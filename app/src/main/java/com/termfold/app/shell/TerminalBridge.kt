package com.termfold.app.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient

/**
 * Bridges Termux's terminal emulator to the bundled Ubuntu environment.
 *
 * Two interfaces have to be implemented because the library splits the work in two:
 *
 *  - [TerminalSessionClient] is the session's own callback surface: it fires when the screen
 *    changes, when the process exits, and when the emulator wants something copied.
 *  - [TerminalViewClient] is the *view's* policy: which keys are intercepted before the emulator
 *    sees them, whether back acts as Escape, and what the on-screen Ctrl/Alt buttons mean.
 *
 * Everything that reaches the UI is reposted to the main thread, because the session's reader
 * threads call in from arbitrary threads and `TerminalView` may not be touched off the main one.
 */
class TerminalBridge(
    private val context: Context,
    private val host: TerminalHostCallbacks,
) : TerminalSessionClient, TerminalViewClient {

    private val main = Handler(Looper.getMainLooper())

    /**
     * The cumulative pinch multiplier `TerminalView` is holding for us. The view multiplies each
     * gesture event into it, hands it over, and uses whatever we return as the new accumulator —
     * so this is where the raw ratio is turned into discrete, readable font-size steps.
     */
    private var pinchFactor = 1f

    /** Modifier state for the extra key row, toggled by the on-screen Ctrl/Alt buttons. */
    private var controlKey = false
    private var altKey = false

    /**
     * Activity tracking for the "working" spinner in the session list. Output counts as work
     * only when it is not the echo of something just typed: a CLI that is busy keeps drawing on
     * its own, while one waiting at a prompt only redraws in answer to keystrokes.
     */
    @Volatile
    private var lastInputAt = 0L

    @Volatile
    private var lastWorkAt = 0L

    /** True while the session has been producing output of its own in the last moment. */
    val isWorking: Boolean
        get() = System.currentTimeMillis() - lastWorkAt < WORK_WINDOW_MS

    private fun markInput() {
        lastInputAt = System.currentTimeMillis()
    }

    fun setControlKey(down: Boolean) {
        controlKey = down
    }

    fun setAltKey(down: Boolean) {
        altKey = down
    }

    /** Sends Escape, which the soft keyboard usually cannot produce at all. */
    fun sendEscape() = writeToTerminal("\u001b")

    /** Sends the literal Tab character rather than letting the view move focus. */
    fun sendTab() = writeToTerminal("\t")

    /**
     * Sends Enter.
     *
     * Deliberately a carriage return, not a line feed: that is what a terminal in raw mode
     * expects, and it is what the Enter key on a physical keyboard produces.
     */
    fun sendEnter() = writeToTerminal("\r")

    /** Sends a control character by its letter, e.g. `D` for Ctrl+D (end of input). */
    fun sendControl(letter: Char) {
        val upper = letter.uppercaseChar()
        if (upper in 'A'..'Z') writeToTerminal(((upper - 'A') + 1).toChar().toString())
    }

    /** Sends a raw escape sequence, used by the navigation keys. */
    fun sendSequence(sequence: String) = writeToTerminal(sequence)

    private fun writeToTerminal(text: String) {
        markInput()
        host.terminalSession()?.write(text)
    }

    // --- TerminalSessionClient ------------------------------------------------------------------

    override fun onTextChanged(session: TerminalSession) {
        val now = System.currentTimeMillis()
        if (now - lastInputAt > ECHO_WINDOW_MS) lastWorkAt = now
        // Only the session on screen needs drawing: a busy background shell (apt, npm, a build)
        // must not keep repainting the visible one. And output arrives in many small chunks, so
        // one redraw is queued per burst rather than one per chunk.
        if (session !== host.terminalSession()) return
        if (redrawQueued.compareAndSet(false, true)) {
            main.post {
                redrawQueued.set(false)
                host.onScreenUpdated()
            }
        }
    }

    private val redrawQueued = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onTitleChanged(session: TerminalSession) {
        // Bash reports the running command through OSC 0/2, which makes a good session title.
        val title = session.title
        if (!title.isNullOrBlank()) {
            main.post { host.onTitleChanged(title) }
        }
    }

    override fun onSessionFinished(session: TerminalSession) {
        // The session travels with the call: a session killed because the user opened another one
        // finishes *after* its replacement has started, and the host must be able to tell them
        // apart or it would flag the new session as exited.
        main.post { host.onSessionFinished(session) }
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        main.post { copyToClipboard(text) }
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        // TerminalView reads the clipboard itself and writes it to the session; this hook is a
        // notification that it happened, so there is nothing to do.
    }

    override fun onBell(session: TerminalSession) {
        // No vibration: that would fight the shell on every tab-completion beep. The host only
        // turns a bell into a notification while the app is out of sight.
        main.post { host.onBell(session) }
    }

    override fun onColorsChanged(session: TerminalSession) {
        main.post { host.onScreenUpdated() }
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        // The cursor is drawn by the view from the emulator's own state.
    }

    override fun getTerminalCursorStyle(): Int = TERMINAL_CURSOR_STYLE_BLOCK

    // --- TerminalViewClient ---------------------------------------------------------------------

    override fun onScale(scale: Float): Float {
        // Every 12% of cumulative pinch movement is one size step, which keeps the text readable
        // at every point of the gesture instead of jumping to the raw ratio (2x pinch would
        // otherwise ask for a 2sp font).
        pinchFactor *= scale
        var steps = 0
        while (pinchFactor >= STEP) { steps += 1; pinchFactor /= STEP }
        while (pinchFactor <= 1f / STEP) { steps -= 1; pinchFactor *= STEP }
        if (steps != 0) main.post { host.onFontStep(steps) }
        return pinchFactor
    }

    override fun onSingleTapUp(event: MotionEvent) {
        main.post { host.onTap() }
    }

    /**
     * Back acts as Escape while the terminal has focus. Without this, a TUI that expects Escape
     * to close a menu is unusable on a phone.
     */
    override fun shouldBackButtonBeMappedToEscape(): Boolean = true

    /**
     * Character-based input keeps the IME from running autocorrect and predictions over shell
     * input, which would otherwise rewrite commands as they are typed.
     */
    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) {
        // Reported through the text-selection controller; no state to mirror.
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent, session: TerminalSession): Boolean {
        markInput()
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // Back first gives the UI a chance to handle it (closing the keyboard, then leaving
            // the screen); only if the UI declines does it fall through to Escape above.
            main.post { host.onBackPressed() }
            return false
        }
        // Ctrl+V (and Ctrl+Shift+V) paste from Android's clipboard. The agents' own Ctrl+V reads
        // an X11/Wayland clipboard the guest does not have ("Failed to paste image: clipboard
        // unavailable" in Codex), so an image becomes a saved file whose path is pasted instead,
        // which Codex and Claude Code both attach. An empty clipboard lets ^V through as usual.
        if (keyCode == KeyEvent.KEYCODE_V && event.isCtrlPressed && !event.isAltPressed) {
            return TerminalHost.paste(context)
        }
        // Ctrl+Shift+F opens search. Plain Ctrl+F stays with the shell and TUIs (forward-char,
        // page-down in less), as in desktop terminals.
        if (keyCode == KeyEvent.KEYCODE_F && event.isCtrlPressed && event.isShiftPressed) {
            main.post { TerminalHost.onFindRequested?.invoke() }
            return true
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = controlKey
    override fun readAltKey(): Boolean = altKey
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false

    /**
     * Runs after the emulator has resolved a character, which is the last chance to turn Ctrl+X
     * into the control code a readline or a TUI actually listens for.
     */
    override fun onCodePoint(
        codePoint: Int,
        controlDown: Boolean,
        session: TerminalSession,
    ): Boolean {
        markInput()
        if (!controlDown) return false
        // The on-screen Ctrl key plus v: the same clipboard paste as a hardware Ctrl+V.
        if ((codePoint == 'v'.code || codePoint == 'V'.code) && TerminalHost.paste(context)) {
            main.post { host.onScreenUpdated() }
            return true
        }
        val mapped = when (codePoint) {
            in 'a'.code..'z'.code -> codePoint - 'a'.code + 1
            in 'A'.code..'Z'.code -> codePoint - 'A'.code + 1
            ' '.code, '2'.code -> 0
            '['.code, '3'.code -> 27
            '\\'.code, '4'.code -> 28
            ']'.code, '5'.code -> 29
            '^'.code, '6'.code -> 30
            '_'.code, '7'.code -> 31
            else -> return false
        }
        session.write(byteArrayOf(mapped.toByte()), 0, 1)
        return true
    }

    override fun onEmulatorSet() {
        main.post { host.onScreenUpdated() }
    }

    // --- Logging --------------------------------------------------------------------------------

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, throwable: Exception) {
        Log.e(tag, message, throwable)
    }

    override fun logStackTrace(tag: String, throwable: Exception) {
        Log.e(tag, "error", throwable)
    }

    private fun copyToClipboard(text: String) {
        runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("TermFold", text))
        }
    }

    private companion object {
        /** Output this soon after a keystroke is treated as its echo, not as work. */
        private const val ECHO_WINDOW_MS = 500L

        /** How long after its last output a session still counts as working. */
        private const val WORK_WINDOW_MS = 2_000L

        /** `TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK`, inlined to avoid the emulator import. */
        const val TERMINAL_CURSOR_STYLE_BLOCK = 1

        /** One font-size step of cumulative pinch movement. */
        const val STEP = 1.12f
    }
}
