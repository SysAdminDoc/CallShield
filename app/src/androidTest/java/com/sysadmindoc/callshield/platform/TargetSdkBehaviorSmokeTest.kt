package com.sysadmindoc.callshield.platform

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sysadmindoc.callshield.permissions.CallShieldPermissions
import com.sysadmindoc.callshield.service.NotificationHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TargetSdkBehaviorSmokeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Suppress("DEPRECATION")
    private val packageInfo =
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS or
                PackageManager.GET_RECEIVERS or
                PackageManager.GET_SERVICES,
        )

    @Test
    fun targetSdkModernPermissionDeclarationsArePresent() {
        assertEquals(36, packageInfo.applicationInfo!!.targetSdkVersion)

        val permissions = packageInfo.requestedPermissions.orEmpty().toSet()
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in permissions)
        assertTrue(Manifest.permission.SYSTEM_ALERT_WINDOW in permissions)
        assertTrue(Manifest.permission.READ_PHONE_STATE in permissions)
        assertTrue(Manifest.permission.ANSWER_PHONE_CALLS in permissions)
        assertTrue(Manifest.permission.RECEIVE_SMS in permissions)
        assertTrue(Manifest.permission.READ_SMS in permissions)
        assertFalse(Manifest.permission.USE_FULL_SCREEN_INTENT in permissions)
    }

    @Test
    fun android16SdkIntFullContractIsVisibleWhenAvailable() {
        val sdkIntFull = readSdkIntFullOrNull()

        if (Build.VERSION.SDK_INT >= 36) {
            assertNotNull(sdkIntFull)
            assertTrue(sdkIntFull!! >= Build.VERSION.SDK_INT)
        } else {
            assertEquals(null, sdkIntFull)
        }
    }

    @Test
    fun targetSdkRuntimePermissionHelpersRemainSafe() {
        val declaredPermissions = packageInfo.requestedPermissions.orEmpty().toSet()
        val missingPermissions = CallShieldPermissions.missingCorePermissions(context)

        assertTrue(missingPermissions.all { permission -> permission in declaredPermissions })

        val expectedNotificationPermission =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
        assertEquals(expectedNotificationPermission, CallShieldPermissions.hasNotificationPermission(context))
    }

    @Test
    fun permissionContractManifestPermissionsRemainDeclared() {
        val declaredPermissions = packageInfo.requestedPermissions.orEmpty().toSet()
        val contractPermissions =
            CallShieldPermissions.permissionCapabilityContracts
                .mapNotNull { contract -> contract.manifestPermission }

        assertTrue(contractPermissions.isNotEmpty())
        assertTrue(contractPermissions.all { permission -> permission in declaredPermissions })

        val contractStates = CallShieldPermissions.permissionContractStates(context)
        assertEquals(CallShieldPermissions.permissionCapabilityContracts.size, contractStates.size)
    }

    @Test
    fun protectedPlatformServicesKeepRequiredBindPermissions() {
        val services = packageInfo.services.orEmpty().associateBy { it.name }

        val screening = services["com.sysadmindoc.callshield.service.CallShieldScreeningService"]
        assertNotNull(screening)
        assertTrue(screening!!.exported)
        assertEquals(Manifest.permission.BIND_SCREENING_SERVICE, screening.permission)

        val rcs = services["com.sysadmindoc.callshield.service.RcsNotificationListener"]
        assertNotNull(rcs)
        assertTrue(rcs!!.exported)
        assertEquals(Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE, rcs.permission)

        val tile = services["com.sysadmindoc.callshield.service.CallShieldTileService"]
        assertNotNull(tile)
        assertTrue(tile!!.exported)
        assertEquals(Manifest.permission.BIND_QUICK_SETTINGS_TILE, tile.permission)
    }

    @Test
    fun notificationChannelsStayStandardAndPermissionSafe() {
        NotificationHelper.createChannels(context)

        val notificationManager = context.getSystemService(NotificationManager::class.java)

        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            notificationManager.getNotificationChannel(NotificationHelper.CHANNEL_BLOCKED).importance,
        )
        assertEquals(
            NotificationManager.IMPORTANCE_HIGH,
            notificationManager.getNotificationChannel(NotificationHelper.CHANNEL_PHISHING).importance,
        )
        assertEquals(
            NotificationManager.IMPORTANCE_MIN,
            notificationManager.getNotificationChannel(NotificationHelper.CHANNEL_STATUS).importance,
        )
        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            notificationManager.getNotificationChannel(NotificationHelper.CHANNEL_DIGEST).importance,
        )
    }

    @Test
    fun smsReceiverContractIsExplicitForAndroid17OtpDelayBehavior() {
        val declaredPermissions = packageInfo.requestedPermissions.orEmpty().toSet()
        assertTrue(Manifest.permission.RECEIVE_SMS in declaredPermissions)
        assertTrue(Manifest.permission.READ_SMS in declaredPermissions)

        val receiver =
            packageInfo.receivers
                .orEmpty()
                .firstOrNull { receiver ->
                    receiver.name == "com.sysadmindoc.callshield.service.SmsReceiver"
                }
        assertNotNull(receiver)
        assertTrue(receiver!!.exported)
        assertEquals(Manifest.permission.BROADCAST_SMS, receiver.permission)

        val smsReceivers =
            context.packageManager.queryBroadcastReceivers(
                Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).setPackage(context.packageName),
                PackageManager.GET_RESOLVED_FILTER,
            )
        assertTrue(
            smsReceivers.any { resolveInfo ->
                resolveInfo.activityInfo.name == "com.sysadmindoc.callshield.service.SmsReceiver"
            },
        )
        assertTrue(
            ANDROID_17_OTP_DELAY_TARGET_MESSAGE,
            packageInfo.applicationInfo!!.targetSdkVersion < 37,
        )
    }

    private fun readSdkIntFullOrNull(): Int? =
        runCatching {
            Build.VERSION::class.java
                .getField("SDK_INT_FULL")
                .getInt(null)
        }.getOrNull()

    companion object {
        private const val ANDROID_17_OTP_DELAY_TARGET_MESSAGE =
            "Android 17 standard OTP SMS delay starts at target SDK 37; " +
                "update this smoke matrix when targetSdk changes."
    }
}
