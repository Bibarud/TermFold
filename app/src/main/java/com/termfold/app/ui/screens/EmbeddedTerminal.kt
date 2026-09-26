package com.termfold.app.ui.screens

import android.annotation.SuppressLint
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.view.TerminalView
import com.termfold.app.shell.ShellTheme
import com.termfold.app.shell.TerminalHost
import com.termfold.app.ui.theme.Palette

/**
 * The live terminal surface.
 *
 * `TerminalView` is a classic Android `View`, so it is hosted through `AndroidView`. Three details
 * matter here:
 *
 *  - a `TerminalView` follows exactly one session, so the view is recreated whenever
 *    [TerminalHost.generation] changes, which is precisely when the session is replaced;
 *  - the session itself is owned by [TerminalHost], so rotating the device or navigating away and
 *    back re-attaches to the running shell rather than starting a new one;
 *  - the session writes its output from a background thread, so the view has to be told to redraw
 *    whenever the session says the screen changed. Without that the terminal renders its first
 *    frame and then appears frozen.
 *
 * [onReady] hands the live view back to the caller, which is how the surrounding screen raises the
 * soft keyboard on the terminal rather than on some other focusable.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun EmbeddedTerminal(
    modifier: Modifier = Modifier,
    fontSizeSp: Int,
    onReady: (TerminalView) -> Unit = {},
    highlights: List<TermMatch> = emptyList(),
    currentHighlight: Int = -1,
) {
    var liveView by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<TerminalView?>(null) }
    val generation by TerminalHost.generation.collectAsStateWithLifecycle()
    val theme by ShellTheme.current.collectAsStateWithLifecycle()
    val themeBackground = androidx.compose.ui.graphics.Color(theme.background)
    // The window this terminal is in. The main window and the bubble can both show a terminal;
    // whichever is resumed claims the display (see TerminalHost.claimDisplay).
    val owner = androidx.compose.ui.platform.LocalContext.current.findActivity()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && owner != null) TerminalHost.claimDisplay(owner)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun ownsDisplay() = TerminalHost.displayOwner == null || TerminalHost.displayOwner === owner

    // Recreating the view on a generation change is what lets a new session be displayed: an
    // existing view cannot be repointed at a different session reliably.
    key(generation) {
      // The gutter is drawn in the same background colour, so the shell content sits with
      // comfortable side padding instead of touching the screen edges.
      Box(modifier = modifier.fillMaxSize().background(themeBackground)) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            factory = { context ->
                TerminalView(context, null).apply {
                    setBackgroundColor(ShellTheme.windowBackground)

                    // JetBrains Mono has the box-drawing glyphs TUIs draw their frames with, and
                    // reads far better than the platform monospace. It ships as a font resource
                    // (res/font), not an asset; loading it from assets failed silently and left
                    // the terminal on the system font.
                    //
                    // The size must be set first: setTextSize is what creates the view's text
                    // renderer, and setTypeface reads the size back from it (it throws a
                    // NullPointerException otherwise).
                    setTextSize(spToPx(context, fontSizeSp))
                    val mono = runCatching {
                        context.resources.getFont(com.termfold.app.R.font.jetbrains_mono)
                    }.getOrNull() ?: Typeface.MONOSPACE
                    setTypeface(mono)
                    isFocusable = true
                    isFocusableInTouchMode = true

                    TerminalHost.currentBridge?.let { setTerminalViewClient(it) }
                    TerminalHost.session?.let { attachSession(it) }
                    if (ownsDisplay()) TerminalHost.currentView = this
                    liveView = this
                    onReady(this)
                }
            },
            // The host keeps a static reference to the view it draws into; drop it with the
            // view, or a closed bubble or main window would stay in memory behind it.
            onRelease = { view ->
                if (TerminalHost.currentView === view) TerminalHost.currentView = null
            },
            update = { view ->
                // `update` also runs after a configuration change, so the client and session are
                // re-checked rather than assumed.
                TerminalHost.currentBridge?.let { view.setTerminalViewClient(it) }
                TerminalHost.session?.let { view.attachSession(it) }
                view.setTextSize(spToPx(view.context, fontSizeSp))
                if (ownsDisplay()) TerminalHost.currentView = view
                liveView = view
                onReady(view)
            },
        )
        // Search matches, drawn over the text in the same padded box as the terminal.
        TerminalHighlights(
            view = liveView,
            matches = highlights,
            current = currentHighlight,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
      }
    }

    // The redraw hook. The session writes from a background thread and only notifies its client,
    // so this is what turns that notification into an actual repaint. It reads the view out of the
    // host rather than capturing one, so it stays correct across recomposition, and it is cleared
    // on dispose so a detached terminal is never asked to draw.
    DisposableEffect(generation) {
        val host = TerminalHost
        val hook = { host.currentView?.onScreenUpdated() ?: Unit }
        host.onScreenUpdate = hook
        // The other window may have registered its own hook since; leave that one alone.
        onDispose { if (host.onScreenUpdate === hook) host.onScreenUpdate = null }
    }
}

/**
 * `TerminalView.setTextSize` takes pixels, not sp. Passing the sp value straight through made
 * the text roughly half its intended size on a high-density tablet.
 */
private fun android.content.Context.findActivity(): android.app.Activity? {
    var c: android.content.Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}

private fun spToPx(context: android.content.Context, sp: Int): Int =
    android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_SP,
        sp.toFloat(),
        context.resources.displayMetrics,
    ).toInt().coerceAtLeast(1)
