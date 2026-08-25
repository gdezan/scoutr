package dev.scoutr.app.ui.screens

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import dev.scoutr.app.data.ContentBlock
import dev.scoutr.app.data.SessionEntry
import dev.scoutr.app.ui.theme.ScoutrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Chat transcript scroll-follow policy at the [ChatList] seam, mirroring the
 * contract the terminal locks down in TerminalScrollFollowTest.
 *
 * The user-visible contract: incoming transcript traffic moves the list only
 * while it is fully scrolled down. A reader in history — holding a drag,
 * coasting in a fling, or parked — stays put: no programmatic scroll may chase
 * the bottom, cancel a fling, or fight the finger. The 2.5s poll appends rows
 * and a reverse-history page prepends them; neither is allowed to move the
 * viewport unless the tail row's bottom edge is flush with the viewport end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatListScrollFollowTest {

    @get:Rule
    val compose = createComposeRule()

    private fun entry(id: String, text: String) = SessionEntry(
        entryId = id,
        role = "user",
        content = listOf(ContentBlock(type = "text", text = text)),
    )

    private fun rows(count: Int, prefix: String = "e") =
        (1..count).map { i -> entry("$prefix$i", "message $i") }


    @Test
    fun appendWhileFullyScrolledDownFollowsToNewTail() {
        val listState = LazyListState()
        var current by mutableStateOf(rows(60))
        compose.setContent {
            ScoutrTheme {
                ChatList(entries = current, state = listState)
            }
        }
        compose.waitForIdle()
        assertTrue("opens at the end", isChatListAtEndForTest(listState))

        current = current + rows(3, prefix = "new")
        compose.waitForIdle()

        assertTrue("stays glued to the tail after an append", isChatListAtEndForTest(listState))
    }

    @Test
    fun appendWhenNotFullyScrolledDownDoesNotMoveTheList() {
        val listState = LazyListState()
        var current by mutableStateOf(rows(60))
        compose.setContent {
            ScoutrTheme {
                ChatList(entries = current, state = listState)
            }
        }
        compose.waitForIdle()

        // Leave the end the way an interrupted gesture leaves it: raw delta,
        // no drag-interaction bookkeeping. This is the stale-intent hole a
        // fling window exposes — follow must read live position, not gesture
        // history.
        listState.dispatchRawDelta(-600f)
        compose.waitForIdle()
        val indexBeforeAppend = listState.firstVisibleItemIndex
        assertTrue("precondition: reader left the end", indexBeforeAppend < 59)

        current = current + rows(3, prefix = "new")
        compose.waitForIdle()

        assertEquals(
            "append pulled the list back to the tail",
            indexBeforeAppend,
            listState.firstVisibleItemIndex,
        )
    }

    @Test
    fun appendDuringHeldUpwardDragDoesNotMoveTheList() {
        val listState = LazyListState()
        var current by mutableStateOf(rows(60))
        compose.setContent {
            ScoutrTheme {
                ChatList(entries = current, state = listState)
            }
        }
        compose.waitForIdle()

        // Grab the list mid-history with a real touch and HOLD it down: the
        // pointer stays pressed after performTouchInput returns, which is the
        // exact window a 2.5s poll tick lands in while the user scrolls up.
        compose.onNodeWithTag("chat_list").performTouchInput {
            down(center)
            moveBy(Offset(0f, 600f))
        }
        compose.waitForIdle()
        assertTrue(
            "drag did not leave the end",
            listState.firstVisibleItemIndex < 59,
        )
        val indexDuringDrag = listState.firstVisibleItemIndex

        // The poll tick lands: three new entries append at the tail mid-drag.
        current = current + rows(3, prefix = "new")
        compose.waitForIdle()

        assertEquals(
            "mid-drag append moved the viewport toward the tail",
            indexDuringDrag,
            listState.firstVisibleItemIndex,
        )

        compose.onNodeWithTag("chat_list").performTouchInput { up() }
        compose.waitForIdle()
    }

    /** Test-side mirror of the production at-end predicate. */
    private fun isChatListAtEndForTest(state: LazyListState): Boolean {
        val info = state.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
        return !state.canScrollForward && lastVisible == info.totalItemsCount - 1
    }

}
