package dev.cockpit.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two pure functions behind the transcript's busy indicator: which mode
 * (if any) a given state renders, and how time-in-status reads.
 */
class WorkingIndicatorTest {

    @Test
    fun elapsedShowsSecondsUnderAMinute() {
        assertEquals("0s", formatElapsed(0))
        assertEquals("3s", formatElapsed(3_400))
        assertEquals("47s", formatElapsed(47_000))
        assertEquals("59s", formatElapsed(59_999))
    }

    @Test
    fun elapsedShowsMinutesAndSecondsUnderTenMinutes() {
        assertEquals("1m 0s", formatElapsed(60_000))
        assertEquals("1m 12s", formatElapsed(72_000))
        assertEquals("4m 30s", formatElapsed(270_000))
        assertEquals("9m 59s", formatElapsed(599_000))
    }

    @Test
    fun elapsedDropsSecondsFromTenMinutesOn() {
        assertEquals("10m", formatElapsed(600_000))
        assertEquals("14m", formatElapsed(14 * 60_000 + 45_000))
        assertEquals("120m", formatElapsed(2 * 60 * 60_000))
    }

    @Test
    fun elapsedClampsClockSkewToZero() {
        // A stamp in the future must never render as a negative duration.
        assertEquals("0s", formatElapsed(-1))
        assertEquals("0s", formatElapsed(-90_000))
    }

    @Test
    fun startingWinsOverEveryOtherStatus() {
        // A booting session must not claim to be working.
        for (status in listOf("working", "blocked", "idle", "done", "flumoxed")) {
            for (question in listOf(true, false)) {
                assertEquals(
                    "starting + $status + question=$question",
                    WorkingIndicatorMode.Starting,
                    workingIndicatorMode(starting = true, agentStatus = status, hasPendingQuestion = question),
                )
            }
        }
    }

    @Test
    fun workingRendersWorkingRegardlessOfQuestions() {
        assertEquals(
            WorkingIndicatorMode.Working,
            workingIndicatorMode(starting = false, agentStatus = "working", hasPendingQuestion = false),
        )
        assertEquals(
            WorkingIndicatorMode.Working,
            workingIndicatorMode(starting = false, agentStatus = "working", hasPendingQuestion = true),
        )
    }

    @Test
    fun blockedWithoutAQuestionAsksTheUser() {
        assertEquals(
            WorkingIndicatorMode.WaitingForYou,
            workingIndicatorMode(starting = false, agentStatus = "blocked", hasPendingQuestion = false),
        )
    }

    @Test
    fun blockedWithAQuestionDefersToTheCard() {
        // The QuestionCard already states the need and carries the buttons;
        // a second "waiting for you" next to it is noise.
        assertNull(workingIndicatorMode(starting = false, agentStatus = "blocked", hasPendingQuestion = true))
    }

    @Test
    fun settledAndUnknownStatusesRenderNothing() {
        for (status in listOf("idle", "done", "closed", "", "some-future-status")) {
            for (question in listOf(true, false)) {
                assertNull(
                    "$status + question=$question",
                    workingIndicatorMode(starting = false, agentStatus = status, hasPendingQuestion = question),
                )
            }
        }
    }
}
