package com.sysadmindoc.callshield.ui

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.data.IsolatedRepositoryFixture
import com.sysadmindoc.callshield.data.repository.SpamRepositoryAdapter
import com.sysadmindoc.callshield.domain.usecase.ExportLogsUseCase
import com.sysadmindoc.callshield.domain.usecase.ManageBlocklistUseCase
import com.sysadmindoc.callshield.domain.usecase.SyncDatabaseUseCase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SwipeBlockFailureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val number = "+12125550142"
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
        // Let the view model's startup reads finish while the database still works.
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `a swipe block reports its undo only once the block is written`() =
        runBlocking {
            val undo = viewModel.blockNumberUndoable(number, "spam", "swiped").getOrThrow()

            assertNotNull(undo)
            assertEquals(true, fixture.dao.findByNumber(number)?.isUserBlocked)
        }

    @Test
    fun `a swipe block that can't be written is a failure, not a block`() =
        runBlocking {
            fixture.breakDatabase()

            val result = viewModel.blockNumberUndoable(number, "spam", "swiped")

            assertTrue(result.isFailure)
        }

    @Test
    fun `an undo that can't be written says so instead of crashing`() {
        val undo = runBlocking { requireNotNull(viewModel.blockNumberUndoable(number, "spam", "swiped").getOrThrow()) }
        fixture.breakDatabase()

        viewModel.undoBlock(undo)
        // The undo's write runs off the main thread; wait for its toast.
        val deadline = System.currentTimeMillis() + 5_000
        while (ShadowToast.getTextOfLatestToast() == null && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }

        assertEquals(context.getString(R.string.blocked_log_undo_failed), ShadowToast.getTextOfLatestToast())
    }
}
