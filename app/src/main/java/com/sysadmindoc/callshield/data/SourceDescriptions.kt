package com.sysadmindoc.callshield.data

import android.content.Context
import androidx.annotation.StringRes
import com.sysadmindoc.callshield.R

/**
 * A spam database description in words. The importers write their own field
 * names into it ("FCC callback_business: Unwanted Calls"), and a row that
 * several sources or complaint roles reported repeats itself, so Number details
 * and Lookup showed raw keys and the same complaint twice. The stored text and
 * its report count don't change; this is only how it reads, one line per kind
 * of complaint, and running it twice changes nothing.
 */
internal object SourceDescriptions {
    private val complaint = Regex("""^(FCC|FTC)(?:\s+(caller_id|callback_business|caller ID))?:\s*(.+)$""")
    private val separator = Regex("""\s*[;\n]\s*""")
    private val whitespace = Regex("""\s+""")

    private enum class Kind(
        @param:StringRes val format: Int,
    ) {
        FCC_CALLER_ID(R.string.source_fcc_caller_id),
        FCC_CALLBACK(R.string.source_fcc_callback),
        FCC_UNSPECIFIED(R.string.source_fcc_unspecified),
        FTC_CALLER_ID(R.string.source_ftc_caller_id),
    }

    fun readable(
        context: Context,
        raw: String,
    ): String {
        val subjects = linkedMapOf<Kind, LinkedHashSet<String>>()
        val other = LinkedHashSet<String>()
        for (segment in raw.split(separator)) {
            val text = segment.replace(whitespace, " ").trim()
            if (text.isEmpty()) continue
            val match = complaint.matchEntire(text)
            if (match == null) {
                other += text
                continue
            }
            val (source, role, subject) = match.destructured
            val kind =
                when {
                    source == "FTC" -> Kind.FTC_CALLER_ID
                    role == "callback_business" -> Kind.FCC_CALLBACK
                    role == "caller_id" -> Kind.FCC_CALLER_ID
                    else -> Kind.FCC_UNSPECIFIED
                }
            subjects.getOrPut(kind) { LinkedHashSet() } += subject
        }
        // "FCC: X" predates the importer splitting complaints by role, and it
        // repeats a subject one of the roles already names.
        val named = subjects[Kind.FCC_CALLER_ID].orEmpty() + subjects[Kind.FCC_CALLBACK].orEmpty()
        subjects[Kind.FCC_UNSPECIFIED]?.removeAll(named)
        val lines =
            Kind.entries.mapNotNull { kind ->
                subjects[kind]?.takeIf { it.isNotEmpty() }?.let { context.getString(kind.format, it.joinToString(", ")) }
            }
        return (lines + other).joinToString("\n")
    }
}
