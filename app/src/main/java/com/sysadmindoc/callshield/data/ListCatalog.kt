package com.sysadmindoc.callshield.data

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.sysadmindoc.callshield.data.model.ListCatalogEntry
import com.sysadmindoc.callshield.data.model.ListCatalogEntryJson
import com.sysadmindoc.callshield.data.model.ListCatalogJson
import com.sysadmindoc.callshield.data.model.ListNumberPlan
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/** The lists in [entries], from a catalog at [revision]; a higher revision is newer. */
internal data class ParsedListCatalog(
    val revision: Int,
    val entries: List<ListCatalogEntry>,
)

/**
 * Reads the recommended-list catalog: the copy bundled in the APK and the
 * signed copy each sync downloads. An entry the app can't use (a link that
 * isn't HTTPS, a format it can't read, a list that would be on by default) is
 * left out and the rest still show. A file with no usable entry is refused.
 */
@Suppress("MagicNumber")
internal object ListCatalog {
    const val SCHEMA_VERSION = 1

    private val formats = setOf("json", "csv", "txt")
    private val idPattern = Regex("[a-z0-9][a-z0-9-]{0,39}")
    private val countryPattern = Regex("[A-Z]{2}")
    private val callingCodePattern = Regex("[1-9][0-9]{0,2}")
    private val trunkPrefixPattern = Regex("[0-9]{0,2}")
    private const val MAX_NAME_LENGTH = 60
    private const val MAX_LICENSE_LENGTH = 40
    private const val MIN_NATIONAL_LENGTH = 4
    private const val MAX_NATIONAL_LENGTH = 14

    private val adapter =
        Moshi
            .Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
            .adapter(ListCatalogJson::class.java)

    /** Throws [IllegalArgumentException] when [body] isn't a catalog with at least one usable list. */
    fun parse(body: String): ParsedListCatalog {
        val read =
            try {
                adapter.fromJson(body)
            } catch (e: IOException) {
                throw IllegalArgumentException("list catalog isn't JSON: ${e.message}", e)
            } catch (e: JsonDataException) {
                throw IllegalArgumentException("list catalog has the wrong shape: ${e.message}", e)
            }
        val json = requireNotNull(read) { "list catalog is empty" }
        require(json.version == SCHEMA_VERSION) { "list catalog version ${json.version} isn't $SCHEMA_VERSION" }
        val revision = json.revision ?: 0
        require(revision > 0) { "list catalog has no revision" }
        // The first of two entries with one id or one link wins.
        val ids = mutableSetOf<String>()
        val urls = mutableSetOf<String>()
        val entries =
            json.lists
                .orEmpty()
                .mapNotNull(::entryOrNull)
                .filter { it.id !in ids && urls.add(it.url) && ids.add(it.id) }
        require(entries.isNotEmpty()) { "list catalog has no usable list" }
        return ParsedListCatalog(revision, entries)
    }

    /** Whether [id] is one a catalog list could have. */
    fun isUsableId(id: String): Boolean = idPattern.matches(id)

    /** [plan] when a catalog could have declared it, else null: a backup can carry anything. */
    fun usablePlanOrNull(plan: ListNumberPlan): ListNumberPlan? =
        plan
            .takeIf {
                countryPattern.matches(it.country) &&
                    callingCodePattern.matches(it.callingCode) &&
                    trunkPrefixPattern.matches(it.trunkPrefix) &&
                    it.nationalLengths.isNotEmpty() &&
                    it.nationalLengths.all { length -> length in MIN_NATIONAL_LENGTH..MAX_NATIONAL_LENGTH }
            }?.copy(nationalLengths = plan.nationalLengths.distinct())

    private fun entryOrNull(json: ListCatalogEntryJson): ListCatalogEntry? {
        val id = json.id.orEmpty()
        val name = json.name.orEmpty().trim()
        val country = json.country.orEmpty()
        val callingCode = json.callingCode.orEmpty()
        val trunkPrefix = json.trunkPrefix.orEmpty()
        val lengths = json.nationalLengths.orEmpty()
        val license = json.license.orEmpty().trim()
        val format = json.format.orEmpty()
        val usable =
            idPattern.matches(id) &&
                name.isNotEmpty() &&
                name.length <= MAX_NAME_LENGTH &&
                countryPattern.matches(country) &&
                callingCodePattern.matches(callingCode) &&
                trunkPrefixPattern.matches(trunkPrefix) &&
                lengths.isNotEmpty() &&
                lengths.all { it in MIN_NATIONAL_LENGTH..MAX_NATIONAL_LENGTH } &&
                license.isNotEmpty() &&
                license.length <= MAX_LICENSE_LENGTH &&
                format in formats &&
                // Every catalog list starts off: nothing is downloaded from a
                // third party until someone adds it.
                json.enabledByDefault == false
        if (!usable) return null
        val url = runCatching { ExternalBlocklistParser.validateHttpUrl(json.url.orEmpty()) }.getOrNull() ?: return null
        val homepage = httpsOrNull(json.homepage) ?: return null
        val licenseUrl = httpsOrNull(json.licenseUrl) ?: return null
        return ListCatalogEntry(
            id = id,
            name = name,
            url = url,
            homepage = homepage,
            license = license,
            licenseUrl = licenseUrl,
            format = format,
            numberPlan = ListNumberPlan(country, callingCode, trunkPrefix, lengths.distinct()),
        )
    }

    private fun httpsOrNull(raw: String?): String? =
        raw
            ?.trim()
            ?.toHttpUrlOrNull()
            ?.takeIf { it.scheme == "https" && it.username.isEmpty() && it.password.isEmpty() }
            ?.toString()
}
