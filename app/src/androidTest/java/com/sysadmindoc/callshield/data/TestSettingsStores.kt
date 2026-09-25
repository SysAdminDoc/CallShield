package com.sysadmindoc.callshield.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.sysadmindoc.callshield.data.checker.CheckerDependencies
import com.sysadmindoc.callshield.data.local.AppDatabase
import com.sysadmindoc.callshield.data.model.CampaignObservation
import com.sysadmindoc.callshield.data.remote.GitHubDataSource
import com.sysadmindoc.callshield.data.remote.SpamDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID

/**
 * Empty settings stores and detectors for one test. The app's own stores stay
 * on the device between runs, and state kept there, such as the feed version
 * and date the sync last accepted, makes a test depend on whatever ran before
 * it. The shared detectors are the app's too: Hilt attaches
 * `CampaignDetector.shared` to the installed app's database, so calls a test
 * screened landed in its campaign_observations and came back as a burst on
 * later runs.
 */
internal class TestSettingsStores(
    context: Context,
) : AutoCloseable {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val directory = File(context.cacheDir, "test-settings-${UUID.randomUUID()}").apply { mkdirs() }

    val settings: DataStore<Preferences> = store("settings")
    val privateSettings: DataStore<Preferences> = store("private")

    /** Hot ranges, spam domains and campaign observations of this test's own. */
    val checkerDependencies =
        CheckerDependencies(
            spamHeuristics = SpamHeuristics(),
            smsContentAnalyzer = SmsContentAnalyzer(),
            campaignDetector = CampaignDetector(),
        )

    /** A repository on [database] that keeps its settings in these stores and uses these detectors. */
    fun repository(
        context: Context,
        database: AppDatabase,
        remote: SpamDataSource = GitHubDataSource(),
        phoneIdentityCanonicalizer: PhoneIdentityCanonicalizer = PhoneIdentityCanonicalizer.fromContext(context.applicationContext),
    ) = SpamRepository(
        context = context,
        database = database,
        remote = remote,
        checkerDependencies = checkerDependencies,
        settingsDataStore = settings,
        privateSettingsDataStore = privateSettings,
        phoneIdentityCanonicalizer = phoneIdentityCanonicalizer,
    )

    private fun store(name: String) =
        PreferenceDataStoreFactory.create(
            corruptionHandler = replaceCorruptPreferences(),
            scope = scope,
            produceFile = { File(directory, "$name.preferences_pb") },
        )

    override fun close() {
        runBlocking { job.cancelAndJoin() }
        directory.deleteRecursively()
    }
}

/**
 * A campaign detector that reads the installed app's observations and records
 * none, for the tests that time the app's own cold path: the read is part of
 * what they time, and the calls they screen must not land in the app's table.
 */
internal fun readOnlyCampaignDetector(context: Context): CampaignDetector {
    val appStore = RoomCampaignObservationStore(AppDatabase.getInstance(context).spamDao())
    return CampaignDetector().apply {
        attachObservationStore(
            object : CampaignObservationStore {
                override suspend fun record(observation: CampaignObservation) = Unit

                override suspend fun load(
                    prefix: String,
                    since: Long,
                ): List<CampaignObservation> = appStore.load(prefix, since)

                override suspend fun prune(
                    before: Long,
                    maxRows: Int,
                ) = Unit
            },
        )
    }
}
