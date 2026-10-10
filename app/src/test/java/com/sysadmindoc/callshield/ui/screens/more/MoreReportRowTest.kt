package com.sysadmindoc.callshield.ui.screens.more

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.data.repository.SpamRepositoryAdapter
import com.sysadmindoc.callshield.domain.usecase.ExportLogsUseCase
import com.sysadmindoc.callshield.domain.usecase.ManageBlocklistUseCase
import com.sysadmindoc.callshield.domain.usecase.SyncDatabaseUseCase
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.theme.CallShieldTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * More's "Report spam number" opens Lookup, where Report sends the anonymous
 * report that counts toward blocking. It used to open the GitHub form, which
 * files a public issue that nothing reads into the database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MoreReportRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var fixture: IsolatedRepositoryFixture
    private lateinit var viewModel: MainViewModel

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
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `Report spam number opens Lookup, not the GitHub form`() {
        var lookupsOpened = 0
        composeRule.setContent {
            CallShieldTheme {
                MoreHub(
                    viewModel = viewModel,
                    onStats = {},
                    onSettings = {},
                    onChangelog = {},
                    onTest = {},
                    onReportNumber = { lookupsOpened++ },
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.more_report_spam_number)).performScrollTo().performClick()

        assertEquals(1, lookupsOpened)
        assertNull(shadowOf(context as Application).nextStartedActivity)
    }
}
