package dev.scoutr.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pushed payload is untrusted — the link string must be validated and
 * rebuilt exactly like MainActivity's entry path, never handed to the launcher raw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeepLinkValidationTest {

    @Test
    fun evilClickFallsBackToPaneId() {
        val link = resolveNotificationLink(
            click = "https://evil.example/x",
            paneId = "p1",
            status = "working",
        )
        assertEquals("scoutr://chat/p1?status=working", link?.uri)
        assertEquals("p1", link?.paneId)
    }

    @Test
    fun evilClickWithoutPaneIdDropsTheDeepLink() {
        assertNull(resolveNotificationLink(click = "https://evil.example/x", paneId = null, status = "working"))
    }

    @Test
    fun validClickPassesThroughRebuilt() {
        val link = resolveNotificationLink(
            click = "scoutr://chat/p1?status=blocked",
            paneId = null,
            status = "working",
        )
        assertEquals("scoutr://chat/p1?status=blocked", link?.uri)
        assertEquals("p1", link?.paneId)
    }

    @Test
    fun parsedClickWinsOverRawPaneId() {
        // A validated pane must never be overridden by a raw payload field.
        val link = resolveNotificationLink(
            click = "scoutr://chat/p1?status=blocked",
            paneId = "p2",
            status = "working",
        )
        assertEquals("scoutr://chat/p1?status=blocked", link?.uri)
        assertEquals("p1", link?.paneId)
    }

    @Test
    fun noClickFallsBackToThePaneIdAndGivenStatus() {
        val link = resolveNotificationLink(click = null, paneId = "p3", status = "blocked")
        assertEquals("scoutr://chat/p3?status=blocked", link?.uri)
        assertEquals("p3", link?.paneId)
    }

    @Test
    fun parseScoutrUriRejectsForeignSchemes() {
        assertNull(parseScoutrUri("https://evil.example/x"))
        assertNull(parseScoutrUri("scoutr://other/p1"))
        assertEquals("p1", parseScoutrUri("scoutr://chat/p1?status=blocked")?.paneId)
    }

}
