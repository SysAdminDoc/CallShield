package com.sysadmindoc.callshield.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sysadmindoc.callshield.permissions.CallShieldPermissions
import org.junit.Assert.assertEquals
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
            PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES,
        )

    @Test
    fun targetSdkModernPermissionDeclarationsArePresent() {
        assertTrue(packageInfo.applicationInfo!!.targetSdkVersion >= 35)

        val permissions = packageInfo.requestedPermissions.orEmpty().toSet()
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in permissions)
        assertTrue(Manifest.permission.SYSTEM_ALERT_WINDOW in permissions)
        assertTrue(Manifest.permission.READ_PHONE_STATE in permissions)
        assertTrue(Manifest.permission.ANSWER_PHONE_CALLS in permissions)
        assertTrue(Manifest.permission.RECEIVE_SMS in permissions)
        assertTrue(Manifest.permission.READ_SMS in permissions)
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
}
