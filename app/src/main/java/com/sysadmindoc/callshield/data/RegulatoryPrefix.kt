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
 * carries a national trunk 0, so Brazil's 0303 is +55 303. The national
 * form only means that country when the phone's home region is that
 * country: on a US phone a malformed caller ID shaped like 1600xxxxxx is
 * not an Indian bank.
 */
enum class RegulatoryPrefix(
    val key: Preferences.Key<Boolean>,
    val allows: Boolean,
    /** ISO region whose national numbering the national form belongs to. */
    val regionIso: String,
    val e164Prefix: String,
    val nationalPrefix: String,
    val nationalLength: Int,
    /** The range's name, shown with the decision as the range that matched. */
    val descriptionRes: Int,
    val titleRes: Int,
    val summaryRes: Int,
) {
    SPAIN_400(
        key = SpamRepository.KEY_REG_SPAIN_400,
        allows = false,
        regionIso = "ES",
        e164Prefix = "+34400",
        nationalPrefix = "400",
        nationalLength = 9,
        descriptionRes = R.string.reg_prefix_spain_400_match,
        titleRes = R.string.reg_prefix_spain_400,
        summaryRes = R.string.reg_prefix_spain_400_desc,
    ),
    INDIA_140(
        key = SpamRepository.KEY_REG_INDIA_140,
        allows = false,
        regionIso = "IN",
        e164Prefix = "+91140",
        nationalPrefix = "140",
        nationalLength = 10,
        descriptionRes = R.string.reg_prefix_india_140_match,
        titleRes = R.string.reg_prefix_india_140,
        summaryRes = R.string.reg_prefix_india_140_desc,
    ),
    BRAZIL_0303(
        key = SpamRepository.KEY_REG_BRAZIL_0303,
        allows = false,
        regionIso = "BR",
        e164Prefix = "+55303",
        nationalPrefix = "0303",
        nationalLength = 11,
        descriptionRes = R.string.reg_prefix_brazil_0303_match,
        titleRes = R.string.reg_prefix_brazil_0303,
        summaryRes = R.string.reg_prefix_brazil_0303_desc,
    ),
    INDIA_1600(
        key = SpamRepository.KEY_REG_INDIA_1600_ALLOW,
        allows = true,
        regionIso = "IN",
        e164Prefix = "+911600",
        nationalPrefix = "1600",
        nationalLength = 10,
        descriptionRes = R.string.reg_prefix_india_1600_allow_match,
        titleRes = R.string.reg_prefix_india_1600_allow,
        summaryRes = R.string.reg_prefix_india_1600_allow_desc,
    ),
    ;

    /** [homeRegionIso] is read only for a number in national form. */
    fun matches(
        number: String,
        homeRegionIso: () -> String?,
    ): Boolean =
        number.startsWith(e164Prefix) ||
            (
                !number.startsWith("+") &&
                    number.length == nationalLength &&
                    number.startsWith(nationalPrefix) &&
                    homeRegionIso() == regionIso
            )

    companion object {
        /** The enabled range of the given kind that [number] falls in, if any. */
        fun enabledMatch(
            prefs: Preferences,
            number: String,
            allows: Boolean,
            homeRegionIso: () -> String?,
        ): RegulatoryPrefix? =
            entries.firstOrNull {
                it.allows == allows && prefs[it.key] == true && it.matches(number, homeRegionIso)
            }
    }
}
