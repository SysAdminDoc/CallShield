package com.sysadmindoc.callshield.data.model

import androidx.room.TypeConverter
import com.sysadmindoc.callshield.domain.model.BlockReasonCode

/** Room's wire representation for the stable screening reason enum. */
class BlockReasonCodeConverters {
    @TypeConverter
    fun fromReasonCode(code: BlockReasonCode): String = code.wireValue

    @TypeConverter
    fun toReasonCode(value: String?): BlockReasonCode = BlockReasonCode.fromStored(value)
}
