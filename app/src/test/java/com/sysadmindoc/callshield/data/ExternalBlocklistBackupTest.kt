package com.sysadmindoc.callshield.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.sysadmindoc.callshield.data.BackupRestore.BackupExternalBlocklist
import com.sysadmindoc.callshield.data.BackupRestore.BackupSettings
import com.sysadmindoc.callshield.data.model.ExternalBlocklistSubscription
import com.sysadmindoc.callshield.data.model.ListNumberPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A backup carries the lists someone subscribed to, and none of this phone's fetch state. */
class ExternalBlocklistBackupTest {
    private val adapter =
        Moshi
            .Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
            .adapter<List<ExternalBlocklistSubscription>>(
                Types.newParameterizedType(List::class.java, ExternalBlocklistSubscription::class.java),
            )

    private fun phoneWith(vararg subscriptions: ExternalBlocklistSubscription): MutablePreferences = mutablePreferencesOf(SpamRepository.KEY_EXTERNAL_BLOCKLIST_SUBSCRIPTIONS to adapter.toJson(subscriptions.toList()))

    private fun fetched(
        url: String,
        label: String,
    ) = ExternalBlocklistSubscription(
        id = ExternalBlocklistParser.idForUrl(url),
        label = label,
        url = url,
        lastSyncedAt = 1_700_000_000_000L,
        lastNumberCount = 420,
        lastAdded = 12,
        lastRemoved = 3,
        lastError = "timed out once",
        declaredRefreshHours = 12,
        lastAttemptAt = 1_700_000_000_000L,
    )

    @Test
    fun `a backup keeps the address, name, switch and plan but no fetch state`() {
        val colombia =
            fetched(CO_LIST, "Colombia").copy(
                enabled = false,
                numberPlan = ListNumberPlan("CO", "57", "0", listOf(10)),
                catalogId = "example-co",
            )

        val settings = phoneWith(colombia).toBackupSettings()

        assertEquals(
            listOf(BackupExternalBlocklist(CO_LIST, "Colombia", enabled = false, numberPlan = colombia.numberPlan, catalogId = "example-co")),
            settings.externalBlocklists,
        )
        val json = BackupRestore.backupToJson(BackupRestore.Backup(settings = settings))
        listOf("lastSyncedAt", "lastNumberCount", "lastAdded", "lastRemoved", "lastError", "declaredRefreshHours", "lastAttemptAt")
            .forEach { field -> assertFalse("$field is in the backup", json.contains(field)) }
    }

    @Test
    fun `a restore adds the backup's lists as new ones and leaves the phone's own alone`() {
        val mine = fetched(MY_LIST, "Mine")
        val phone = phoneWith(mine)
        val backup =
            BackupSettings(
                externalBlocklists =
                    listOf(
                        BackupExternalBlocklist(US_LIST, "Robocalls"),
                        // The phone has this one already, switched on; the backup's copy changes nothing.
                        BackupExternalBlocklist(MY_LIST, "Renamed", enabled = false),
                    ),
            )

        backup
            .sanitized()
            .addingExternalBlocklistsTo(phone.toBackupSettings().externalBlocklists)
            .sanitized()
            .writeTo(phone)

        val lists = phone.externalBlocklistSubscriptions().associateBy { it.url }
        assertEquals(setOf(MY_LIST, US_LIST), lists.keys)
        assertEquals(mine, lists.getValue(MY_LIST))
        val added = lists.getValue(US_LIST)
        assertEquals(ExternalBlocklistParser.idForUrl(US_LIST), added.id)
        assertEquals("Robocalls", added.label)
        assertTrue(added.enabled)
        assertEquals(0L, added.lastSyncedAt)
        assertEquals(0L, added.lastAttemptAt)
        assertTrue("a restored list fetches on the next refresh", ExternalBlocklistRefreshPolicy.isDue(added, System.currentTimeMillis()))
    }

    @Test
    fun `writing the phone's snapshot back takes the added lists away again`() {
        val mine = fetched(MY_LIST, "Mine")
        val phone = phoneWith(mine)
        val snapshot = phone.toBackupSettings()
        BackupSettings(externalBlocklists = listOf(BackupExternalBlocklist(US_LIST, "Robocalls")))
            .addingExternalBlocklistsTo(snapshot.externalBlocklists)
            .sanitized()
            .writeTo(phone)
        val restored = phone.externalBlocklistSubscriptions().single { it.url == US_LIST }

        val undo = snapshot.sanitized()
        assertEquals(listOf(restored.source), phone.droppedExternalBlocklistSources(undo))
        undo.writeTo(phone)

        assertEquals(listOf(mine), phone.externalBlocklistSubscriptions())
    }

    @Test
    fun `a restore never drops one of the phone's lists`() {
        val phone = phoneWith(fetched(MY_LIST, "Mine"))
        val desired =
            BackupSettings(externalBlocklists = emptyList())
                .addingExternalBlocklistsTo(phone.toBackupSettings().externalBlocklists)
                .sanitized()

        assertEquals(emptyList<String>(), phone.droppedExternalBlocklistSources(desired))
        desired.writeTo(phone)

        assertEquals(listOf(MY_LIST), phone.externalBlocklistSubscriptions().map { it.url })
    }

    @Test
    fun `a backup from before lists were saved leaves them alone`() {
        val phone = phoneWith(fetched(MY_LIST, "Mine"))
        val before = requireNotNull(phone[SpamRepository.KEY_EXTERNAL_BLOCKLIST_SUBSCRIPTIONS])

        val old = BackupRestore.backupFromJson("""{"version":9,"app":"CallShield","timestamp":1,"settings":{}}""")
        val settings = requireNotNull(old?.settings)
        assertNull(settings.externalBlocklists)
        settings.sanitized().writeTo(phone)

        assertEquals(before, phone[SpamRepository.KEY_EXTERNAL_BLOCKLIST_SUBSCRIPTIONS])
    }

    @Test
    fun `a list added before addresses had to be HTTPS survives a restore and its undo`() {
        val legacy = fetched(LEGACY_LIST, "Old list")
        val phone = phoneWith(legacy)
        val snapshot = phone.toBackupSettings()
        val desired =
            BackupSettings(externalBlocklists = listOf(BackupExternalBlocklist(US_LIST, "Robocalls")))
                .sanitized()
                .addingExternalBlocklistsTo(snapshot.externalBlocklists)
                .sanitized()

        assertEquals("its numbers would be deleted", emptyList<String>(), phone.droppedExternalBlocklistSources(desired))
        desired.writeTo(phone)
        assertEquals(setOf(LEGACY_LIST, US_LIST), phone.externalBlocklistSubscriptions().map { it.url }.toSet())

        snapshot.sanitized().writeTo(phone)
        assertEquals(listOf(legacy), phone.externalBlocklistSubscriptions())
    }

    @Test
    fun `a backup's lists are checked like a typed address`() {
        val sanitized =
            BackupSettings(
                externalBlocklists =
                    listOf(
                        BackupExternalBlocklist("http://lists.example/plain.txt", "Not HTTPS"),
                        BackupExternalBlocklist("https://user:secret@lists.example/a.txt", "Credentials"),
                        BackupExternalBlocklist(
                            US_LIST,
                            "  ",
                            numberPlan = ListNumberPlan("usa", "1", "", listOf(10)),
                            catalogId = "Not An Id!",
                        ),
                        BackupExternalBlocklist("$US_LIST#again", "Same list"),
                    ),
            ).sanitized()
                .addingExternalBlocklistsTo(emptyList())

        assertEquals(listOf(BackupExternalBlocklist(US_LIST, "lists.example")), sanitized.externalBlocklists)
    }

    private companion object {
        const val MY_LIST = "https://lists.example/mine.txt"
        const val LEGACY_LIST = "http://lists.example/old.txt"
        const val US_LIST = "https://lists.example/us.txt"
        const val CO_LIST = "https://lists.example/co.json"
    }
}
