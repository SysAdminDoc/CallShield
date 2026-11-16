package com.sysadmindoc.callshield.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.domain.model.BlockReasonCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The political texts preset goes in as ordinary keyword rules and comes out without touching the user's own. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class KeywordPresetRepositoryTest {
    private val fixture = IsolatedRepositoryFixture(ApplicationProvider.getApplicationContext())
    private val repository = fixture.repository
    private val preset = KeywordPreset.POLITICAL

    @After
    fun tearDown() = fixture.close()

    @Test
    fun `adding the preset keeps a rule the user already had and adds the rest once`() =
        runBlocking {
            repository.addKeywordRule("ActBlue", caseSensitive = true, description = "my own")

            assertEquals(preset.phrases.size - 1, repository.addKeywordPreset(preset))
            assertEquals(0, repository.addKeywordPreset(preset))

            val rules = repository.getAllKeywordRules().first()
            val own = rules.single { it.keyword.equals("actblue", ignoreCase = true) }
            assertEquals("my own", own.description)
            assertTrue(own.caseSensitive)
            assertEquals(preset.phrases.size, rules.size)
            assertEquals(preset.phrases.size - 1, preset.rulesIn(rules).size)
        }

    @Test
    fun `a text the preset covers is flagged once it's added and not once it's removed`() =
        runBlocking {
            repository.addKeywordRule("bank alert")
            assertFalse(repository.isSpamSms(SENDER, FUNDRAISING_TEXT).reasonCode == BlockReasonCode.KEYWORD)

            repository.addKeywordPreset(preset)
            val blocked = repository.isSpamSms(SENDER, FUNDRAISING_TEXT)
            assertTrue(blocked.isSpam)
            assertEquals(BlockReasonCode.KEYWORD, blocked.reasonCode)

            preset.rulesIn(repository.getAllKeywordRules().first()).forEach { repository.deleteKeywordRule(it) }
            assertFalse(repository.isSpamSms(SENDER, FUNDRAISING_TEXT).reasonCode == BlockReasonCode.KEYWORD)
            assertEquals(listOf("bank alert"), repository.getAllKeywordRules().first().map { it.keyword })
        }

    private companion object {
        const val SENDER = "+13125550147"
        const val FUNDRAISING_TEXT = "Hi it's Alex with the Taylor campaign. Will we have your support in November? Pd for by Taylor for Senate"
    }
}
