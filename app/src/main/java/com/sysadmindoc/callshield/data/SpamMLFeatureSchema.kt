package com.sysadmindoc.callshield.data

/** Versioned feature contract shared by the on-device scorer and Python trainer. */
internal const val SPAM_ML_FEATURE_SCHEMA_VERSION = 1

internal val SPAM_ML_FEATURE_NAMES =
    listOf(
        "toll_free",
        "high_spam_npa",
        "voip_range",
        "repeated_digits_ratio",
        "sequential_asc_ratio",
        "all_same_digit",
        "nxx_555",
        "last4_zero",
        "invalid_nxx",
        "subscriber_all_same",
        "alternating_pattern",
        "sequential_desc_ratio",
        "nxx_below_200",
        "low_digit_entropy",
        "subscriber_sequential",
        "time_of_day_sin",
        "time_of_day_cos",
        "geographic_distance",
        "short_number",
        "plus_one_prefix",
    )

private val featureSchemaVersionRegex = Regex("""\"feature_schema_version\"\s*:\s*(\d+)""")
private val featureNamesRegex = Regex("""\"feature_names\"\s*:\s*\[([^]]*)]""")
private val quotedNameRegex = Regex("""\"([^\"]*)\"""")

/**
 * Validate an optional model schema declaration. Older v2/v3 payloads did not
 * carry schema metadata, so an entirely absent declaration remains compatible;
 * once either field is declared, both fields must match this exact contract.
 */
internal fun modelFeatureSchemaMatches(json: String): Boolean {
    val version =
        featureSchemaVersionRegex
            .find(json)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    val namesBody = featureNamesRegex.find(json)?.groupValues?.get(1)
    if (version == null && namesBody == null) return true
    if (version != SPAM_ML_FEATURE_SCHEMA_VERSION || namesBody == null) return false

    val names = quotedNameRegex.findAll(namesBody).map { it.groupValues[1] }.toList()
    return names == SPAM_ML_FEATURE_NAMES
}
