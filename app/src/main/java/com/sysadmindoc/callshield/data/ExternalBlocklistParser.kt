package com.sysadmindoc.callshield.data

import com.squareup.moshi.JsonReader
import com.sysadmindoc.callshield.data.model.ExternalBlocklistSubscription
import com.sysadmindoc.callshield.data.model.SpamNumber
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.Buffer
import java.security.MessageDigest

internal enum class ExternalBlocklistFailureReason {
    UNSUPPORTED_URL,
    OVERSIZE,
    ROW_LIMIT,
    INVALID_FORMAT,
    EMPTY,
}

internal class ExternalBlocklistValidationException(
    val reason: ExternalBlocklistFailureReason,
    message: String,
) : IllegalArgumentException(message)

internal data class ParsedExternalBlocklist(
    val id: String,
    val label: String,
    val url: String,
    val source: String,
    val format: String,
    val numbers: List<SpamNumber>,
    val skippedRows: Int,
)

private data class ExternalBlocklistRow(
    val number: String,
    val type: String = "subscription",
    val description: String = "",
)

@Suppress(
    "MagicNumber",
    "ReturnCount",
    "SwallowedException",
    "ThrowsCount",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
)
internal object ExternalBlocklistParser {
    const val MAX_SUBSCRIPTION_BYTES = 1L * 1024L * 1024L
    const val MAX_SUBSCRIPTION_ROWS = 20_000

    private val numberFields = setOf("number", "phone", "phone_number", "phoneNumber", "msisdn")
    private val typeFields = setOf("type", "category", "label")
    private val descriptionFields = setOf("description", "comment", "name", "reason")
    private val arrayFields = setOf("numbers", "blocklist", "entries", "data")

    fun parse(
        rawUrl: String,
        rawLabel: String,
        body: String,
        normalizeNumber: (String) -> String,
    ): ParsedExternalBlocklist {
        val url = validateHttpUrl(rawUrl)
        requireBodyWithinCap(body)
        val format = detectFormat(url, body)
        val rows =
            when (format) {
                "json" -> parseJsonRows(body)
                "csv" -> parseCsvRows(body)
                else -> parseTextRows(body)
            }
        val id = idForUrl(url)
        val source = ExternalBlocklistSubscription.sourceFor(id)
        val label = rawLabel.trim().ifBlank { url.toHttpUrlOrNull()?.host ?: "External blocklist" }
        val dedupeKeys = linkedSetOf<String>()
        var skippedRows = 0
        val numbers =
            rows.mapNotNull { row ->
                val normalizedNumber = normalizeNumber(row.number)
                val key = canonicalNumberKey(normalizedNumber)
                if (normalizedNumber.isBlank() || key.isBlank() || !dedupeKeys.add(key)) {
                    skippedRows++
                    null
                } else {
                    SpamNumber(
                        number = normalizedNumber,
                        type = row.type.trim().ifBlank { "subscription" },
                        reports = 1,
                        description = row.description.trim().ifBlank { label },
                        source = source,
                    )
                }
            }
        if (numbers.isEmpty()) {
            throw ExternalBlocklistValidationException(
                ExternalBlocklistFailureReason.EMPTY,
                "External blocklist did not contain any valid phone numbers",
            )
        }
        return ParsedExternalBlocklist(
            id = id,
            label = label,
            url = url,
            source = source,
            format = format,
            numbers = numbers,
            skippedRows = skippedRows,
        )
    }

    fun validateHttpUrl(rawUrl: String): String {
        val url =
            rawUrl.trim().toHttpUrlOrNull()
                ?: throw ExternalBlocklistValidationException(
                    ExternalBlocklistFailureReason.UNSUPPORTED_URL,
                    "External blocklist URL must be a valid HTTPS URL",
                )
        if (url.scheme != "https") {
            throw ExternalBlocklistValidationException(
                ExternalBlocklistFailureReason.UNSUPPORTED_URL,
                "External blocklist URL must use HTTPS",
            )
        }
        if (url.username.isNotBlank() || url.password.isNotBlank()) {
            throw ExternalBlocklistValidationException(
                ExternalBlocklistFailureReason.UNSUPPORTED_URL,
                "External blocklist URL must not include embedded credentials",
            )
        }
        return url
            .newBuilder()
            .fragment(null)
            .build()
            .toString()
    }

    fun idForUrl(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    fun canonicalNumberKey(number: String): String {
        val digits = number.filter { it in '0'..'9' }
        return if (digits.length == 11 && digits.startsWith("1")) digits.drop(1) else digits
    }

    private fun requireBodyWithinCap(body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8).size.toLong()
        if (bytes > MAX_SUBSCRIPTION_BYTES) {
            throw ExternalBlocklistValidationException(
                ExternalBlocklistFailureReason.OVERSIZE,
                "External blocklist exceeded ${MAX_SUBSCRIPTION_BYTES} byte cap",
            )
        }
    }

    private fun detectFormat(
        url: String,
        body: String,
    ): String {
        val trimmed = body.trimStart()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return "json"
        val path =
            url
                .toHttpUrlOrNull()
                ?.encodedPath
                .orEmpty()
                .lowercase()
        if (path.endsWith(".csv")) return "csv"
        val firstDataLine =
            body
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("//") }
                .orEmpty()
        return if (firstDataLine.contains(",")) "csv" else "txt"
    }

    private fun parseTextRows(body: String): List<ExternalBlocklistRow> {
        val rows = mutableListOf<ExternalBlocklistRow>()
        body.lineSequence().forEach { line ->
            enforceRowCap(rows.size + 1)
            val trimmed = line.trim()
            if (trimmed.isNotBlank() && !trimmed.startsWith("#") && !trimmed.startsWith("//")) {
                rows += ExternalBlocklistRow(trimmed)
            }
        }
        return rows
    }

    private fun parseCsvRows(body: String): List<ExternalBlocklistRow> {
        val lines =
            body
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("//") }
                .toList()
        if (lines.isEmpty()) return emptyList()

        val firstCells = parseCsvLine(lines.first())
        val headerLookup = firstCells.map { it.trim().lowercase() }
        val hasHeader = headerLookup.any { it in numberFields.map(String::lowercase) }
        val numberIndex = if (hasHeader) headerLookup.indexOfFirst { it in numberFields.map(String::lowercase) } else 0
        val typeIndex = if (hasHeader) headerLookup.indexOfFirst { it in typeFields } else -1
        val descriptionIndex = if (hasHeader) headerLookup.indexOfFirst { it in descriptionFields } else -1
        val dataLines = if (hasHeader) lines.drop(1) else lines

        val rows = mutableListOf<ExternalBlocklistRow>()
        dataLines.forEach { line ->
            enforceRowCap(rows.size + 1)
            val cells = parseCsvLine(line)
            val number = cells.getOrNull(numberIndex).orEmpty()
            rows +=
                ExternalBlocklistRow(
                    number = number,
                    type = cells.getOrNull(typeIndex).orEmpty(),
                    description = cells.getOrNull(descriptionIndex).orEmpty(),
                )
        }
        return rows
    }

    private fun parseCsvLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && inQuotes && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }

                char == '"' -> {
                    inQuotes = !inQuotes
                }

                char == ',' && !inQuotes -> {
                    cells += current.toString().trim()
                    current.clear()
                }

                else -> {
                    current.append(char)
                }
            }
            index++
        }
        cells += current.toString().trim()
        return cells
    }

    private fun parseJsonRows(body: String): List<ExternalBlocklistRow> {
        val reader = JsonReader.of(Buffer().writeUtf8(body))
        return try {
            when (reader.peek()) {
                JsonReader.Token.BEGIN_ARRAY -> readJsonArray(reader, 0)
                JsonReader.Token.BEGIN_OBJECT -> readJsonRootObject(reader)
                else -> invalidJson()
            }
        } catch (e: ExternalBlocklistValidationException) {
            throw e
        } catch (e: Exception) {
            throw ExternalBlocklistValidationException(
                ExternalBlocklistFailureReason.INVALID_FORMAT,
                "External blocklist JSON is invalid: ${e.message.orEmpty()}",
            )
        }
    }

    private fun readJsonRootObject(reader: JsonReader): List<ExternalBlocklistRow> {
        val rows = mutableListOf<ExternalBlocklistRow>()
        var singleRow: ExternalBlocklistRow? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                in arrayFields -> {
                    rows += readJsonArray(reader, rows.size)
                }

                in numberFields -> {
                    val number = reader.nextScalarString()
                    singleRow = ExternalBlocklistRow(number = number)
                }

                else -> {
                    reader.skipValue()
                }
            }
        }
        reader.endObject()
        if (rows.isNotEmpty()) return rows
        return singleRow?.let(::listOf) ?: invalidJson()
    }

    private fun readJsonArray(
        reader: JsonReader,
        existingRows: Int,
    ): List<ExternalBlocklistRow> {
        val rows = mutableListOf<ExternalBlocklistRow>()
        reader.beginArray()
        while (reader.hasNext()) {
            enforceRowCap(existingRows + rows.size + 1)
            when (reader.peek()) {
                JsonReader.Token.STRING,
                JsonReader.Token.NUMBER,
                -> {
                    rows += ExternalBlocklistRow(reader.nextString())
                }

                JsonReader.Token.BEGIN_OBJECT -> {
                    rows += readJsonEntryObject(reader)
                }

                else -> {
                    reader.skipValue()
                    rows += ExternalBlocklistRow("")
                }
            }
        }
        reader.endArray()
        return rows
    }

    private fun readJsonEntryObject(reader: JsonReader): ExternalBlocklistRow {
        var number = ""
        var type = ""
        var description = ""
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                in numberFields -> number = reader.nextScalarString()
                in typeFields -> type = reader.nextScalarString()
                in descriptionFields -> description = reader.nextScalarString()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return ExternalBlocklistRow(number, type, description)
    }

    private fun JsonReader.nextScalarString(): String =
        when (peek()) {
            JsonReader.Token.STRING,
            JsonReader.Token.NUMBER,
            -> {
                nextString()
            }

            JsonReader.Token.BOOLEAN -> {
                nextBoolean().toString()
            }

            JsonReader.Token.NULL -> {
                nextNull<Unit>()
                ""
            }

            else -> {
                skipValue()
                ""
            }
        }

    private fun enforceRowCap(rows: Int) {
        if (rows > MAX_SUBSCRIPTION_ROWS) {
            throw ExternalBlocklistValidationException(
                ExternalBlocklistFailureReason.ROW_LIMIT,
                "External blocklist row count exceeded cap $MAX_SUBSCRIPTION_ROWS",
            )
        }
    }

    private fun invalidJson(): Nothing =
        throw ExternalBlocklistValidationException(
            ExternalBlocklistFailureReason.INVALID_FORMAT,
            "External blocklist JSON must be an array or an object with numbers",
        )
}
