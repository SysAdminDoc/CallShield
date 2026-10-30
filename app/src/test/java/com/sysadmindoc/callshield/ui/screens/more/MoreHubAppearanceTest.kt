package com.sysadmindoc.callshield.ui.screens.more

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.BuildConfig
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.data.repository.SpamRepositoryAdapter
import com.sysadmindoc.callshield.domain.usecase.ExportLogsUseCase
import com.sysadmindoc.callshield.domain.usecase.ManageBlocklistUseCase
import com.sysadmindoc.callshield.domain.usecase.SyncDatabaseUseCase
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.theme.AppThemeMode
import com.sysadmindoc.callshield.ui.theme.CallShieldTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * More's Appearance row picks the theme in place. It used to open Settings at
 * the top, where the theme row sits below the fold. The snapshot metrics are
 * one TalkBack stop each.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MoreHubAppearanceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var fixture: IsolatedRepositoryFixture
    private lateinit var viewModel: MainViewModel
    private var settingsOpened = 0

    @Before
    fun setUp() {
        fixture = IsolatedRepositoryFixture(context)
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
        composeRule.setContent {
            CallShieldTheme {
                MoreHub(
                    viewModel = viewModel,
                    onStats = {},
                    onSettings = { settingsOpened++ },
                    onChangelog = {},
                    onTest = {},
                )
            }
        }
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `Appearance opens the theme picker and applies the pick`() {
        composeRule.onNodeWithText(context.getString(R.string.more_appearance)).performScrollTo().performClick()

        composeRule.onNodeWithText(context.getString(R.string.settings_theme_dialog_title)).assertIsDisplayed()
        composeRule.onNodeWithTag("settings_theme_option_${AppThemeMode.Light.storageValue}").performClick()

        composeRule.waitUntil(TIMEOUT_MS) {
            shadowOf(Looper.getMainLooper()).idle()
            viewModel.appTheme.value == AppThemeMode.Light
        }
        composeRule.onNodeWithText(context.getString(R.string.settings_theme_dialog_title)).assertDoesNotExist()
        assertEquals(0, settingsOpened)
    }

    @Test
    fun `the version metric reads its value and label together`() {
        val label = context.getString(R.string.more_snapshot_version)
        composeRule.onNode(hasText(label)).assertTextEquals("v${BuildConfig.VERSION_NAME}", label)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
