package com.sysadmindoc.callshield.ui.screens.settings

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.data.RegulatoryPrefix
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.repository.SpamRepositoryAdapter
import com.sysadmindoc.callshield.domain.usecase.ExportLogsUseCase
import com.sysadmindoc.callshield.domain.usecase.ManageBlocklistUseCase
import com.sysadmindoc.callshield.domain.usecase.SyncDatabaseUseCase
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.theme.CallShieldTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Modifier

/**
 * Every DataStore key is either a setting with a control on one of the two
 * Settings tabs or left off Settings on purpose, with the reason here. A key
 * added without a control, or a control lost in a move between cards, fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SettingsRenderCoverageTest {
    @get:Rule
    val composeRule = createComposeRule()

    private enum class Tab { BASIC, ADVANCED }

    /** The tab a setting's control sits on and the text that labels it. */
    private data class Control(
        val tab: Tab,
        @param:StringRes val label: Int,
    )

    private val rendered: Map<String, Control> =
        mapOf(
            "KEY_ACTIVE_PROFILE" to Control(Tab.BASIC, R.string.settings_protection_level),
            "KEY_BLOCK_CALLS" to Control(Tab.BASIC, R.string.settings_block_spam_calls),
            "KEY_BLOCK_SMS" to Control(Tab.BASIC, R.string.settings_block_spam_sms),
            "KEY_BLOCK_UNKNOWN" to Control(Tab.BASIC, R.string.settings_block_unknown),
            "KEY_SILENT_VOICEMAIL" to Control(Tab.ADVANCED, R.string.settings_silent_voicemail),
            "KEY_AUTOMUTE_LOW_CONFIDENCE" to Control(Tab.ADVANCED, R.string.settings_automute_low_confidence),
            "KEY_ANSWER_HANG_UP" to Control(Tab.ADVANCED, R.string.settings_answer_hang_up),
            "KEY_HANG_UP_DELAY_SECONDS" to Control(Tab.ADVANCED, R.string.settings_hang_up_delay),
            "KEY_CATEGORY_CALL_ACTIONS" to Control(Tab.ADVANCED, R.string.settings_category_actions),
            "KEY_CONTACT_WHITELIST" to Control(Tab.BASIC, R.string.settings_contact_whitelist),
            "KEY_SELECTED_CONTACT_GROUPS" to Control(Tab.BASIC, R.string.settings_contact_scope),
            "KEY_CONTACTS_ONLY" to Control(Tab.BASIC, R.string.settings_contacts_only),
            "KEY_OUTGOING_RISK_WARNING" to Control(Tab.BASIC, R.string.settings_outgoing_risk_warning),
            "KEY_OUTGOING_CALL_HOLD" to Control(Tab.BASIC, R.string.settings_outgoing_call_hold),
            "KEY_REGION_BLOCK" to Control(Tab.BASIC, R.string.settings_region_cnap_rules),
            "KEY_ALLOWED_REGIONS" to Control(Tab.BASIC, R.string.settings_region_cnap_rules),
            "KEY_CNAP_TRUST_PATTERNS" to Control(Tab.BASIC, R.string.settings_region_cnap_rules),
            "KEY_CNAP_BLOCK_PATTERNS" to Control(Tab.BASIC, R.string.settings_region_cnap_rules),
            "KEY_POST_CALL_SCREEN" to Control(Tab.BASIC, R.string.settings_post_call_screen),
            "KEY_TIME_BLOCK" to Control(Tab.ADVANCED, R.string.settings_quiet_hours_toggle),
            "KEY_TIME_BLOCK_START" to Control(Tab.ADVANCED, R.string.settings_time_start),
            "KEY_TIME_BLOCK_END" to Control(Tab.ADVANCED, R.string.settings_time_end),
            "KEY_APP_THEME" to Control(Tab.BASIC, R.string.settings_theme),
            "KEY_REG_SPAIN_400" to Control(Tab.ADVANCED, RegulatoryPrefix.SPAIN_400.titleRes),
            "KEY_REG_INDIA_140" to Control(Tab.ADVANCED, RegulatoryPrefix.INDIA_140.titleRes),
            "KEY_REG_BRAZIL_0303" to Control(Tab.ADVANCED, RegulatoryPrefix.BRAZIL_0303.titleRes),
            "KEY_REG_INDIA_1600_ALLOW" to Control(Tab.ADVANCED, RegulatoryPrefix.INDIA_1600.titleRes),
            "KEY_STIR_SHAKEN" to Control(Tab.ADVANCED, R.string.settings_stir_shaken),
            "KEY_STIR_TRUSTED_ALLOW" to Control(Tab.ADVANCED, R.string.settings_stir_trusted_allow),
            "KEY_ANSWERED_CALLER_TRUST" to Control(Tab.ADVANCED, R.string.settings_answered_caller_trust),
            "KEY_ANSWERED_CALLER_THRESHOLD" to Control(Tab.ADVANCED, R.string.settings_answered_caller_threshold),
            "KEY_ANSWERED_CALLER_WINDOW_DAYS" to Control(Tab.ADVANCED, R.string.settings_answered_caller_window),
            "KEY_EMERGENCY_CALLBACK_GRACE" to Control(Tab.ADVANCED, R.string.settings_emergency_callback_grace),
            "KEY_EMERGENCY_CALLBACK_WINDOW_MINUTES" to Control(Tab.ADVANCED, R.string.settings_emergency_callback_window),
            "KEY_NEIGHBOR_SPOOF" to Control(Tab.ADVANCED, R.string.settings_neighbor_spoofing),
            "KEY_HEURISTICS" to Control(Tab.ADVANCED, R.string.settings_heuristic_analysis),
            "KEY_SMS_CONTENT" to Control(Tab.ADVANCED, R.string.settings_sms_content),
            "KEY_REMOTE_URL_LOOKUP" to Control(Tab.ADVANCED, R.string.settings_remote_url_lookup),
            "KEY_LIVE_CALLER_ENRICHMENT" to Control(Tab.ADVANCED, R.string.settings_live_caller_enrichment),
            "KEY_SMS_BURST" to Control(Tab.ADVANCED, R.string.settings_sms_burst),
            "KEY_FREQ_ESCALATION" to Control(Tab.ADVANCED, R.string.settings_repeat_caller),
            "KEY_FREQ_THRESHOLD" to Control(Tab.ADVANCED, R.string.settings_repeat_caller_threshold),
            "KEY_ML_SCORER" to Control(Tab.ADVANCED, R.string.settings_ml_scorer),
            "KEY_DB_PREFIX_EXPANSION" to Control(Tab.ADVANCED, R.string.settings_db_prefix_expansion),
            "KEY_RCS_FILTER" to Control(Tab.ADVANCED, R.string.settings_rcs_filter),
            "KEY_NOTIFICATION_SCREENING_PACKAGES" to Control(Tab.ADVANCED, R.string.settings_notification_screening_sources),
            "KEY_PUSH_ALERT" to Control(Tab.ADVANCED, R.string.settings_push_alert),
            "KEY_PUSH_ALERT_DISABLED" to Control(Tab.ADVANCED, R.string.settings_push_alert_sources),
            "KEY_MEETING_MODE" to Control(Tab.ADVANCED, R.string.settings_meeting_mode_toggle),
            "KEY_MEETING_MODE_APPS" to Control(Tab.ADVANCED, R.string.settings_meeting_mode_apps),
            "KEY_AGGRESSIVE_MODE" to Control(Tab.ADVANCED, R.string.settings_aggressive_blocking),
            "KEY_AUTO_CLEANUP" to Control(Tab.ADVANCED, R.string.settings_auto_cleanup),
            "KEY_CLEANUP_DAYS" to Control(Tab.ADVANCED, R.string.settings_keep_for),
            "KEY_EXTERNAL_BLOCKLIST_SUBSCRIPTIONS" to Control(Tab.ADVANCED, R.string.settings_external_blocklist_url),
            "KEY_FEED_MIRROR_URL" to Control(Tab.ADVANCED, R.string.settings_feed_mirror_url),
        )

    private val notOnSettings: Map<String, String> =
        mapOf(
            "KEY_APP_UPDATE_CHECKS" to "More, next to the update check it turns on",
            "KEY_EXPECTING_CALL_UNTIL" to "Home and the Quick Settings tile open and end the window",
            "KEY_URL_STRIP_QUERY" to
                "no control since remote link checks became domain-only; it only decides whether a logged link keeps its query",
            "KEY_ABSTRACT_API_KEY" to "a retired API key the app deletes on start",
            "KEY_ONBOARDING_DONE" to "this install's setup progress",
            "KEY_THEME_DEFAULT_SETTLED" to "the theme migration's record",
            "KEY_EVIDENCE_EXPIRY_RULE_APPLIED" to "a one-time fix to this phone's copy of the database",
            "KEY_COMMUNITY_REPORT_LEDGER" to "this phone's report outbox",
            "KEY_DISMISSED_RULE_CONFLICTS" to "notices this phone already showed",
            "KEY_PROTECTION_ROLE_EVER_HELD" to "this install's role history",
            "KEY_PROTECTION_ROLE_LOSS_NOTICE_SHOWN" to "a notice this phone already showed",
            "KEY_CNAP_SCREENED_WITH" to "a counter of this phone's calls",
            "KEY_CNAP_SCREENED_WITHOUT" to "a counter of this phone's calls",
            "KEY_SMS_CAPABILITY_STATE" to "a measurement of this phone",
            "KEY_SMS_CAPABILITY_API" to "a measurement of this phone",
            "KEY_SMS_CAPABILITY_OBSERVED_AT" to "a measurement of this phone",
            "KEY_SMS_CAPABILITY_LATENCY" to "a measurement of this phone",
            "KEY_NOTIFICATION_CAPABILITY_STATE" to "a measurement of this phone",
            "KEY_NOTIFICATION_CAPABILITY_API" to "a measurement of this phone",
            "KEY_NOTIFICATION_CAPABILITY_OBSERVED_AT" to "a measurement of this phone",
            "KEY_NOTIFICATION_CAPABILITY_LATENCY" to "a measurement of this phone",
            "KEY_APP_UPDATE_STATUS" to "the last update check's result",
            "KEY_APP_UPDATE_TAG" to "the last update check's result",
            "KEY_APP_UPDATE_RELEASE_URL" to "the last update check's result",
            "KEY_APP_UPDATE_CHECKSUM_URL" to "the last update check's result",
            "KEY_APP_UPDATE_CHECKED_AT" to "the last update check's result",
            "KEY_RELEASE_NOTICE_CODE" to "the last release notice, shown on Home",
            "KEY_RELEASE_NOTICE_NAME" to "the last release notice, shown on Home",
            "KEY_RELEASE_NOTICE_URL" to "the last release notice, shown on Home",
            "KEY_RELEASE_NOTICE_SHA256" to "the last release notice, shown on Home",
            "KEY_RELEASE_NOTICE_DISMISSED" to "the last release notice, shown on Home",
            "KEY_LIST_CATALOG" to "the downloaded list catalog, which External blocklists shows as rows, not a setting",
            "KEY_LAST_SYNC" to "sync state",
            "KEY_LAST_SYNC_SOURCE" to "sync state",
            "KEY_LAST_SHA" to "sync state",
            "KEY_LAST_SHARD_HASHES" to "sync state",
            "KEY_LAST_MANIFEST_DIGEST" to "sync state",
            "KEY_DB_VERSION" to "sync state",
            "KEY_DB_UPDATED" to "sync state",
            "KEY_FEED_TRUST_FAILED_AT" to "sync state",
            "KEY_FEED_TRUST_FAILED_VERSION" to "sync state",
            "KEY_FEED_TRUST_NOTICE_VERSION" to "sync state",
            "KEY_HOT_DATA_LAST_GOOD" to "sync state",
            "KEY_HOT_DATA_UNAVAILABLE" to "sync state",
            "KEY_HOT_DATA_UNREACHABLE" to "sync state",
            "KEY_HOT_DATA_CLEARED" to "sync state",
            "KEY_HOT_DATA_REFUSED" to "sync state",
            "KEY_HOT_DATA_GENERATED_AT" to "sync state",
            "KEY_HOT_DATA_DIGESTS" to "sync state",
            "KEY_TRENDING_NUMBERS" to "sync state",
            "KEY_TRENDING_APPLIED_AT" to "sync state",
        )

    /** Switches whose sub-controls only show while they're on. */
    private val revealing =
        listOf(
            SpamRepository.KEY_CONTACT_WHITELIST,
            SpamRepository.KEY_ANSWER_HANG_UP,
            SpamRepository.KEY_TIME_BLOCK,
            SpamRepository.KEY_ANSWERED_CALLER_TRUST,
            SpamRepository.KEY_EMERGENCY_CALLBACK_GRACE,
            SpamRepository.KEY_FREQ_ESCALATION,
            SpamRepository.KEY_RCS_FILTER,
            SpamRepository.KEY_PUSH_ALERT,
            SpamRepository.KEY_MEETING_MODE,
            SpamRepository.KEY_AUTO_CLEANUP,
        )

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var fixture: IsolatedRepositoryFixture
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        fixture = IsolatedRepositoryFixture(context)
        runBlocking { fixture.settingsStore.edit { prefs -> revealing.forEach { prefs[it] = true } } }
        val adapter = SpamRepositoryAdapter(fixture.repository)
        viewModel =
            MainViewModel(
                appContext = context,
                repo = fixture.repository,
                syncDatabase = SyncDatabaseUseCase(adapter),
                manageBlocklist = ManageBlocklistUseCase(adapter),
                exportLogs = ExportLogsUseCase(context),
            )
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `every key is either rendered on Settings or kept off it with a reason`() {
        val declared = declaredKeys()
        val both = rendered.keys intersect notOnSettings.keys
        assertTrue("classified twice: $both", both.isEmpty())
        assertEquals("unclassified keys", emptySet<String>(), declared - rendered.keys - notOnSettings.keys)
        assertEquals("keys that no longer exist", emptySet<String>(), (rendered.keys + notOnSettings.keys) - declared)
    }

    @Test
    fun `every setting's control shows on its tab`() {
        composeRule.setContent { CallShieldTheme { SettingsScreen(viewModel) } }

        assertShown(Tab.BASIC)
        composeRule.onNodeWithTag(SETTINGS_ADVANCED_TAB_TAG).performClick()
        assertShown(Tab.ADVANCED)
    }

    @Test
    fun `Basic shows Blocking, Safety, Notifications and Appearance and nothing else`() {
        composeRule.setContent { CallShieldTheme { SettingsScreen(viewModel) } }

        // Card titles are headings. The protection level header sits above the
        // tabs, and the access card shows on Basic only while setup is missing.
        val aboveTheCards = setOf(R.string.settings_protection_level, R.string.settings_permissions_access).map(::heading)
        val cards =
            composeRule
                .onAllNodes(isHeading(), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .sortedBy { it.positionInRoot.y }
                .map { node -> node.config[SemanticsProperties.Text].joinToString().uppercase() }
                .filterNot { it in aboveTheCards }
        val basic =
            listOf(R.string.settings_blocking, R.string.settings_safety, R.string.settings_notifications, R.string.settings_appearance)
        assertEquals(basic.map(::heading), cards)
    }

    @Test
    fun `what happens to a blocked call is one card on Advanced, ahead of the detection engines`() {
        composeRule.setContent { CallShieldTheme { SettingsScreen(viewModel) } }
        val moved =
            listOf(
                R.string.settings_silent_voicemail,
                R.string.settings_automute_low_confidence,
                R.string.settings_answer_hang_up,
                R.string.settings_category_actions,
            )
        moved.forEach { label -> assertEquals(context.getString(label), 0, count(label)) }

        composeRule.onNodeWithTag(SETTINGS_ADVANCED_TAB_TAG).performClick()
        val card = top(R.string.settings_when_blocked)
        val nextCard = top(R.string.settings_quiet_hours)
        moved.forEach { label ->
            val y = top(label)
            assertTrue("${context.getString(label)} sits outside its card", y > card && y < nextCard)
        }
        assertTrue("detection comes after", top(R.string.settings_detection_engines) > nextCard)
    }

    @Test
    fun `the region and caller name sheet edits each of its settings`() {
        composeRule.setContent { CallShieldTheme { SettingsScreen(viewModel) } }
        composeRule
            .onAllNodes(hasText(context.getString(R.string.settings_region_cnap_rules), ignoreCase = true), useUnmergedTree = true)
            .onFirst()
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        val sheetFields =
            mapOf(
                "KEY_REGION_BLOCK" to R.string.region_rules_block_outside,
                "KEY_ALLOWED_REGIONS" to R.string.region_rules_allowed_regions,
                "KEY_CNAP_TRUST_PATTERNS" to R.string.cnap_trust_patterns,
                "KEY_CNAP_BLOCK_PATTERNS" to R.string.cnap_block_patterns,
            )
        assertEquals(rendered.filterValues { it.label == R.string.settings_region_cnap_rules }.keys, sheetFields.keys)
        val missing = sheetFields.filterValues { count(it) == 0 }.keys
        assertEquals("sheet fields not rendered", emptySet<String>(), missing)
    }

    private fun assertShown(tab: Tab) {
        val missing = rendered.filterValues { it.tab == tab && count(it.label) == 0 }.keys
        assertEquals("$tab controls not rendered", emptySet<String>(), missing)
    }

    private fun count(
        @StringRes label: Int,
    ): Int =
        composeRule
            .onAllNodes(hasText(context.getString(label), ignoreCase = true), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size

    private fun heading(
        @StringRes title: Int,
    ): String = context.getString(title).uppercase()

    private fun top(
        @StringRes label: Int,
    ): Float {
        val nodes =
            composeRule
                .onAllNodes(hasText(context.getString(label), ignoreCase = true), useUnmergedTree = true)
                .fetchSemanticsNodes()
        assertEquals("${context.getString(label)} shown once", 1, nodes.size)
        return nodes.single().positionInRoot.y
    }

    /** SpamRepository's DataStore keys by their Kotlin names. */
    private fun declaredKeys(): Set<String> =
        SpamRepository::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && Preferences.Key::class.java.isAssignableFrom(it.type) }
            .map { it.name }
            .toSet()
}
