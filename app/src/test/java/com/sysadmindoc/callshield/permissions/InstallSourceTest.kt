package com.sysadmindoc.callshield.permissions

import org.junit.Assert.assertEquals
import org.junit.Test

class InstallSourceTest {
    @Test
    fun `adb or nothing on record reads as the shell`() {
        assertEquals(InstallSourceKind.Shell, InstallSource.classify(null))
        assertEquals(InstallSourceKind.Shell, InstallSource.classify("com.android.shell"))
    }

    @Test
    fun `the phone's own package installer reads as the system installer`() {
        assertEquals(InstallSourceKind.SystemInstaller, InstallSource.classify("com.google.android.packageinstaller"))
        assertEquals(InstallSourceKind.SystemInstaller, InstallSource.classify("com.android.packageinstaller"))
        assertEquals(InstallSourceKind.SystemInstaller, InstallSource.classify("com.miui.packageinstaller"))
    }

    @Test
    fun `Google Play reads as the Play Store`() {
        assertEquals(InstallSourceKind.PlayStore, InstallSource.classify("com.android.vending"))
    }

    @Test
    fun `any other app store or installer reads as another installer`() {
        listOf("org.fdroid.fdroid", "dev.imranr.obtainium", "com.looker.droidify", "io.github.muntashirakon.AppManager").forEach {
            assertEquals(it, InstallSourceKind.OtherInstaller, InstallSource.classify(it))
        }
    }

    @Test
    fun `an installer that came with the phone counts as the phone's own`() {
        assertEquals(InstallSourceKind.SystemInstaller, InstallSource.classify("com.oplus.appdetail", installerIsSystemApp = true))
    }
}
