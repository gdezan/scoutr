package com.termux.terminal;

import java.nio.charset.StandardCharsets;

/**
 * A terminal session coupled to a remote terminal transport instead of a local process.
 * <p>
 * ADAPTED for Scoutr from the Termux terminal-emulator library (see UPSTREAM.md in the vendor
 * root). The upstream class spawned a local shell subprocess on a PTY via JNI
 * ({@code JNI.createSubprocess} + input/output/waiter threads, {@code finishIfRunning()},
 * {@code getPid()}, {@code getCwd()}, queues and a main-thread handler). This adaptation removes
 * the process/PTY/JNI machinery and keeps the session as a transport-neutral contract:
 * <ul>
 * <li>{@link #appendOutput(byte[], int, int)} feeds bytes received from the remote side into the
 * emulator (replaces the process-to-terminal reader thread).</li>
 * <li>{@link #setInputCallback(TerminalInputCallback)} routes bytes written to the session
 * (user input, emulator replies) to the transport (replaces the terminal-to-process writer
 * thread).</li>
 * <li>{@link #initializeEmulator(int, int, int, int)} replaces the emulator, which supports
 * resetting state for a new remote stream generation.</li>
 * </ul>
 * The {@link TerminalView} contract is preserved: {@link #write(byte[], int, int)},
 * {@link #writeCodePoint(boolean, int)}, {@link #getEmulator()} and
 * {@link #updateSize(int, int, int, int)} behave as upstream, minus the local process.
 * <p>
 * All terminal emulation and callback methods are expected to be performed on the main thread.
 */
public class TerminalSession extends TerminalOutput {

    TerminalEmulator mEmulator;

    /** Callback which gets notified when a session finishes or changes title. */
    TerminalSessionClient mClient;

    private final Integer mTranscriptRows;

    /** Buffer used to translate code points into UTF-8 before writing to the input callback. */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Transport hook receiving bytes produced by this session (user input, emulator replies). */
    private TerminalInputCallback mInputCallback;

    /**
     * Seam between a {@link TerminalSession} and a terminal transport: receives bytes that would
     * have been written to the shell's stdin upstream. The transport forwards them to the remote
     * side.
     */
    public interface TerminalInputCallback {
        void onInput(byte[] data, int offset, int count);
    }

    public TerminalSession(Integer transcriptRows, TerminalSessionClient client) {
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /** Resize the emulator grid, initializing terminal emulation on the first call. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels);
        } else {
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /**
     * Initialize terminal emulation with the given window size. Any previous emulator is replaced;
     * used for the initial size and to reset all terminal state when a new remote stream generation
     * starts. Callers holding the previous {@link #getEmulator()} reference must re-fetch it.
     *
     * @param columns The number of columns in the terminal window.
     * @param rows    The number of rows in the terminal window.
     */
    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);
    }

    /**
     * Set the callback receiving bytes written to this session, or null to drop them. For a remote
     * terminal this is the transport hook that forwards input to the remote side. Bytes are dropped
     * when no callback is set, mirroring upstream behavior of dropping writes before the process
     * was started.
     */
    public void setInputCallback(TerminalInputCallback callback) {
        mInputCallback = callback;
    }

    /**
     * Feed output received from the remote side into the terminal emulator. Must be called on the
     * single thread that owns this session (Scoutr's terminal dispatcher); the client's
     * {@link TerminalSessionClient#onTextChanged(TerminalSession)} is notified so the view can
     * repaint, and that callback is responsible for reaching the UI thread.
     *
     * <p>{@code offset} is not supported by the emulator append below: callers must pass 0.
     */
    public void appendOutput(byte[] data, int offset, int count) {
        mEmulator.append(data, count);
        notifyScreenUpdate();
    }

    /** Write data to the remote side through the {@link TerminalInputCallback}. */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (mInputCallback != null) mInputCallback.onInput(data, offset, count);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }
}
