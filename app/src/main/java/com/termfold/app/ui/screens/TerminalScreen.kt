package com.termfold.app.ui.screens

import android.content.Context
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
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
import com.termfold.app.ui.components.PhysicalKeyboard
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
    filesOpen: Boolean = false,
    onToggleFiles: () -> Unit = {},
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

    // The image key: the picked image is copied into the guest and its path typed at the cursor,
    // which is how terminal agents (Claude Code, OpenCode, Pi) take an image.
    val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) TerminalHost.insertImage(context, uri) }

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
        val tap = { requestTerminalFocus(terminalView, keyboard) }
        val back = { onBack() }
        host.onTap = tap
        host.onBackPressed = back
        onDispose {
            // The bubble and the main window can both have this screen; only clear our own hooks.
            if (host.onTap === tap) host.onTap = null
            if (host.onBackPressed === back) host.onBackPressed = null
            // The keyboard was raised for the terminal, so it leaves with it. Otherwise it stays
            // up over the folder screen and covers half of the session list.
            runCatching { keyboard?.hide() }
            terminalView?.let { view ->
                runCatching {
                    val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE)
                        as? android.view.inputmethod.InputMethodManager
                    imm?.hideSoftInputFromWindow(view.windowToken, 0)
                }
            }
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
            filesOpen = filesOpen,
            onToggleFiles = onToggleFiles,
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
                if (key == SpecialKey.IMAGE) {
                    pickImage.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                        ),
                    )
                    return@KeyRow
                }
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
                        SpecialKey.IMAGE -> Unit
                    }
                }
            },
        )
    }
}

/** Header: current folder, live terminal title, and the state of the shell. */
@Composable
private fun TerminalHeader(
    title: String,
    subtitle: String,
    exited: Boolean,
    filesOpen: Boolean,
    onToggleFiles: () -> Unit,
    onBack: () -> Unit,
    onRestart: () -> Unit,
    onFontSize: (Int) -> Unit,
) {
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
        FilesButton(open = filesOpen, onClick = onToggleFiles)
        Spacer(Modifier.size(4.dp))
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

/** One key of the extra-keys row. */
private data class ExtraKey(
    val label: String,
    val key: SpecialKey? = null,
    val primary: Boolean = false,
    /** Share of the row on wide screens, relative to a plain key. */
    val weight: Float = 1f,
    /** Keeps firing while held, like a hardware key (the arrows). */
    val repeats: Boolean = false,
)

/**
 * The extra keys a touch keyboard cannot provide, in two rows.
 *
 * The top row is modifiers and the keys that interrupt or complete a command; the bottom row is
 * editing and navigation. On a phone each row keeps its keys at a comfortable fixed size and
 * scrolls if it has to; on a tablet the keys stretch to share the full width, so they are large,
 * evenly spaced targets instead of a small strip in the middle of a wide screen.
 */
@Composable
private fun KeyRow(
    controlDown: Boolean,
    altDown: Boolean,
    onToggleControl: () -> Unit,
    onToggleAlt: () -> Unit,
    onKey: (SpecialKey) -> Unit,
) {
    val top = listOf(
        ExtraKey(stringResource(R.string.key_esc), SpecialKey.ESC),
        ExtraKey(stringResource(R.string.key_tab), SpecialKey.TAB),
        ExtraKey(stringResource(R.string.key_ctrl_c), SpecialKey.CTRL_C),
        ExtraKey(stringResource(R.string.key_ctrl_d), SpecialKey.CTRL_D),
        ExtraKey(stringResource(R.string.key_paste), SpecialKey.PASTE, weight = 1.2f),
        ExtraKey(stringResource(R.string.key_image), SpecialKey.IMAGE, weight = 1.2f),
        ExtraKey(stringResource(R.string.key_enter), SpecialKey.ENTER, primary = true, weight = 1.5f),
    )
    val bottom = listOf(
        ExtraKey(stringResource(R.string.key_home), SpecialKey.HOME),
        ExtraKey(stringResource(R.string.key_end), SpecialKey.END),
        ExtraKey(stringResource(R.string.key_pgup), SpecialKey.PAGE_UP),
        ExtraKey(stringResource(R.string.key_pgdn), SpecialKey.PAGE_DOWN),
        ExtraKey(stringResource(R.string.key_pipe), SpecialKey.PIPE, weight = 0.8f),
        ExtraKey(stringResource(R.string.key_slash), SpecialKey.SLASH, weight = 0.8f),
        ExtraKey(stringResource(R.string.key_dash), SpecialKey.DASH, weight = 0.8f),
        ExtraKey("←", SpecialKey.LEFT, weight = 0.9f, repeats = true),
        ExtraKey("↓", SpecialKey.DOWN, weight = 0.9f, repeats = true),
        ExtraKey("↑", SpecialKey.UP, weight = 0.9f, repeats = true),
        ExtraKey("→", SpecialKey.RIGHT, weight = 0.9f, repeats = true),
    )

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        val wide = maxWidth >= 600.dp
        val keyHeight = if (wide) 44.dp else 38.dp
        val gap = if (wide) 8.dp else 5.dp

        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            KeyLine(wide = wide, gap = gap) { stretch ->
                ModifierKey(
                    label = stringResource(R.string.key_ctrl),
                    active = controlDown,
                    height = keyHeight,
                    wide = wide,
                    onClick = onToggleControl,
                    modifier = stretch(1.2f),
                )
                ModifierKey(
                    label = stringResource(R.string.key_alt),
                    active = altDown,
                    height = keyHeight,
                    wide = wide,
                    onClick = onToggleAlt,
                    modifier = stretch(1.2f),
                )
                top.forEach { key ->
                    KeyButton(
                        label = key.label,
                        onClick = { key.key?.let(onKey) },
                        primary = key.primary,
                        height = keyHeight,
                        wide = wide,
                        modifier = stretch(key.weight),
                    )
                }
            }
            KeyLine(wide = wide, gap = gap) { stretch ->
                bottom.forEach { key ->
                    KeyButton(
                        label = key.label,
                        onClick = { key.key?.let(onKey) },
                        repeats = key.repeats,
                        height = keyHeight,
                        wide = wide,
                        modifier = stretch(key.weight),
                    )
                }
            }
        }
    }
}

/**
 * One row of keys. [content] receives a function that turns a weight into the modifier for a
 * key: a share of the full width on wide screens, nothing (natural size, scrolling row) on phones.
 */
@Composable
private fun KeyLine(
    wide: Boolean,
    gap: androidx.compose.ui.unit.Dp,
    content: @Composable androidx.compose.foundation.layout.RowScope.(stretch: (Float) -> Modifier) -> Unit,
) {
    if (wide) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content { weight -> Modifier.weight(weight) }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                content { Modifier }
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
    // Typing goes to the focused view either way; with a physical keyboard attached the
    // on-screen one would only cover the terminal.
    if (PhysicalKeyboard.inUse) return
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
    IMAGE,
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

/** Delay before a held key starts repeating, and the interval between repeats after that. */
private const val REPEAT_DELAY_MS = 380L
private const val REPEAT_INTERVAL_MS = 55L

/**
 * The shared key surface: a visible press state (a lighter fill and a slight sink) and a light
 * haptic tick, so a tap reads as a key press even when the terminal reacts a moment later.
 * [repeats] keys fire again and again while held.
 */
@Composable
private fun KeySurface(
    fill: androidx.compose.ui.graphics.Color,
    pressedFill: androidx.compose.ui.graphics.Color,
    height: androidx.compose.ui.unit.Dp,
    wide: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    repeats: Boolean = false,
    content: @Composable () -> Unit,
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = androidx.compose.animation.core.tween(70),
        label = "keyScale",
    )
    val currentOnClick by androidx.compose.runtime.rememberUpdatedState(onClick)

    Box(
        modifier = modifier
            .height(height)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(if (wide) 11.dp else 9.dp))
            .background(if (pressed) pressedFill else fill)
            .pointerInput(repeats) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        if (repeats) {
                            // Fire now, then keep firing while the finger stays down.
                            currentOnClick()
                            kotlinx.coroutines.coroutineScope {
                                val job = launch {
                                    kotlinx.coroutines.delay(REPEAT_DELAY_MS)
                                    while (true) {
                                        currentOnClick()
                                        kotlinx.coroutines.delay(REPEAT_INTERVAL_MS)
                                    }
                                }
                                tryAwaitRelease()
                                job.cancel()
                            }
                        } else {
                            if (tryAwaitRelease()) currentOnClick()
                        }
                        pressed = false
                    },
                )
            }
            .padding(horizontal = if (wide) 6.dp else 10.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun ModifierKey(
    label: String,
    active: Boolean,
    height: androidx.compose.ui.unit.Dp,
    wide: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    KeySurface(
        fill = if (active) Palette.Accent else Palette.Card,
        pressedFill = if (active) Palette.Accent.copy(alpha = 0.8f) else Palette.CardPressed,
        height = height,
        wide = wide,
        onClick = onClick,
        modifier = modifier,
    ) {
        KeyLabel(label, wide, if (active) Palette.OnAccent else Palette.Text)
    }
}

@Composable
private fun KeyButton(
    label: String,
    onClick: () -> Unit,
    height: androidx.compose.ui.unit.Dp,
    wide: Boolean,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    repeats: Boolean = false,
) {
    KeySurface(
        fill = if (primary) Palette.Accent else Palette.Card,
        pressedFill = if (primary) Palette.Accent.copy(alpha = 0.8f) else Palette.CardPressed,
        height = height,
        wide = wide,
        onClick = onClick,
        modifier = modifier,
        repeats = repeats,
    ) {
        KeyLabel(label, wide, if (primary) Palette.OnAccent else Palette.Text)
    }
}

@Composable
private fun KeyLabel(label: String, wide: Boolean, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = Mono,
            fontSize = if (wide) 14.sp else 12.sp,
        ),
        color = color,
        maxLines = 1,
    )
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
