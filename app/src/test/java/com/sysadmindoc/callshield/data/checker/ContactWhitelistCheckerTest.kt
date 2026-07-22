package com.sysadmindoc.callshield.data.checker

import android.content.Context
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.ContactGroupCatalog
import com.sysadmindoc.callshield.data.SpamHeuristics
import com.sysadmindoc.callshield.data.SpamRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContactWhitelistCheckerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `absent group preference checks all contacts`() =
        runBlocking {
            var observedScope: Set<String>? = setOf("unexpected")
            val checker =
                checker { _, _, scope ->
                    observedScope = scope
                    true
                }

            val result = checker.check(checkContext())

            assertNotNull(result)
            assertEquals("contact_whitelist", result?.matchSource)
            assertNull(observedScope)
        }

    @Test
    fun `selected group keys are forwarded to the contact lookup`() =
        runBlocking {
            val key = ContactGroupCatalog.stableKey("account", "type", "source", "Friends")
            var observedScope: Set<String>? = null
            val checker =
                checker { _, _, scope ->
                    observedScope = scope
                    true
                }

            val result =
                checker.check(
                    checkContext(
                        mutablePreferencesOf(SpamRepository.KEY_SELECTED_CONTACT_GROUPS to setOf(key)),
                    ),
                )

            assertNotNull(result)
            assertEquals(setOf(key), observedScope)
        }

    @Test
    fun `denied contact lookup never produces an allow`() =
        runBlocking {
            var queried = false
            val checker =
                checker { _, _, _ ->
                    queried = true
                    false
                }

            val result = checker.check(checkContext())

            assertNull(result)
            assertFalse(result?.shouldBlock == false)
            assertEquals(true, queried)
        }

    @Test
    fun `malformed stored group remains a scoped fail closed lookup`() =
        runBlocking {
            var observedScope: Set<String>? = null
            val checker =
                checker { _, _, scope ->
                    observedScope = scope
                    false
                }

            val result =
                checker.check(
                    checkContext(
                        mutablePreferencesOf(SpamRepository.KEY_SELECTED_CONTACT_GROUPS to setOf("raw group")),
                    ),
                )

            assertNull(result)
            assertEquals(1, observedScope?.size)
            assertFalse(observedScope.isNullOrEmpty())
        }

    private fun checker(lookup: (Context, String, Set<String>?) -> Boolean) = ContactWhitelistChecker(context, SpamHeuristics(), lookup)

    private fun checkContext(
        preferences: androidx.datastore.preferences.core.Preferences = emptyPreferences(),
    ) = CheckContext(
        appContext = context,
        number = "+15551230000",
        realtimeCall = true,
        prefs = preferences,
        startTimeMillis = System.currentTimeMillis(),
    )
}
