package dev.cockpit.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

/**
 * Deterministic on-device reproduction of the chat scroll crash class:
 * a LazyColumn whose data contains duplicate keys throws
 * IllegalArgumentException "Key ... was already used" when those keys are
 * composed. This is exactly what the old bridge's lexical since-filter +
 * blind append produced (seen live on the phone 3x and reproduced on the
 * emulator with the pre-fix bridge + pre-fix app).
 *
 * The fix (mergeSessionEntries) guarantees the chat list never contains
 * duplicate entry ids, so this crash class cannot reach the list anymore.
 */
class DuplicateKeyCrashTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Composable
    private fun ListWithDuplicateKeys() {
        LazyColumn(Modifier.size(400.dp).testTag("dup_list")) {
            // entry "b2" appears twice — the exact shape the old code produced
            items(listOf("a1", "b2", "c3", "b2", "d4"), key = { it }) { id ->
                Text(id)
            }
        }
    }

    @Test
    fun duplicateKeysCrashWhenComposed() {
        // Composing a list whose data contains a duplicate key must abort with
        // the duplicate-key IllegalArgumentException. The crash can fire on the
        // initial composition or on scroll, so capture any Throwable from the
        // whole pass and assert the exact failure class.
        var failure: Throwable? = null
        try {
            composeRule.setContent { ListWithDuplicateKeys() }
            composeRule.onNodeWithTag("dup_list").performScrollToIndex(4)
            composeRule.waitForIdle()
        } catch (t: Throwable) {
            failure = t
        }
        // The test environment can re-raise composition failures asynchronously;
        // one more idle pass attributes them to this thread.
        try {
            composeRule.waitForIdle()
        } catch (t: Throwable) {
            if (failure == null) failure = t
        }
        org.junit.Assert.assertTrue(
            "expected the duplicate-key failure, got: ${failure?.message}",
            failure?.message?.contains("was already used") == true,
        )
    }
}
