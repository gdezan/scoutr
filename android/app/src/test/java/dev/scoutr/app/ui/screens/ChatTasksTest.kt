package dev.scoutr.app.ui.screens

import dev.scoutr.app.data.AgentTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTasksTest {
    @Test
    fun `deleted tasks are omitted from the task surface`() {
        val visible = visibleAgentTasks(
            listOf(
                AgentTask(id = "1", subject = "Open", status = "pending"),
                AgentTask(id = "2", subject = "Gone", status = "deleted"),
                AgentTask(id = "3", subject = "Done", status = "completed"),
            ),
        )

        assertEquals(listOf("1", "3"), visible.map { it.id })
    }

    @Test
    fun `task is blocked until every dependency is completed`() {
        val waiting = AgentTask(id = "1", subject = "Waiting", blockedBy = listOf("2"))
        val pendingDependency = AgentTask(id = "2", subject = "Dependency", status = "pending")
        val completedDependency = pendingDependency.copy(status = "completed")

        assertTrue(isAgentTaskBlocked(waiting, mapOf(pendingDependency.id to pendingDependency)))
        assertFalse(isAgentTaskBlocked(waiting, mapOf(completedDependency.id to completedDependency)))
        assertTrue(isAgentTaskBlocked(waiting, emptyMap()))
    }
}
