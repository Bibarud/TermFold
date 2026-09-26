package com.termfold.app.shell

import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The slice of [TerminalHost] that [TerminalBridge] drives. */
interface TerminalHostCallbacks {
    /** The session to write keystrokes to, or null when nothing is running. */
    fun terminalSession(): TerminalSession?
    fun onScreenUpdated()
    fun onTitleChanged(title: String)

    /**
     * The user zoomed the terminal text by [steps] size steps (positive = larger). Pinch gestures
     * arrive as a stream of small scale factors, so the bridge turns them into discrete steps and
     * the host keeps the resulting size.
     */
    fun onFontStep(steps: Int)
    fun onTap()
    fun onBackPressed()

    /**
     * A session's process has ended. The session is part of the call because several sessions can
     * be alive at once: a background one exiting must not be reported as if the visible one had.
     */
    fun onSessionFinished(session: TerminalSession)

    /** The program in [session] rang the terminal bell (CLI agents do this when they need you). */
    fun onBell(session: TerminalSession)
}

/**
 * Owns the live terminal sessions.
 *
 * A shell has to outlive the Composable that displays it: recomposition, rotation, and navigating
 * back to the folder list all happen while the guest process keeps running. Holding the sessions
 * here, instead of in Compose state, is what makes that true — the UI attaches to an existing
 * session rather than starting a new one.
 *
 * Sessions are keyed by folder *and* the folder's session entry, so every terminal the user opens
 * keeps its process until it is restarted from inside or the app itself goes away. Opening a
 * second session therefore never kills the first: the UI switches which stored session the
 * `TerminalView` is attached to, and each live session remains a real process tree of its own.
 */
object TerminalHost : TerminalHostCallbacks {

    private const val TAG = "TerminalHost"

    /** Everything needed to start a session, kept so an exited one can be restarted. */
    data class Spec(
        val workspace: String?,
        val initialCommand: String,
        val title: String,
    )

    /** A stored session and the bridge that drives it. */
    private class Entry(
        val session: TerminalSession,
        val bridge: TerminalBridge,
        val spec: Spec,
    )

    /**
     * Bumped whenever the session the UI should display changes — started, exited, or switched.
     * The UI keys its `TerminalView` attachment off this, because one `TerminalView` can only
     * follow one session and must be told when that session is replaced.
     */
    private val _generation = MutableStateFlow(0L)
    val generation: StateFlow<Long> = _generation.asStateFlow()

    /** True once the displayed session's process has exited, so the UI can offer a restart. */
    private val _exited = MutableStateFlow(false)
    val exited: StateFlow<Boolean> = _exited.asStateFlow()

    /** The displayed session's current title, usually set by the shell through OSC escape sequences. */
    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    /**
     * The terminal font size, shared by every session so zooming one terminal zooms them all, and
     * kept here rather than in Compose state so it survives navigating away and back.
     */
    private val _fontSize = MutableStateFlow(DEFAULT_FONT_SP)
    val fontSize: StateFlow<Int> = _fontSize.asStateFlow()

    /** All stored sessions, keyed by "folderId/sessionId". */
    private val entries = LinkedHashMap<String, Entry>()

    private var currentKey: String? = null
    private var bridge: TerminalBridge? = null

    /**
     * The `TerminalView` currently displaying the session, if any.
     *
     * The session produces output on a background thread and only notifies its client; something
     * has to turn that notification into a redraw. The view registers itself here, and
     * [onScreenUpdate] is what the UI uses to invalidate it.
     */
    @Volatile
    var currentView: com.termux.view.TerminalView? = null

    /**
     * UI hooks. They are functions rather than direct references because the UI can be detached
     * while the shell keeps running, and a detached terminal must not be written to.
     */
    var onScreenUpdate: (() -> Unit)? = null
    var onTap: (() -> Unit)? = null
    var onBackPressed: (() -> Unit)? = null

    /** Ctrl+Shift+F in the terminal: the screen opens its find bar. */
    var onFindRequested: (() -> Unit)? = null

    /** Terminal text sizes, in sp. */
    const val MIN_FONT_SP = 7
    const val MAX_FONT_SP = 28
    const val DEFAULT_FONT_SP = 13

    val session: TerminalSession? get() = entries[currentKey]?.session

    val currentBridge: TerminalBridge? get() = bridge

    /** A stored shell as the notification watcher sees it. */
    data class LiveSession(
        val key: String,
        val title: String,
        val folderLabel: String,
        val running: Boolean,
        val working: Boolean,
    )

    fun liveSessions(): List<LiveSession> = entries.map { (key, entry) ->
        LiveSession(
            key = key,
            title = entry.spec.title,
            folderLabel = entry.spec.workspace?.substringAfterLast('/').orEmpty(),
            running = entry.session.isRunning,
            working = entry.session.isRunning && entry.bridge.isWorking,
        )
    }

    /**
     * The window that owns the terminal display. The main window and the bubble can both have a
     * terminal screen composed, but only one of them is on screen; the one that resumes last
     * claims the display so the output is drawn where the user is looking.
     */
    @Volatile
    var displayOwner: Any? = null
        private set

    fun claimDisplay(owner: Any) {
        if (displayOwner === owner) return
        displayOwner = owner
        // Recreates the terminal views, and only the owner's registers itself as currentView.
        _generation.value += 1
    }

    /** Whether the stored session for [key] is running something right now (see TerminalBridge). */
    fun isWorking(key: String): Boolean {
        val entry = entries[key] ?: return false
        return entry.session.isRunning && entry.bridge.isWorking
    }

    /** The host key under which a folder's session entry is stored. */
    fun sessionKey(folderId: String, sessionId: String): String = "$folderId/$sessionId"

    /**
     * Ends the session stored for [key] and forgets it, releasing its PRoot process tree so an
     * idle shell stops consuming memory. The next open of this session starts a fresh one.
     */
    fun closeSession(key: String) {
        destroy(key)
    }

    /**
     * Makes the session for [key] the displayed one, starting a fresh process only when none is
     * stored yet or the stored one has died. Cheap when the terminal is already up, so it is safe
     * to call from a Composable effect.
     */
    fun ensureStarted(context: Context, key: String, spec: Spec) {
        val entry = entries[key]
        if (entry != null && entry.session.isRunning && entry.spec == spec) {
            if (currentKey != key) switchTo(key)
            return
        }
        // Nothing usable is stored: replace an exited (or stale-spec) entry with a fresh process.
        destroy(key)
        open(context.applicationContext, key, spec)
    }

    /** Points the UI at an already-stored live session without touching any process. */
    private fun switchTo(key: String) {
        val entry = entries[key] ?: return
        currentKey = key
        bridge = entry.bridge
        _exited.value = !entry.session.isRunning
        _title.value = entry.spec.title
        _generation.value += 1
        Log.i(TAG, "Attached '${entry.spec.title}' (${entries.size} stored)")
    }

    private fun open(context: Context, key: String, spec: Spec) {
        val client = TerminalBridge(context, this)
        val started = ShellSessions.start(
            context = context,
            client = client,
            workspace = spec.workspace,
            initialCommand = spec.initialCommand,
            sessionName = spec.title,
        )

        entries[key] = Entry(started, client, spec)
        currentKey = key
        bridge = client
        _exited.value = false
        _title.value = spec.title
        _generation.value += 1
        Log.i(TAG, "Started '${spec.title}' workspace=${spec.workspace}")
    }

    /** Ends one stored session and forgets it, which also ends its PRoot process tree. */
    private fun destroy(key: String) {
        val entry = entries.remove(key) ?: return
        runCatching { entry.session.finishIfRunning() }
            .onFailure { Log.w(TAG, "Could not finish the session cleanly", it) }
        if (currentKey == key) {
            currentKey = null
            bridge = null
        }
    }

    /**
     * The colour scheme changed: every stored session goes back to the (new) default colours,
     * and the visible terminal repaints with the new background.
     */
    fun recolor() {
        entries.values.forEach { entry -> runCatching { entry.session.emulator?.mColors?.reset() } }
        currentView?.let { view ->
            view.setBackgroundColor(ShellTheme.windowBackground)
            view.onScreenUpdated()
        }
    }

    /** Ends the displayed session and starts a fresh one with the same spec. */
    fun restart(context: Context) {
        val key = currentKey ?: return
        val spec = entries[key]?.spec ?: return
        destroy(key)
        open(context.applicationContext, key, spec)
    }

    /** Sends raw text to the displayed shell, as if it had been typed. */
    fun write(text: String) {
        session?.write(text)
    }

    /**
     * Pastes the clipboard into the shell.
     *
     * Text goes through the emulator's own paste, which wraps it in bracketed-paste markers
     * whenever the program asked for them (bash, Claude Code, OpenCode and vim all do), so a
     * multi-line snippet arrives as one paste instead of being run line by line, and TUIs keep
     * its newlines.
     *
     * An image is saved into the guest and its path is typed instead: terminal agents take an
     * image by path, and there is no other way to hand one across a PTY.
     *
     * Returns false when the clipboard held nothing usable.
     */
    fun paste(context: Context): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val item = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return false
        val uri = item.uri
        if (uri != null && com.termfold.app.acp.PastedImages.isImage(context, uri)) {
            insertImage(context, uri)
            return true
        }
        val text = item.coerceToText(context)?.toString()?.takeIf { it.isNotEmpty() } ?: return false
        val emulator = session?.emulator
        if (emulator != null) emulator.paste(text) else write(text)
        return true
    }

    /**
     * Copies an image into the guest's /tmp and types its path at the cursor. Decoding happens
     * off the main thread; the path is typed when it is ready.
     */
    fun insertImage(context: Context, uri: android.net.Uri) {
        val app = context.applicationContext
        Thread {
            val encoded = com.termfold.app.acp.PastedImages.load(app, uri) ?: return@Thread
            val dir = java.io.File(ShellPaths.rootfsDir(app), "tmp/termfold")
            dir.mkdirs()
            val name = "image-${System.currentTimeMillis()}.${encoded.extension}"
            java.io.File(dir, name).writeBytes(encoded.bytes)
            // Delivered as a paste (bracketed), not typed: Codex and Claude Code turn a *pasted*
            // image path into an image attachment. The emulator is not thread-safe, hence main.
            val path = "/tmp/termfold/$name"
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val emulator = session?.emulator
                if (emulator != null) emulator.paste(path) else write("$path ")
            }
        }.start()
    }

    // --- TerminalHostCallbacks ------------------------------------------------------------------

    override fun terminalSession(): TerminalSession? = session

    override fun onScreenUpdated() {
        onScreenUpdate?.invoke()
    }

    override fun onTitleChanged(title: String) {
        _title.value = title
    }

    override fun onFontStep(steps: Int) {
        _fontSize.value = (_fontSize.value + steps).coerceIn(MIN_FONT_SP, MAX_FONT_SP)
    }

    override fun onTap() {
        onTap?.invoke()
    }

    override fun onBackPressed() {
        onBackPressed?.invoke()
    }

    /** Set by the app so the bell can reach the notification watcher without a dependency cycle. */
    var onBellRung: ((key: String, title: String) -> Unit)? = null

    override fun onBell(session: TerminalSession) {
        val (key, entry) = entries.entries.firstOrNull { it.value.session === session }?.toPair() ?: return
        onBellRung?.invoke(key, entry.spec.title)
    }

    override fun onSessionFinished(session: TerminalSession) {
        // Several sessions run side by side, so the exit only counts as *the* terminal's exit when
        // it happened to the session currently on screen. A background shell dying just sits in
        // its entry until the user next opens it, and is then replaced with a fresh process.
        if (entries[currentKey]?.session === session) {
            _exited.value = true
        }
    }
}
