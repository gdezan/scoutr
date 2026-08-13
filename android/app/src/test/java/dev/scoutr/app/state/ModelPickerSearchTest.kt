package dev.scoutr.app.state

import dev.scoutr.app.data.ModelInfo
import dev.scoutr.app.data.ModelProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPickerSearchTest {
    private val providers = listOf(
        ModelProvider(
            name = "openai-codex",
            models = listOf(
                ModelInfo(
                    id = "gpt-5.4",
                    name = "GPT-5.4",
                    reasoning = true,
                    thinkingLevels = listOf("low", "medium", "high"),
                    contextWindow = 200_000,
                ),
                ModelInfo(id = "gpt-4.1-mini", name = "GPT-4.1 Mini", contextWindow = 64_000),
            ),
        ),
        ModelProvider(
            name = "anthropic",
            models = listOf(
                ModelInfo(
                    id = "claude-sonnet-4-6",
                    name = "Claude Sonnet 4.6",
                    reasoning = true,
                    thinkingLevels = listOf("low", "high"),
                    contextWindow = 200_000,
                ),
            ),
        ),
        ModelProvider(
            name = "local",
            models = listOf(ModelInfo(id = "llama-3", name = "Llama 3", contextWindow = 32_000)),
        ),
    )

    @Test
    fun typoTolerantSearchFindsModelByName() {
        val results = searchModelCatalog(providers, ModelPickerFilters(query = "cluade"))

        assertEquals(listOf("anthropic/claude-sonnet-4-6"), results.map { it.key })
    }

    @Test
    fun providerAndModelSearchMatchesWithoutCapabilityFilters() {
        val results = searchModelCatalog(
            providers = providers,
            filters = ModelPickerFilters(query = "openai-codex/gpt-5.4"),
        )

        assertEquals("openai-codex/gpt-5.4", results.first().key)
        assertTrue(results.any { it.key == "openai-codex/gpt-5.4" })
    }
    @Test
    fun noQueryRanksDefaultThenFavoriteThenRecents() {
        val results = searchModelCatalog(
            providers = providers,
            filters = ModelPickerFilters(),
            favoriteKeys = setOf("anthropic/claude-sonnet-4-6"),
            recentKeys = listOf("local/llama-3", "openai-codex/gpt-4.1-mini"),
            defaultKey = "openai-codex/gpt-5.4",
        )

        assertEquals("openai-codex/gpt-5.4", results[0].key)
        assertEquals("anthropic/claude-sonnet-4-6", results[1].key)
        assertEquals("local/llama-3", results[2].key)
        assertTrue(results[0].default)
        assertTrue(results[1].favorite)
        assertTrue(results[2].recent)
    }

    @Test
    fun currentSelectionIsFirstWhenThereIsNoSearchQuery() {
        val results = searchModelCatalog(
            providers = providers,
            filters = ModelPickerFilters(),
            selectedKey = "local/llama-3",
        )

        assertEquals("local/llama-3", results.first().key)
    }
}
