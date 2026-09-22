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
 * Contact-group trust against a fake Contacts provider that evaluates the
 * selection it's given over modelled Data rows. One of those rows is a phone
 * row whose DATA1 reads as the Work group's id, so a membership read that
 * loses its MIMETYPE term finds a member that isn't one.
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
    fun `a non-membership row whose DATA1 matches a group id is not membership`() {
        // OTHER_CONTACT's phone row carries WORK_GROUP in DATA1. Only the
        // MIMETYPE term keeps it from reading as a Work membership.
        assertFalse(ContactGroupCatalog.isNumberInSelectedGroups(context, CALLER, setOf(work)))
        assertTrue(provider.dataReads.isNotEmpty())
    }

    @Test
    fun `membership reads bind their values instead of splicing them into the SQL`() {
        ContactGroupCatalog.isNumberInSelectedGroups(context, CALLER, setOf(work))

        assertTrue(provider.dataReads.isNotEmpty())
        provider.dataReads.forEach { selection ->
            assertEquals(ContactGroupCatalog.MEMBERSHIP_SELECTION, selection)
            listOf(OTHER_CONTACT.toString(), FAMILY_CONTACT.toString(), GROUP_MEMBERSHIP).forEach { value ->
                assertFalse("$value spliced into $selection", value in selection.orEmpty())
            }
        }
    }

    @Test
    fun `losing contacts access at the membership read falls back to untrusted`() {
        provider.deniedPaths = setOf("data")

        assertFalse(ContactGroupCatalog.isNumberInSelectedGroups(context, CALLER, setOf(family)))
        assertTrue("the membership read was reached", provider.dataReads.isNotEmpty())
    }

    @Test
    fun `losing contacts access before the lookup falls back to untrusted`() {
        provider.deniedPaths = setOf("phone_lookup", "groups", "data")

        assertFalse(ContactGroupCatalog.isNumberInSelectedGroups(context, CALLER, setOf(family)))
    }

    class FakeContacts : ContentProvider() {
        val dataReads = mutableListOf<String?>()
        var deniedPaths = emptySet<String>()

        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            val path = uri.pathSegments.firstOrNull()
            if (path == "data") dataReads += selection
            if (path in deniedPaths) throw SecurityException("READ_CONTACTS revoked")
            return when (path) {
                // Two contacts share the caller's number; only the second is in Family.
                "phone_lookup" -> {
                    MatrixCursor(arrayOf(ContactsContract.PhoneLookup._ID)).apply {
                        addRow(arrayOf<Any>(OTHER_CONTACT))
                        addRow(arrayOf<Any>(FAMILY_CONTACT))
                    }
                }

                "groups" -> {
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

                "data" -> {
                    queryData(projection, selection, selectionArgs)
                }

                else -> {
                    null
                }
            }
        }

        /** Evaluates `column=? AND column=?` over [DATA_ROWS], the only shape a membership read may use. */
        private fun queryData(
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Cursor {
            val terms = selection.orEmpty().split(" AND ").map(String::trim)
            require(terms.all { it.endsWith("=?") }) { "unsupported selection: $selection" }
            val columns = terms.map { it.removeSuffix("=?") }
            val args = selectionArgs.orEmpty().toList()
            require(columns.size == args.size) { "selection $selection takes ${columns.size} args, got ${args.size}" }
            val columnsOut = requireNotNull(projection) { "no projection" }
            return MatrixCursor(columnsOut).apply {
                DATA_ROWS
                    .filter { row -> columns.zip(args).all { (column, arg) -> row.valueOf(column) == arg } }
                    .forEach { row -> addRow(columnsOut.map(row::valueOf).toTypedArray()) }
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

    private data class DataRow(
        val contactId: Long,
        val mimeType: String,
        val data1: String,
    ) {
        fun valueOf(column: String): String =
            when (column) {
                ContactsContract.Data.CONTACT_ID -> contactId.toString()
                ContactsContract.Data.MIMETYPE -> mimeType
                ContactsContract.Data.DATA1 -> data1
                else -> error("the fake has no column $column")
            }
    }

    private companion object {
        const val CALLER = "+12125550101"
        const val OTHER_CONTACT = 11L
        const val FAMILY_CONTACT = 12L
        const val FAMILY_GROUP = 3L
        const val WORK_GROUP = 4L
        const val OTHER_GROUP = 9L
        const val GROUP_MEMBERSHIP = ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE

        val DATA_ROWS =
            listOf(
                DataRow(FAMILY_CONTACT, GROUP_MEMBERSHIP, FAMILY_GROUP.toString()),
                DataRow(OTHER_CONTACT, GROUP_MEMBERSHIP, OTHER_GROUP.toString()),
                DataRow(OTHER_CONTACT, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, WORK_GROUP.toString()),
            )
    }
}
