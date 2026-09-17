package com.termfold.app.ui.screens

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termfold.app.R
import com.termfold.app.core.Folder
import com.termfold.app.shell.TerminalHost
import com.termfold.app.ui.components.BareIconButton
import com.termfold.app.ui.components.StatusDot
import com.termfold.app.ui.theme.Mono
import com.termfold.app.ui.theme.Palette
import com.termfold.app.ui.theme.TermFoldIcons

/**
 * The terminal screen: a real Ubuntu shell rendered in-app.
 *
 * The shell is a full PTY running inside the bundled Linux environment, so full-screen TUIs such
 * as OpenCode and Pi work here directly. Nothing is handed off to another app.
 */
@Composable
fun TerminalScreen(
    folder: Folder,
    sessionId: String,
    sessionName: String,
    initialCommand: String,
    onBack: () -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val exited by TerminalHost.exited.collectAsStateWithLifecycle()
    val liveTitle by TerminalHost.title.collectAsStateWithLifecycle()
    val fontSize by TerminalHost.fontSize.collectAsStateWithLifecycle()
    val generation by TerminalHost.generation.collectAsStateWithLifecycle()

    var controlDown by remember { mutableStateOf(false) }
    var altDown by remember { mutableStateOf(false) }
    var terminalView by remember { mutableStateOf<com.termux.view.TerminalView?>(null) }

    val keyboard = LocalSoftwareKeyboardController.current

    // Start (or adopt) the session for this folder+session pair. The spec is stable for a given
    // entry, so returning to a live session reuses it rather than restarting PRoot. Keying by the
    // pair is what lets several sessions of one folder keep their shells side by side.
    val sessionKey = TerminalHost.sessionKey(folder.id, sessionId)
    val spec = remember(folder.id, folder.path, initialCommand, sessionName) {
        TerminalHost.Spec(
            workspace = folder.path.takeIf { it.isNotBlank() },
            initialCommand = initialCommand,
            title = sessionName,
        )
    }

    LaunchedEffect(sessionKey, spec) {
        TerminalHost.ensureStarted(context, sessionKey, spec)
    }

    // Raise the keyboard as soon as the terminal surface exists — on a phone there is no other
    // way to type, and expecting a tap on the terminal first reads as "the keyboard is broken".
    // Re-runs when the displayed session is replaced, and the short delay lets the screen
    // transition finish so the IME is not raised over a screen that is still sliding in.
    LaunchedEffect(sessionKey, generation, terminalView) {
        val view = terminalView ?: return@LaunchedEffect
        kotlinx.coroutines.delay(350)
        requestTerminalFocus(view, keyboard)
    }

    // The UI hooks are re-registered on every entry, because the previous screen may have left
    // them pointing at a Composable that no longer exists.
    DisposableEffect(sessionKey) {
        val host = TerminalHost
        host.onTap = { requestTerminalFocus(terminalView, keyboard) }
        host.onBackPressed = { onBack() }
        onDispose {
            host.onTap = null
            host.onBackPressed = null
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start))
            .imePadding(),
    ) {
        TerminalHeader(
            title = liveTitle.ifBlank { sessionName },
            subtitle = folder.name,
            exited = exited,
            onBack = onBack,
            onRestart = onRestart,
            onFontSize = { steps -> TerminalHost.onFontStep(steps) },
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            EmbeddedTerminal(
                modifier = Modifier.fillMaxSize(),
                fontSizeSp = fontSize,
                onReady = { view -> terminalView = view },
            )
        }

        KeyRow(
            controlDown = controlDown,
            altDown = altDown,
            onToggleControl = {
                controlDown = !controlDown
                TerminalHost.currentBridge?.setControlKey(controlDown)
            },
            onToggleAlt = {
                altDown = !altDown
                TerminalHost.currentBridge?.setAltKey(altDown)
            },
            onKey = { key ->
                TerminalHost.currentBridge?.let { bridge ->
                    when (key) {
                        SpecialKey.ESC -> bridge.sendEscape()
                        SpecialKey.TAB -> bridge.sendTab()
                        SpecialKey.ENTER -> bridge.sendEnter()
                        SpecialKey.CTRL_C -> bridge.sendControl('C')
                        SpecialKey.CTRL_D -> bridge.sendControl('D')
                        SpecialKey.PASTE -> TerminalHost.paste(context)
                        SpecialKey.PIPE -> bridge.sendSequence("|")
                        SpecialKey.DASH -> bridge.sendSequence("-")
                        SpecialKey.SLASH -> bridge.sendSequence("/")
                        // Cursor and paging keys are ordinary VT100 escape sequences.
                        SpecialKey.UP -> bridge.sendSequence("\u001b[A")
                        SpecialKey.DOWN -> bridge.sendSequence("\u001b[B")
                        SpecialKey.RIGHT -> bridge.sendSequence("\u001b[C")
                        SpecialKey.LEFT -> bridge.sendSequence("\u001b[D")
                        SpecialKey.HOME -> bridge.sendSequence("\u001b[H")
                        SpecialKey.END -> bridge.sendSequence("\u001b[F")
                        SpecialKey.PAGE_UP -> bridge.sendSequence("\u001b[5~")
                        SpecialKey.PAGE_DOWN -> bridge.sendSequence("\u001b[6~")
                    }
                }
            },
        )
    }
}

/** Header: current folder, live terminal title, and the state of the shell. */
@Composable
private fun TerminalHeader(    title: String,
    subtitle: String,
    exited: Boolean,
    onBack: () -> Unit,
    onRestart: () -> Unit,
    onFontSize: (Int) -> Unit,
) {
    // The dot is the one glanceable "is my shell alive" signal, so its change is animated.
    val dotColor by animateColorAsState(
        targetValue = if (exited) Palette.Pink else Palette.Green,
        animationSpec = tween(250),
        label = "statusDot",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BareIconButton(
            icon = TermFoldIcons.Back,
            contentDescription = stringResource(R.string.cd_back),
            onClick = onBack,
        )
        Spacer(Modifier.size(4.dp))
        StatusDot(dotColor)
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Palette.Text,
                maxLines = 1,
            )
            Text(
                text = if (exited) {
                    stringResource(R.string.terminal_exited)
                } else {
                    subtitle
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (exited) Palette.Pink else Palette.TextFaint,
                maxLines = 1,
            )
        }
        PillButton(
            label = "\u2212",
            onClick = { onFontSize(-1) },
            modifier = Modifier.padding(end = 4.dp),
        )
        PillButton(
            label = "+",
            onClick = { onFontSize(1) },
            modifier = Modifier.padding(end = 4.dp),
        )
        if (exited) {
            PillButton(
                label = stringResource(R.string.action_restart),
                onClick = onRestart,
            )
        } else {
            PillButton(
                label = stringResource(R.string.action_reset),
                onClick = onRestart,
            )
        }
    }
}

/**
 * The extra keys a phone keyboard cannot provide, in two rows.
 *
 * The top row is modifiers and the keys that interrupt or complete a command; the bottom row is
 * editing and navigation. They are kept separate because a single row of this many keys would
 * either be too small to hit or would push the terminal off screen.
 *
 * Each row is centred in the width it has: while the keys fit they sit in the middle rather than
 * hugging the left edge with a dead zone on the right, and once they no longer fit the row simply
 * scrolls.
 */
@Composable
private fun KeyRow(
    controlDown: Boolean,
    altDown: Boolean,
    onToggleControl: () -> Unit,
    onToggleAlt: () -> Unit,
    onKey: (SpecialKey) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModifierKey(
                    label = stringResource(R.string.key_ctrl),
                    active = controlDown,
                    onClick = onToggleControl,
                )
                ModifierKey(
                    label = stringResource(R.string.key_alt),
                    active = altDown,
                    onClick = onToggleAlt,
                )
                KeyButton(stringResource(R.string.key_esc), onClick = { onKey(SpecialKey.ESC) })
                KeyButton(stringResource(R.string.key_tab), onClick = { onKey(SpecialKey.TAB) })
                KeyButton(stringResource(R.string.key_ctrl_c), onClick = { onKey(SpecialKey.CTRL_C) })
                KeyButton(stringResource(R.string.key_ctrl_d), onClick = { onKey(SpecialKey.CTRL_D) })
                KeyButton(stringResource(R.string.key_paste), onClick = { onKey(SpecialKey.PASTE) })
                KeyButton(
                    label = stringResource(R.string.key_enter),
                    onClick = { onKey(SpecialKey.ENTER) },
                    primary = true,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KeyButton(stringResource(R.string.key_home), onClick = { onKey(SpecialKey.HOME) })
                KeyButton(stringResource(R.string.key_end), onClick = { onKey(SpecialKey.END) })
                KeyButton(stringResource(R.string.key_pgup), onClick = { onKey(SpecialKey.PAGE_UP) })
                KeyButton(stringResource(R.string.key_pgdn), onClick = { onKey(SpecialKey.PAGE_DOWN) })
                KeyButton(stringResource(R.string.key_pipe), onClick = { onKey(SpecialKey.PIPE) })
                KeyButton(stringResource(R.string.key_slash), onClick = { onKey(SpecialKey.SLASH) })
                KeyButton(stringResource(R.string.key_dash), onClick = { onKey(SpecialKey.DASH) })
                KeyButton("\u2190", onClick = { onKey(SpecialKey.LEFT) })
                KeyButton("\u2193", onClick = { onKey(SpecialKey.DOWN) })
                KeyButton("\u2191", onClick = { onKey(SpecialKey.UP) })
                KeyButton("\u2192", onClick = { onKey(SpecialKey.RIGHT) })
            }
        }
    }
}

/**
 * Moves input focus to the terminal and raises the keyboard.
 *
 * The soft keyboard follows the focused view, and the focusable here is the `TerminalView`, which
 * is created outside Compose. So focus is requested on the View directly rather than through the
 * focus system: that is what tells `TerminalView` to return an input connection and therefore
 * what makes the IME appear. The Compose controller is asked first, and the platform input
 * manager is asked directly as a fallback, because either alone can silently do nothing
 * depending on what currently holds focus.
 */
private fun requestTerminalFocus(
    view: com.termux.view.TerminalView?,
    keyboard: androidx.compose.ui.platform.SoftwareKeyboardController?,
) {
    if (view == null) return
    view.requestFocus()
    runCatching { keyboard?.show() }
    runCatching {
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        imm?.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }
}

/** Every key the on-screen row can send. */
private enum class SpecialKey {    ESC,
    TAB,
    ENTER,
    CTRL_C,
    CTRL_D,
    PASTE,
    UP,
    DOWN,
    LEFT,
    RIGHT,
    HOME,
    END,
    PAGE_UP,
    PAGE_DOWN,
    PIPE,
    DASH,
    SLASH,
}

@Composable
private fun ModifierKey(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (active) Palette.Accent else Palette.Card)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono),
            color = if (active) Palette.OnAccent else Palette.Text,
            maxLines = 1,
        )
    }
}

@Composable
private fun KeyButton(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (primary) Palette.Accent else Palette.Card)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono),
            color = if (primary) Palette.OnAccent else Palette.Text,
            maxLines = 1,
        )
    }
}

@Composable
private fun PillButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Card)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Palette.Text,
        )
    }
}
