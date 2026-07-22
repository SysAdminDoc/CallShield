package com.sysadmindoc.callshield.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sysadmindoc.callshield.data.local.AppDatabase
import com.sysadmindoc.callshield.data.local.SpamDao
import com.sysadmindoc.callshield.data.model.SmsKeywordRule
import com.sysadmindoc.callshield.data.model.WhitelistEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmsPipelineIntegrationTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dao: SpamDao
    private lateinit var repo: SpamRepository

    @Before
    fun setUp() =
        runBlocking {
            context = ApplicationProvider.getApplicationContext()
            db =
                Room
                    .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            dao = db.spamDao()
            repo = SpamRepository(context, db)
            resetHotPathSettings()
        }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun smsKeywordRulesStillInspectWhitelistedSenders() =
        runBlocking {
            val sender = "+13105550101"
            dao.insertWhitelistEntry(WhitelistEntry(number = sender, description = "Known sender"))
            dao.insertKeywordRule(SmsKeywordRule(keyword = "wire transfer", description = "Payment scam"))

            val result = repo.isSpamSms(sender, "Please complete this wire transfer immediately.")

            assertTrue(result.isSpam)
            assertEquals("keyword", result.matchSource)
        }

    @Test
    fun smsKeywordRulesRunBeforeGenericContentAnalysis() =
        runBlocking {
            val sender = "+13105550102"
            dao.insertKeywordRule(SmsKeywordRule(keyword = "custom trigger", description = "User rule"))

            val result =
                repo.isSpamSms(
                    sender,
                    "custom trigger congratulations you have won a free gift claim your prize now https://bit.ly/prize",
                )

            assertTrue(result.isSpam)
            assertEquals("keyword", result.matchSource)
        }

    @Test
    fun smsContentAnalysisBlocksWhenNoKeywordRuleMatches() =
        runBlocking {
            val sender = "+13105550103"

            val result =
                repo.isSpamSms(
                    sender,
                    "Congratulations, you have won a free gift. Claim your prize now at https://bit.ly/prize",
                )

            assertTrue(result.isSpam)
            assertEquals("sms_content", result.matchSource)
        }

    @Test
    fun smsContentAnalysisPreservesOpaqueSenderIdentity() =
        runBlocking {
            val result =
                repo.isSpamSms(
                    "Bank-Alert",
                    "Congratulations, you have won a free gift. Claim your prize now at https://bit.ly/prize",
                )

            assertTrue(result.isSpam)
            assertEquals("sms_content", result.matchSource)
            assertEquals("BANK-ALERT", repo.normalizeSenderIdentity("Bank-Alert"))
        }

    private suspend fun resetHotPathSettings() {
        repo.setContactWhitelist(false)
        repo.setTimeBlock(false)
        repo.setFreqEscalation(false)
        repo.setHeuristics(false)
        repo.setMlScorer(false)
        repo.setPushAlert(false)
        repo.setSmsContent(true)
    }
}
