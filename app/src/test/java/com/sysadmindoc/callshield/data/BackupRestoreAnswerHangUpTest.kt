package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.data.BackupRestore.Backup
import com.sysadmindoc.callshield.data.BackupRestore.BackupSettings
import com.sysadmindoc.callshield.service.AnswerHangUpController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRestoreAnswerHangUpTest {
    @Test
    fun `answer hang up settings round trip through backup JSON`() {
        val backup =
            Backup(
                settings =
                    BackupSettings(
                        answerHangUpEnabled = true,
                        hangUpDelaySeconds = 7,
                    ),
            )

        val validation = BackupRestore.validateBackupJson(BackupRestore.backupToJson(backup))

        assertTrue(validation is BackupRestore.RestoreValidation.Valid)
        val settings = (validation as BackupRestore.RestoreValidation.Valid).payload.settings
        assertEquals(true, settings?.answerHangUpEnabled)
        assertEquals(7, settings?.hangUpDelaySeconds)
    }

    @Test
    fun `older backup without answer hang up settings restores defaults`() {
        val validation =
            BackupRestore.validateBackupJson(
                """{"version":9,"app":"CallShield","settings":{"blockCallsEnabled":false}}""",
            )

        assertTrue(validation is BackupRestore.RestoreValidation.Valid)
        val settings = (validation as BackupRestore.RestoreValidation.Valid).payload.settings
        assertFalse(settings?.answerHangUpEnabled ?: true)
        assertEquals(AnswerHangUpController.DEFAULT_DELAY_SECONDS, settings?.hangUpDelaySeconds)
    }

    @Test
    fun `restored hang up delay is clamped`() {
        val backup = Backup(settings = BackupSettings(hangUpDelaySeconds = 100))

        val validation = BackupRestore.validateBackupJson(BackupRestore.backupToJson(backup))

        assertTrue(validation is BackupRestore.RestoreValidation.Valid)
        val settings = (validation as BackupRestore.RestoreValidation.Valid).payload.settings
        assertEquals(AnswerHangUpController.MAX_DELAY_SECONDS, settings?.hangUpDelaySeconds)
    }
}
