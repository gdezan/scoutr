package dev.cockpit.app.terminal

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.view.TerminalView
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Managed x86_64 device proof that the vendored Termux classes link and run on-device: the
 * emulator renders bytes through the transport-neutral session and TerminalView constructs.
 * No transport, ViewModel or UI is exercised.
 */
@RunWith(AndroidJUnit4::class)
class RemoteTerminalSessionClassLoadTest {

    @Test
    fun vendoredTerminalClassesLoadAndRenderOnDevice() {
        val session = RemoteTerminalSession(transcriptRows = 200)
        session.updateSize(40, 10, 13, 15)

        val bytes = "\u001b[32mHello x86_64".toByteArray(Charsets.UTF_8)
        session.appendOutput(bytes, 0, bytes.size)

        assertEquals("Hello x86_64", session.emulator!!.screen.getTranscriptTextWithoutJoinedLines())

        // Class-links com.termux.view.TerminalView (renderer, gestures, text selection) on-device.
        // TerminalView creates a GestureDetector, so it must be constructed on a Looper thread.
        val view = AtomicReference<TerminalView?>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.set(TerminalView(ApplicationProvider.getApplicationContext(), null))
        }
        assertNotNull(view.get())
    }
}
