package com.sysadmindoc.callshield.data

import androidx.datastore.preferences.core.Preferences
import com.sysadmindoc.callshield.R

/**
 * A number series a telecom regulator set aside, each behind its own opt-in
 * setting. A range either blocks (sales-call ranges) or rings through (a
 * protected series).
 *
 * Matched in E.164 form and in the national form a caller ID keeps when
 * Android can't put it in E.164, which happens for a range newer than the
 * phone's numbering data (Spain's 400 range dates from 2026). E.164 never
 * carries a national trunk 0, so Brazil's 0303 is +55 303.
 */
enum class RegulatoryPrefix(
    val key: Preferences.Key<Boolean>,
    val allows: Boolean,
    val e164Prefix: String,
    val nationalPrefix: String,
    val nationalLength: Int,
    /** Stored with the decision and shown as the range that matched. */
    val description: String,
    val titleRes: Int,
    val summaryRes: Int,
) {
    SPAIN_400(
        key = SpamRepository.KEY_REG_SPAIN_400,
        allows = false,
        e164Prefix = "+34400",
        nationalPrefix = "400",
        nationalLength = 9,
        description = "Spain 400 commercial call range",
        titleRes = R.string.reg_prefix_spain_400,
        summaryRes = R.string.reg_prefix_spain_400_desc,
    ),
    INDIA_140(
        key = SpamRepository.KEY_REG_INDIA_140,
        allows = false,
        e164Prefix = "+91140",
        nationalPrefix = "140",
        nationalLength = 10,
        description = "India 140 promotional call series",
        titleRes = R.string.reg_prefix_india_140,
        summaryRes = R.string.reg_prefix_india_140_desc,
    ),
    BRAZIL_0303(
        key = SpamRepository.KEY_REG_BRAZIL_0303,
        allows = false,
        e164Prefix = "+55303",
        nationalPrefix = "0303",
        nationalLength = 11,
        description = "Brazil 0303 telemarketing code",
        titleRes = R.string.reg_prefix_brazil_0303,
        summaryRes = R.string.reg_prefix_brazil_0303_desc,
    ),
    INDIA_1600(
        key = SpamRepository.KEY_REG_INDIA_1600_ALLOW,
        allows = true,
        e164Prefix = "+911600",
        nationalPrefix = "1600",
        nationalLength = 10,
        description = "India 1600 series for banks, insurers and government offices",
        titleRes = R.string.reg_prefix_india_1600_allow,
        summaryRes = R.string.reg_prefix_india_1600_allow_desc,
    ),
    ;

    fun matches(number: String): Boolean =
        number.startsWith(e164Prefix) ||
            (!number.startsWith("+") && number.length == nationalLength && number.startsWith(nationalPrefix))

    companion object {
        /** The enabled range of the given kind that [number] falls in, if any. */
        fun enabledMatch(
            prefs: Preferences,
            number: String,
            allows: Boolean,
        ): RegulatoryPrefix? = entries.firstOrNull { it.allows == allows && prefs[it.key] == true && it.matches(number) }
    }
}
