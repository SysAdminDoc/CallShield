package com.sysadmindoc.callshield.ui.screens.lookup

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.data.repository.SpamRepositoryAdapter
import com.sysadmindoc.callshield.domain.usecase.ExportLogsUseCase
import com.sysadmindoc.callshield.domain.usecase.ManageBlocklistUseCase
import com.sysadmindoc.callshield.domain.usecase.SyncDatabaseUseCase
import com.sysadmindoc.callshield.ui.MainViewModel
import com.sysadmindoc.callshield.ui.theme.CallShieldTheme
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * More's Report spam number opens Lookup on an empty number field with the
 * keyboard's focus. Lookup used to come back scrolled down to the last check,
 * with the field off screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LookupNumberFieldRequestTest {
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
        composeRule.setContent { CallShieldTheme { LookupScreen(viewModel) } }
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `the request focuses the number field and is used once`() {
        val field = composeRule.onNode(hasSetTextAction())
        field.assertIsNotFocused()

        viewModel.requestLookupNumberField()
        composeRule.waitForIdle()

        field.assertIsFocused()
        assertFalse(viewModel.lookupNumberFieldRequested.value)
    }

    @Test
    fun `the request clears a number typed earlier`() {
        composeRule.onNode(hasSetTextAction()).performTextInput("2125550177")
        composeRule.onNodeWithText("2125550177").assertExists()

        viewModel.requestLookupNumberField()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("2125550177").assertDoesNotExist()
    }
}
