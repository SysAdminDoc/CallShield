package com.sysadmindoc.callshield.data

import com.sysadmindoc.callshield.data.local.SpamDao
import com.sysadmindoc.callshield.data.model.CampaignObservation

/** Small persistence seam so campaign logic remains unit-testable without Room. */
interface CampaignObservationStore {
    suspend fun record(observation: CampaignObservation)

    suspend fun load(
        prefix: String,
        since: Long,
    ): List<CampaignObservation>

    suspend fun prune(
        before: Long,
        maxRows: Int,
    )
}

class RoomCampaignObservationStore(
    private val dao: SpamDao,
) : CampaignObservationStore {
    override suspend fun record(observation: CampaignObservation) {
        dao.insertCampaignObservation(observation)
    }

    override suspend fun load(
        prefix: String,
        since: Long,
    ): List<CampaignObservation> = dao.getCampaignObservations(prefix, since)

    override suspend fun prune(
        before: Long,
        maxRows: Int,
    ) {
        dao.deleteCampaignObservationsBefore(before)
        dao.trimCampaignObservations(maxRows)
    }
}
