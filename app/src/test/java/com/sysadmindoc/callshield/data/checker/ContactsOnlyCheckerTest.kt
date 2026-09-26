package com.sysadmindoc.callshield.data.checker

import android.content.Context
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.SpamHeuristics
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContactsOnlyCheckerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `without contacts permission the mode has no opinion instead of blocking everyone`() =
        runBlocking {
            // Every lookup misses without READ_CONTACTS, so the old check rejected
            // every caller, saved contacts included.
            val checker = ContactsOnlyChecker(context, SpamHeuristics(), contactsReadable = { false })

            assertNull(checker.check(checkContext()))
        }

    @Test
    fun `with contacts permission a stranger is still blocked`() =
        runBlocking {
            // Robolectric's contacts provider is empty, so the number is not a contact.
            val checker = ContactsOnlyChecker(context, SpamHeuristics(), contactsReadable = { true })

            assertEquals("contacts_only", checker.check(checkContext())?.matchSource)
        }

    private fun checkContext() =
        CheckContext(
            appContext = context,
            number = "+15551230000",
            realtimeCall = true,
            prefs = emptyPreferences(),
            startTimeMillis = System.currentTimeMillis(),
        )
}
