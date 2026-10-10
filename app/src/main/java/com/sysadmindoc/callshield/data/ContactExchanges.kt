package com.sysadmindoc.callshield.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.core.content.ContextCompat

/** Which exchanges the phone's contacts are in, so a block suggestion never covers one. */
internal object ContactExchanges {
    /**
     * Every contact number's exchange as [exchangeOf] names it, or null when
     * the contacts can't be read. Null is not an empty set: without the
     * permission nothing says an exchange holds no contact.
     */
    fun read(
        context: Context,
        exchangeOf: (String) -> String?,
    ): Set<String>? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        return try {
            context.contentResolver
                .query(Phone.CONTENT_URI, arrayOf(Phone.NORMALIZED_NUMBER, Phone.NUMBER), null, null, null)
                ?.use { cursor ->
                    buildSet {
                        while (cursor.moveToNext()) {
                            // The normalized form has the country code; the typed one may not.
                            val number = cursor.getString(0) ?: cursor.getString(1) ?: continue
                            exchangeOf(number)?.let(::add)
                        }
                    }
                }
        } catch (_: RuntimeException) {
            null
        }
    }
}
