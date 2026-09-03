package dev.scoutr.app.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspacePathsTest {

    private val cwd = "/home/gd/site"

    @Test
    fun findsAnAbsolutePathInProse() {
        val refs = extractWorkspaceRefs("wrote the report to /home/gd/site/report.html", cwd)
        assertEquals(1, refs.size)
        assertEquals("/home/gd/site/report.html", refs[0].absolutePath)
        assertEquals("report.html", refs[0].relativePath)
        assertEquals("report.html", refs[0].name)
    }

    @Test
    fun stripsSentencePunctuationAndLineSuffixes() {
        val refs = extractWorkspaceRefs(
            "see /home/gd/site/src/main.kt:42, and (/home/gd/site/notes.md).",
            cwd,
        )
        assertEquals(
            listOf("/home/gd/site/src/main.kt", "/home/gd/site/notes.md"),
            refs.map { it.absolutePath },
        )
    }

    @Test
    fun stripsLineAndColumnSuffixes() {
        val refs = extractWorkspaceRefs("fails at /home/gd/site/app.ts:12:3!", cwd)
        assertEquals(listOf("/home/gd/site/app.ts"), refs.map { it.absolutePath })
    }

    @Test
    fun ignoresOutsidePathsDirectoriesAndDuplicates() {
        val refs = extractWorkspaceRefs(
            "read /etc/passwd, listed /home/gd/site/src/, " +
                "edited /home/gd/site/a.kt and /home/gd/site/a.kt again",
            cwd,
        )
        assertEquals(listOf("/home/gd/site/a.kt"), refs.map { it.absolutePath })
    }

    @Test
    fun capsChipsAndKeepsFirstMentionOrder() {
        val text = (1..12).joinToString(" ") { "/home/gd/site/f$it.kt" }
        val refs = extractWorkspaceRefs(text, cwd)
        assertEquals(MAX_WORKSPACE_REFS, refs.size)
        assertEquals("/home/gd/site/f1.kt", refs.first().absolutePath)
        assertEquals("/home/gd/site/f$MAX_WORKSPACE_REFS.kt", refs.last().absolutePath)
    }

    @Test
    fun blankCwdMatchesNothing() {
        assertTrue(extractWorkspaceRefs("see /home/gd/site/a.kt", "").isEmpty())
        assertTrue(extractWorkspaceRefs("see /home/gd/site/a.kt", "  ").isEmpty())
    }

    @Test
    fun absoluteToWorkspaceRefValidatesContainment() {
        assertEquals(
            "src/b.kt",
            absoluteToWorkspaceRef("/home/gd/site/src/b.kt", cwd)?.relativePath,
        )
        assertNull(absoluteToWorkspaceRef("/etc/passwd", cwd))
        assertNull(absoluteToWorkspaceRef("/home/gd/site/src/", cwd))
        assertNull(absoluteToWorkspaceRef("/home/gd/site", cwd))
    }

    @Test
    fun dotSegmentsResolveInsideAndRefuseOutside() {
        assertEquals(
            "a.kt",
            extractWorkspaceRefs("see /home/gd/site/sub/../a.kt", cwd).single().relativePath,
        )
        assertTrue(extractWorkspaceRefs("see /home/gd/site/../secret.txt", cwd).isEmpty())
        assertNull(workspaceRefForPath("../evil.kt", cwd))
        assertEquals(
            "src/b.kt",
            workspaceRefForPath("sub/../src/b.kt", cwd)?.relativePath,
        )
    }

    @Test
    fun relativeEditPathsResolveAgainstCwd() {
        assertEquals("src/b.kt", workspaceRefForPath("src/b.kt", cwd)?.relativePath)
        assertEquals("Makefile", workspaceRefForPath("Makefile", cwd)?.relativePath)
        assertEquals(
            "/home/gd/site/src/b.kt",
            workspaceRefForPath("src/b.kt", cwd)?.absolutePath,
        )
        assertNull(workspaceRefForPath("", cwd))
    }
}
