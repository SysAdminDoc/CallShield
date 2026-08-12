package com.sysadmindoc.callshield.domain.usecase

import android.content.Context
import com.sysadmindoc.callshield.data.BlockedCallBatchReader
import com.sysadmindoc.callshield.data.BlocklistExporter
import com.sysadmindoc.callshield.data.LogExporter
import com.sysadmindoc.callshield.data.model.BlockedCall
import com.sysadmindoc.callshield.data.model.SpamNumber
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class ExportLogsUseCase
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        suspend fun exportBlockedLog(
            calls: List<BlockedCall>,
            includeRawSmsBodies: Boolean = false,
        ) {
            LogExporter.exportAsCsv(context, calls, includeRawSmsBodies)
        }

        suspend fun exportBlockedLog(
            readBatch: BlockedCallBatchReader,
            includeRawSmsBodies: Boolean = false,
        ): Boolean = LogExporter.exportAsCsv(context, readBatch, includeRawSmsBodies)

        suspend fun exportRedressLog(calls: List<BlockedCall>) {
            LogExporter.exportRedressAsCsv(context, calls)
        }

        suspend fun exportRedressLog(readBatch: BlockedCallBatchReader): Boolean = LogExporter.exportRedressAsCsv(context, readBatch)

        suspend fun exportBlocklist(numbers: List<SpamNumber>) {
            BlocklistExporter.exportAndShare(context, numbers)
        }
    }
