package dev.cockpit.app.state

import dev.cockpit.app.data.ModelInfo
import dev.cockpit.app.data.ModelProvider

/** Active model-catalog filters; context size is measured in tokens. */
data class ModelPickerFilters(
    val query: String = "",
    val reasoningOnly: Boolean = false,
    val minimumContextTokens: Long? = null,
    val thinkingLevel: String? = null,
)

/** One ranked model-picker result with provider and preference metadata. */
data class ModelPickerMatch(
    val key: String,
    val provider: String,
    val model: ModelInfo,
    val favorite: Boolean,
    val recent: Boolean,
    val default: Boolean,
)

/** Stable provider-qualified model key passed to pi and stored in launcher settings. */
fun modelPickerKey(provider: String, modelId: String): String = "$provider/$modelId"

/** Filter and typo-tolerantly rank the model catalog without remote work. */
fun searchModelCatalog(
    providers: List<ModelProvider>,
    filters: ModelPickerFilters,
    favoriteKeys: Set<String> = emptySet(),
    recentKeys: List<String> = emptyList(),
    defaultKey: String? = null,
    selectedKey: String? = null,
): List<ModelPickerMatch> {
    val query = normalizeModelSearchText(filters.query)
    val recentRanks = recentKeys.withIndex().associate { it.value to it.index }

    return providers.flatMap { provider ->
        provider.models.mapNotNull { model ->
            val providerName = model.provider.ifBlank { provider.name }
            val key = modelPickerKey(providerName, model.id)
            if (filters.reasoningOnly && !model.reasoning) return@mapNotNull null
            if (filters.minimumContextTokens != null && (model.contextWindow ?: 0) < filters.minimumContextTokens) {
                return@mapNotNull null
            }
            if (filters.thinkingLevel != null && filters.thinkingLevel !in model.thinkingLevels) {
                return@mapNotNull null
            }
            val score = if (query.isBlank()) 0 else modelSearchScore(
                query,
                listOf(providerName, model.name, model.id, key),
            )
            if (query.isNotBlank() && score < 0) return@mapNotNull null
            RankedModelMatch(
                match = ModelPickerMatch(
                    key = key,
                    provider = providerName,
                    model = model,
                    favorite = key in favoriteKeys,
                    recent = key in recentRanks,
                    default = key == defaultKey,
                ),
                searchScore = score,
                recentRank = recentRanks[key] ?: Int.MAX_VALUE,
                selected = key == selectedKey,
            )
        }
    }.sortedWith(
        compareByDescending<RankedModelMatch> { it.searchScore }
            .thenByDescending { it.selected }
            .thenByDescending { it.match.default }
            .thenByDescending { it.match.favorite }
            .thenBy { it.recentRank }
            .thenBy { it.match.model.name.ifBlank { it.match.model.id }.lowercase() },
    ).map { it.match }
}

private data class RankedModelMatch(
    val match: ModelPickerMatch,
    val searchScore: Int,
    val recentRank: Int,
    val selected: Boolean,
)

private fun modelSearchScore(query: String, fields: List<String>): Int {
    val tokens = query.split(' ').filter(String::isNotBlank)
    var total = 0
    for (token in tokens) {
        val best = fields.maxOf { fuzzyModelFieldScore(token, normalizeModelSearchText(it)) }
        if (best < 0) return -1
        total += best
    }
    return total
}

private fun fuzzyModelFieldScore(query: String, candidate: String): Int {
    if (query == candidate) return 200
    val substringIndex = candidate.indexOf(query)
    if (substringIndex >= 0) return 170 - substringIndex.coerceAtMost(40)

    var queryIndex = 0
    var gaps = 0
    for (character in candidate) {
        if (queryIndex < query.length && character == query[queryIndex]) {
            queryIndex += 1
        } else if (queryIndex > 0) {
            gaps += 1
        }
    }
    if (queryIndex == query.length) return 120 - gaps.coerceAtMost(60)

    val allowedDistance = when {
        query.length <= 4 -> 1
        query.length <= 8 -> 2
        else -> 3
    }
    val typoCandidates = buildList {
        add(candidate.take(query.length))
        addAll(candidate.split(' ').filter { kotlin.math.abs(it.length - query.length) <= allowedDistance })
    }
    val distance = typoCandidates.minOfOrNull { levenshteinDistance(query, it) } ?: Int.MAX_VALUE
    return if (distance <= allowedDistance) 90 - distance * 12 else -1
}

private fun normalizeModelSearchText(text: String): String = text
    .lowercase()
    .replace(Regex("[^a-z0-9]+"), " ")
    .trim()

private fun levenshteinDistance(left: String, right: String): Int {
    if (left.isEmpty()) return right.length
    if (right.isEmpty()) return left.length
    var previous = IntArray(right.length + 1) { it }
    for (leftIndex in left.indices) {
        val current = IntArray(right.length + 1)
        current[0] = leftIndex + 1
        for (rightIndex in right.indices) {
            val substitutionCost = if (left[leftIndex] == right[rightIndex]) 0 else 1
            current[rightIndex + 1] = minOf(
                current[rightIndex] + 1,
                previous[rightIndex + 1] + 1,
                previous[rightIndex] + substitutionCost,
            )
        }
        previous = current
    }
    return previous[right.length]
}
