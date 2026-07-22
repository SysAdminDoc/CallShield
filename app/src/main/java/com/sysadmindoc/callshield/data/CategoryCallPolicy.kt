package com.sysadmindoc.callshield.data

import androidx.datastore.preferences.core.Preferences
import com.sysadmindoc.callshield.R
import com.sysadmindoc.callshield.domain.model.SpamCheckResult

enum class CategoryCallAction(
    val storageKey: String,
    val labelResId: Int,
) {
    INHERIT("inherit", R.string.category_action_inherit),
    ALLOW("allow", R.string.category_action_allow),
    SILENCE("silence", R.string.category_action_silence),
    BLOCK("block", R.string.category_action_block),
}

data class CategoryPolicyMatch(
    val category: CallCategory,
    val action: CategoryCallAction,
    val originalMatchSource: String,
)

/** Applies user-selected call handling only after the detection pipeline has produced a category. */
object CategoryCallPolicy {
    private const val SERIALIZED_SEPARATOR = "="
    private const val MATCH_SOURCE_PREFIX = "category_policy:"
    private const val MATCH_SOURCE_PART_COUNT = 3

    private val explicitBlockSources =
        setOf(
            "user_blocklist",
            "temporary_block",
            "system_block_list",
            "wildcard",
            "hash_wildcard",
            "prefix",
        )

    val configurableCategories: List<CallCategory> = CallCategory.entries.filterNot { it == CallCategory.Unknown }

    fun decode(serialized: Set<String>): Map<CallCategory, CategoryCallAction> = serialized.mapNotNull(::decodeEntry).toMap()

    fun encode(actions: Map<CallCategory, CategoryCallAction>): Set<String> =
        actions
            .asSequence()
            .filter { (category, action) -> category != CallCategory.Unknown && action != CategoryCallAction.INHERIT }
            .map { (category, action) -> "${category.storageKey}$SERIALIZED_SEPARATOR${action.storageKey}" }
            .toSortedSet()

    fun sanitize(serialized: Collection<String>): Set<String> = decode(serialized.toSet()).let(::encode)

    fun update(
        serialized: Set<String>,
        category: CallCategory,
        action: CategoryCallAction,
    ): Set<String> {
        if (category == CallCategory.Unknown) return sanitize(serialized)
        val actions = decode(serialized).toMutableMap()
        if (action == CategoryCallAction.INHERIT) {
            actions.remove(category)
        } else {
            actions[category] = action
        }
        return encode(actions)
    }

    @Suppress("ReturnCount")
    fun apply(
        result: SpamCheckResult,
        preferences: Preferences,
    ): SpamCheckResult {
        if (!result.isSpam || result.matchSource in explicitBlockSources) return result
        val category = CallCategoryResolver.resolve(result)
        if (category == CallCategory.Unknown) return result
        val action =
            decode(preferences[SpamRepository.KEY_CATEGORY_CALL_ACTIONS].orEmpty())[category]
                ?: CategoryCallAction.INHERIT
        if (action == CategoryCallAction.INHERIT) return result
        return result.copy(matchSource = encodeMatchSource(category, action, result.matchSource))
    }

    @Suppress("ReturnCount")
    fun parseMatchSource(matchSource: String): CategoryPolicyMatch? {
        if (!matchSource.startsWith(MATCH_SOURCE_PREFIX)) return null
        val parts = matchSource.removePrefix(MATCH_SOURCE_PREFIX).split(":", limit = MATCH_SOURCE_PART_COUNT)
        if (parts.size != MATCH_SOURCE_PART_COUNT || parts[2].isBlank()) return null
        val category = CallCategory.entries.firstOrNull { it.storageKey == parts[0] } ?: return null
        val action = CategoryCallAction.entries.firstOrNull { it.storageKey == parts[1] } ?: return null
        if (category == CallCategory.Unknown || action == CategoryCallAction.INHERIT) return null
        return CategoryPolicyMatch(category, action, parts[2])
    }

    private fun encodeMatchSource(
        category: CallCategory,
        action: CategoryCallAction,
        originalMatchSource: String,
    ): String = "$MATCH_SOURCE_PREFIX${category.storageKey}:${action.storageKey}:$originalMatchSource"

    @Suppress("ReturnCount")
    private fun decodeEntry(entry: String): Pair<CallCategory, CategoryCallAction>? {
        val parts = entry.split(SERIALIZED_SEPARATOR, limit = 2)
        if (parts.size != 2) return null
        val category = configurableCategories.firstOrNull { it.storageKey == parts[0] } ?: return null
        val action = CategoryCallAction.entries.firstOrNull { it.storageKey == parts[1] } ?: return null
        return if (action == CategoryCallAction.INHERIT) null else category to action
    }
}
