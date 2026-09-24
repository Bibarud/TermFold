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
) {
    val generation by TerminalHost.generation.collectAsStateWithLifecycle()

    // Recreating the view on a generation change is what lets a new session be displayed: an
    // existing view cannot be repointed at a different session reliably.
    key(generation) {
        AndroidView(
            modifier = modifier
                .fillMaxSize()
                // The gutter is drawn in the same background colour, so the shell content sits
                // with comfortable side padding instead of touching the screen edges.
                .background(Palette.TermBg)
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
                    TerminalHost.currentView = this
                    onReady(this)
                }
            },
            update = { view ->
                // `update` also runs after a configuration change, so the client and session are
                // re-checked rather than assumed.
                TerminalHost.currentBridge?.let { view.setTerminalViewClient(it) }
                TerminalHost.session?.let { view.attachSession(it) }
                view.setTextSize(spToPx(view.context, fontSizeSp))
                TerminalHost.currentView = view
                onReady(view)
            },
        )
    }

    // The redraw hook. The session writes from a background thread and only notifies its client,
    // so this is what turns that notification into an actual repaint. It reads the view out of the
    // host rather than capturing one, so it stays correct across recomposition, and it is cleared
    // on dispose so a detached terminal is never asked to draw.
    DisposableEffect(generation) {
        val host = TerminalHost
        host.onScreenUpdate = {
            host.currentView?.onScreenUpdated()
        }
        onDispose { host.onScreenUpdate = null }
    }
}

/**
 * `TerminalView.setTextSize` takes pixels, not sp. Passing the sp value straight through made
 * the text roughly half its intended size on a high-density tablet.
 */
private fun spToPx(context: android.content.Context, sp: Int): Int =
    android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_SP,
        sp.toFloat(),
        context.resources.displayMetrics,
    ).toInt().coerceAtLeast(1)
