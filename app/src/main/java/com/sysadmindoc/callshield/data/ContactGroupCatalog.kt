package com.sysadmindoc.callshield.data

import android.content.Context
import android.provider.ContactsContract
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

data class ContactGroup(
    val key: String,
    val title: String,
    val accountName: String?,
    val memberCount: Int,
)

/** Local-only discovery and matching for Android contact groups. */
internal object ContactGroupCatalog {
    private const val MAX_SELECTED_GROUPS = 100
    private const val KEY_HEX_LENGTH = 64
    private const val BYTE_MASK = 0xff
    private val keyPattern = Regex("^[0-9a-f]{$KEY_HEX_LENGTH}$")
    private val unavailableScopeKey = "0".repeat(KEY_HEX_LENGTH)

    fun sanitizeKeys(keys: Collection<String>): Set<String> =
        keys
            .asSequence()
            .map(String::trim)
            .filter(keyPattern::matches)
            .distinct()
            .take(MAX_SELECTED_GROUPS)
            .toCollection(linkedSetOf())

    /**
     * Retains the distinction between an absent preference (all contacts) and
     * a present but malformed scoped preference. A malformed scoped value must
     * never silently broaden back to all contacts.
     */
    fun preserveScope(keys: Collection<String>): Set<String> {
        val sanitized = sanitizeKeys(keys)
        return if (keys.isNotEmpty() && sanitized.isEmpty()) setOf(unavailableScopeKey) else sanitized
    }

    fun loadGroups(context: Context): List<ContactGroup> =
        try {
            context.contentResolver
                .query(
                    ContactsContract.Groups.CONTENT_SUMMARY_URI,
                    GROUP_SUMMARY_PROJECTION,
                    "${ContactsContract.Groups.DELETED}=0 AND ${ContactsContract.Groups.AUTO_ADD}=0",
                    null,
                    "${ContactsContract.Groups.TITLE} COLLATE NOCASE ASC",
                )?.use { cursor ->
                    val titleIndex = cursor.getColumnIndexOrThrow(ContactsContract.Groups.TITLE)
                    val accountNameIndex = cursor.getColumnIndexOrThrow(ContactsContract.Groups.ACCOUNT_NAME)
                    val accountTypeIndex = cursor.getColumnIndexOrThrow(ContactsContract.Groups.ACCOUNT_TYPE)
                    val sourceIdIndex = cursor.getColumnIndexOrThrow(ContactsContract.Groups.SOURCE_ID)
                    val countIndex = cursor.getColumnIndexOrThrow(ContactsContract.Groups.SUMMARY_WITH_PHONES)
                    buildList {
                        while (cursor.moveToNext()) {
                            val title = cursor.getString(titleIndex)?.trim().orEmpty()
                            val memberCount = cursor.getInt(countIndex).coerceAtLeast(0)
                            if (title.isNotEmpty() && memberCount > 0) {
                                val accountName = cursor.getString(accountNameIndex)?.trim()?.takeIf(String::isNotEmpty)
                                val accountType = cursor.getString(accountTypeIndex)?.trim().orEmpty()
                                val sourceId = cursor.getString(sourceIdIndex)?.trim()?.takeIf(String::isNotEmpty)
                                add(
                                    ContactGroup(
                                        key = stableKey(accountName, accountType, sourceId, title),
                                        title = title,
                                        accountName = accountName,
                                        memberCount = memberCount,
                                    ),
                                )
                            }
                        }
                    }.distinctBy(ContactGroup::key)
                } ?: emptyList()
        } catch (_: RuntimeException) {
            emptyList()
        }

    fun isNumberInSelectedGroups(
        context: Context,
        number: String,
        selectedKeys: Set<String>,
    ): Boolean {
        return selectedKeys.isNotEmpty() &&
            try {
                val contactIds = lookupContactIds(context, number)
                val groupIds = resolveGroupIds(context, selectedKeys)
                contactIds.isNotEmpty() && groupIds.isNotEmpty() && hasMembership(context, contactIds, groupIds)
            } catch (_: RuntimeException) {
                false
            }
    }

    internal fun stableKey(
        accountName: String?,
        accountType: String?,
        sourceId: String?,
        title: String,
    ): String {
        val stableIdentity = sourceId?.trim()?.takeIf(String::isNotEmpty) ?: title.trim()
        val material = listOf(accountType.orEmpty(), accountName.orEmpty(), stableIdentity).joinToString("\u0000")
        return MessageDigest
            .getInstance("SHA-256")
            .digest(material.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and BYTE_MASK) }
    }

    private fun lookupContactIds(
        context: Context,
        number: String,
    ): Set<Long> {
        val uri =
            android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(number),
            )
        return context.contentResolver
            .query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)
            ?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup._ID)
                buildSet {
                    while (cursor.moveToNext() && size < MAX_PHONE_LOOKUP_MATCHES) add(cursor.getLong(idIndex))
                }
            }.orEmpty()
    }

    private fun resolveGroupIds(
        context: Context,
        selectedKeys: Set<String>,
    ): Set<Long> =
        context.contentResolver
            .query(
                ContactsContract.Groups.CONTENT_URI,
                GROUP_IDENTITY_PROJECTION,
                "${ContactsContract.Groups.DELETED}=0 AND ${ContactsContract.Groups.AUTO_ADD}=0",
                null,
                null,
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.Groups._ID)
                val titleIndex = cursor.getColumnIndexOrThrow(ContactsContract.Groups.TITLE)
                val accountNameIndex = cursor.getColumnIndexOrThrow(ContactsContract.Groups.ACCOUNT_NAME)
                val accountTypeIndex = cursor.getColumnIndexOrThrow(ContactsContract.Groups.ACCOUNT_TYPE)
                val sourceIdIndex = cursor.getColumnIndexOrThrow(ContactsContract.Groups.SOURCE_ID)
                buildSet {
                    while (cursor.moveToNext()) {
                        val key =
                            stableKey(
                                cursor.getString(accountNameIndex),
                                cursor.getString(accountTypeIndex),
                                cursor.getString(sourceIdIndex),
                                cursor.getString(titleIndex).orEmpty(),
                            )
                        if (key in selectedKeys) add(cursor.getLong(idIndex))
                    }
                }
            }.orEmpty()

    private fun hasMembership(
        context: Context,
        contactIds: Set<Long>,
        groupIds: Set<Long>,
    ): Boolean {
        val contactPlaceholders = contactIds.joinToString(",") { "?" }
        val groupPlaceholders = groupIds.joinToString(",") { "?" }
        val selection =
            "${ContactsContract.Data.MIMETYPE}=? AND " +
                "${ContactsContract.Data.CONTACT_ID} IN ($contactPlaceholders) AND " +
                "${ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID} IN ($groupPlaceholders)"
        val args =
            buildList {
                add(ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
                contactIds.forEach { add(it.toString()) }
                groupIds.forEach { add(it.toString()) }
            }.toTypedArray()
        return context.contentResolver
            .query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID),
                selection,
                args,
                null,
            )?.use { it.moveToFirst() } ?: false
    }

    private val GROUP_IDENTITY_PROJECTION =
        arrayOf(
            ContactsContract.Groups._ID,
            ContactsContract.Groups.TITLE,
            ContactsContract.Groups.ACCOUNT_NAME,
            ContactsContract.Groups.ACCOUNT_TYPE,
            ContactsContract.Groups.SOURCE_ID,
        )

    private val GROUP_SUMMARY_PROJECTION =
        GROUP_IDENTITY_PROJECTION +
            arrayOf(
            ContactsContract.Groups.SUMMARY_WITH_PHONES,
            )

    private const val MAX_PHONE_LOOKUP_MATCHES = 16
}
