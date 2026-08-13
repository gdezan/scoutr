package dev.cockpit.app.terminal

import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/**
 * Transport-neutral terminal session for a remote pane.
 *
 * Owns the vendored [TerminalSession] (see android/vendor/termux/UPSTREAM.md) with no local
 * process: [appendOutput] feeds bytes received from the remote side into the emulator and the
 * [inputSink] routes bytes produced by the terminal (typed input, emulator query replies) to the
 * transport. Transport wiring, ViewModel and UI arrive in later slices; until then [inputSink]
 * stays null and written bytes are dropped (mirroring upstream behavior before the process
 * started).
 *
 * Callback policy decisions taken here:
 *  - OSC 52 clipboard set requests are blocked: a remote pane must never write the device
 *    clipboard. They are dropped and logged, never forwarded.
 *  - All other [TerminalSessionClient] events are forwarded to [callbacks] with no-op defaults.
 */
class RemoteTerminalSession(
    transcriptRows: Int?,
    val callbacks: Callbacks = Callbacks(),
) : TerminalSession(transcriptRows, SessionClient(callbacks)) {
    /**
     * Transport hook for bytes produced by the terminal. The receiver must not retain the array
     * beyond the call (it may be a reused internal buffer); it is copied before delivery.
     */
    var inputSink: ((ByteArray) -> Unit)? = null
        set(value) {
            field = value
            setInputCallback(
                if (value == null) {
                    null
                } else {
                    TerminalSession.TerminalInputCallback { data, offset, count ->
                        value(data.copyOfRange(offset, offset + count))
                    }
                }
            )
        }

    /**
     * Replace all terminal state (transcript, modes, colors, cursor, saved states) with a fresh
     * emulator for a new remote stream generation. Screen listeners are notified so the view can
     * repaint; a view holding the previous [getEmulator] reference must re-fetch it (e.g. via
     * updateSize or re-attach).
     */
    fun resetForGeneration(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels)
        notifyScreenUpdate()
    }

    /** App-facing callback slots with no-op defaults; wired (and rewired) by later slices. */
    class Callbacks(
        /** Terminal content changed; a TerminalView should repaint. */
        var onScreenUpdated: () -> Unit = {},
        /** OSC 0/1/2 window title change. */
        var onTitleChanged: () -> Unit = {},
        /** The remote side closed the stream. */
        var onSessionFinished: () -> Unit = {},
        /** BEL received. */
        var onBell: () -> Unit = {},
        /** OSC 4/10/11/12/104 color change. */
        var onColorsChanged: () -> Unit = {},
        /** Cursor shown/hidden via DEC private mode. */
        var onCursorStateChange: (Boolean) -> Unit = {},
        /** The terminal asked for a paste of the device clipboard. */
        var onClipboardPasteRequest: () -> Unit = {},
    )

    /** Bridges the vendored [TerminalSessionClient] contract to the app-facing [Callbacks]. */
    private class SessionClient(
        private val callbacks: Callbacks,
    ) : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) = callbacks.onScreenUpdated()

        override fun onTitleChanged(changedSession: TerminalSession) = callbacks.onTitleChanged()

        override fun onSessionFinished(finishedSession: TerminalSession) = callbacks.onSessionFinished()

        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            // OSC 52 clipboard set: blocked by policy. Remote panes must not write the device
            // clipboard (see .plans/full-screen-interactive-terminal.md failure handling).
            Log.d(TAG, "Blocked OSC 52 clipboard set request")
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) = callbacks.onClipboardPasteRequest()

        override fun onBell(session: TerminalSession) = callbacks.onBell()

        override fun onColorsChanged(session: TerminalSession) = callbacks.onColorsChanged()

        override fun onTerminalCursorStateChange(state: Boolean) = callbacks.onCursorStateChange(state)

        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
            // No local shell process in the remote model.
        }

        override fun getTerminalCursorStyle(): Int? = null

        override fun logError(tag: String, message: String) { Log.e(tag, message) }

        override fun logWarn(tag: String, message: String) { Log.w(tag, message) }

        override fun logInfo(tag: String, message: String) { Log.i(tag, message) }

        override fun logDebug(tag: String, message: String) { Log.d(tag, message) }

        override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }

        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
            Log.e(tag, message, e)
        }

        override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "stack trace", e) }
    }

    private companion object {
        const val TAG = "RemoteTerminalSession"
    }
}
