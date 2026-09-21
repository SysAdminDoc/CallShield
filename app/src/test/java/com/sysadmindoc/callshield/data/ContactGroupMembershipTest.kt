package com.sysadmindoc.callshield.data

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Contact-group trust against a fake Contacts provider. Apps targeting API 37
 * get strict grammar checks on ContactsContract.Data; the membership read has
 * to stay inside them and still find the same members.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ContactGroupMembershipTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val provider = Robolectric.setupContentProvider(FakeContacts::class.java, ContactsContract.AUTHORITY)
    private val family = ContactGroupCatalog.stableKey("me@example.test", "com.google", "family-source", "Family", FAMILY_GROUP)
    private val work = ContactGroupCatalog.stableKey("me@example.test", "com.google", "work-source", "Work", WORK_GROUP)

    @Test
    fun `a caller in a selected group is trusted and one outside it is not`() {
        assertTrue(ContactGroupCatalog.isNumberInSelectedGroups(context, CALLER, setOf(family)))
        assertFalse(ContactGroupCatalog.isNumberInSelectedGroups(context, CALLER, setOf(work)))
    }

    @Test
    fun `every membership read is plain equality on bound arguments`() {
        ContactGroupCatalog.isNumberInSelectedGroups(context, CALLER, setOf(work))

        assertTrue(provider.dataSelections.isNotEmpty())
        provider.dataSelections.forEach { selection ->
            assertEquals(ContactGroupCatalog.MEMBERSHIP_SELECTION, selection)
            assertFalse("no composed IN list: $selection", " IN " in selection.orEmpty().uppercase())
        }
    }

    @Test
    fun `losing contacts access falls back to untrusted instead of throwing`() {
        provider.denied = true

        assertFalse(ContactGroupCatalog.isNumberInSelectedGroups(context, CALLER, setOf(family)))
    }

    class FakeContacts : ContentProvider() {
        val dataSelections = mutableListOf<String?>()
        var denied = false

        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            if (denied) throw SecurityException("READ_CONTACTS revoked")
            val path = uri.pathSegments.firstOrNull()
            return when {
                // Two contacts share the caller's number; only the second is in Family.
                path == "phone_lookup" -> {
                    MatrixCursor(arrayOf(ContactsContract.PhoneLookup._ID)).apply {
                        addRow(arrayOf<Any>(OTHER_CONTACT))
                        addRow(arrayOf<Any>(FAMILY_CONTACT))
                    }
                }

                path == "groups" -> {
                    MatrixCursor(
                        arrayOf(
                            ContactsContract.Groups._ID,
                            ContactsContract.Groups.TITLE,
                            ContactsContract.Groups.ACCOUNT_NAME,
                            ContactsContract.Groups.ACCOUNT_TYPE,
                            ContactsContract.Groups.SOURCE_ID,
                        ),
                    ).apply {
                        addRow(arrayOf<Any>(FAMILY_GROUP, "Family", "me@example.test", "com.google", "family-source"))
                        addRow(arrayOf<Any>(WORK_GROUP, "Work", "me@example.test", "com.google", "work-source"))
                    }
                }

                path == "data" -> {
                    dataSelections += selection
                    val contact = selectionArgs?.firstOrNull()?.toLongOrNull()
                    MatrixCursor(arrayOf(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID)).apply {
                        if (contact == FAMILY_CONTACT) addRow(arrayOf<Any>(FAMILY_GROUP))
                        if (contact == OTHER_CONTACT) addRow(arrayOf<Any>(OTHER_GROUP))
                    }
                }

                else -> {
                    null
                }
            }
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0
    }

    private companion object {
        const val CALLER = "+12125550101"
        const val OTHER_CONTACT = 11L
        const val FAMILY_CONTACT = 12L
        const val FAMILY_GROUP = 3L
        const val WORK_GROUP = 4L
        const val OTHER_GROUP = 9L
    }
}
