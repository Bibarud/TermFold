package com.termfold.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termfold.app.R
import com.termfold.app.ui.theme.Mono
import com.termfold.app.ui.theme.Palette
import com.termfold.app.ui.theme.TermFoldIcons
import com.termux.view.TerminalView

/** One occurrence of the search text: a terminal row (negative = scrollback) and column. */
data class TermMatch(val row: Int, val col: Int, val length: Int)

/**
 * Every occurrence of [query] in the session's scrollback and screen, oldest first, ignoring
 * case. Must run on the main thread: the emulator's buffer is written there.
 */
fun findInTerminal(view: TerminalView, query: String): List<TermMatch> {
    if (query.isBlank()) return emptyList()
    val screen = view.mEmulator?.screen ?: return emptyList()
    val history = screen.activeTranscriptRows
    // One line per terminal row, wrapped rows kept apart, so line i is row (i - history).
    val lines = screen.transcriptTextWithoutJoinedLines.split('\n')
    val needle = query.lowercase()
    val out = ArrayList<TermMatch>()
    lines.forEachIndexed { i, line ->
        val hay = line.lowercase()
        var from = hay.indexOf(needle)
        while (from >= 0 && out.size < MAX_MATCHES) {
            out += TermMatch(row = i - history, col = from, length = needle.length)
            from = hay.indexOf(needle, from + needle.length)
        }
    }
    return out
}

private const val MAX_MATCHES = 5000

/** Scrolls so [match] sits in the upper third of the terminal. */
fun scrollToMatch(view: TerminalView, match: TermMatch) {
    val emulator = view.mEmulator ?: return
    val history = emulator.screen.activeTranscriptRows
    val target = (match.row - emulator.mRows / 3).coerceIn(-history, 0)
    view.topRow = target
    view.invalidate()
}

/** Back to the live bottom of the terminal. */
fun scrollToBottom(view: TerminalView) {
    view.topRow = 0
    view.invalidate()
}

/**
 * Highlights drawn over the terminal: every visible match softly, the current one strongly.
 * The terminal scrolls on its own (touch, new output), so the top row is re-read every frame
 * while there is anything to draw.
 */
@Composable
fun TerminalHighlights(view: TerminalView?, matches: List<TermMatch>, current: Int, modifier: Modifier = Modifier) {
    if (view == null || matches.isEmpty()) return
    var topRow by remember { mutableIntStateOf(view.topRow) }
    LaunchedEffect(view, matches) {
        while (true) {
            withFrameNanos { }
            if (view.topRow != topRow) topRow = view.topRow
        }
    }
    val renderer = view.mRenderer ?: return
    val emulator = view.mEmulator ?: return
    val fontWidth = renderer.fontWidth
    val lineHeight = renderer.fontLineSpacing.toFloat()
    val rows = emulator.mRows
    val soft = Palette.Accent.copy(alpha = 0.28f)
    val strong = Palette.Accent.copy(alpha = 0.55f)
    Canvas(modifier.fillMaxSize()) {
        matches.forEachIndexed { index, m ->
            val visibleRow = m.row - topRow
            if (visibleRow < 0 || visibleRow >= rows) return@forEachIndexed
            val topLeft = Offset(m.col * fontWidth, visibleRow * lineHeight)
            val size = Size(m.length * fontWidth, lineHeight)
            drawRoundRect(if (index == current) strong else soft, topLeft, size, CornerRadius(3f, 3f))
            if (index == current) {
                drawRoundRect(Palette.Accent, topLeft, size, CornerRadius(3f, 3f), style = Stroke(width = 2f))
            }
        }
    }
}

/**
 * The find bar under the terminal's header. Enter goes to the next match up (older output),
 * Shift+Enter back down, Esc closes, as in desktop terminals.
 */
@Composable
fun TerminalFindBar(
    state: TextFieldState,
    count: Int,
    current: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Card)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TermFoldIcons.Search, null, tint = Palette.TextFaint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
            if (state.text.isEmpty()) {
                Text(
                    stringResource(R.string.terminal_find_hint),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono, fontSize = 13.sp),
                    color = Palette.TextFaint,
                )
            }
            BasicTextField(
                state = state,
                lineLimits = TextFieldLineLimits.SingleLine,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono, fontSize = 13.sp, color = Palette.Text),
                cursorBrush = SolidColor(Palette.Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Enter, Key.NumPadEnter -> { if (event.isShiftPressed) onNext() else onPrevious(); true }
                            Key.Escape -> { onClose(); true }
                            Key.DirectionUp -> { onPrevious(); true }
                            Key.DirectionDown -> { onNext(); true }
                            else -> false
                        }
                    },
            )
        }
        Text(
            text = when {
                state.text.isEmpty() -> ""
                count == 0 -> stringResource(R.string.terminal_find_none)
                else -> stringResource(R.string.terminal_find_count, current + 1, count)
            },
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono),
            color = if (count == 0 && state.text.isNotEmpty()) Palette.Pink else Palette.TextDim,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        FindAction(TermFoldIcons.ChevronUp, stringResource(R.string.terminal_find_previous), enabled = count > 0, onClick = onPrevious)
        FindAction(TermFoldIcons.ChevronDown, stringResource(R.string.terminal_find_next), enabled = count > 0, onClick = onNext)
        FindAction(TermFoldIcons.Close, stringResource(R.string.action_close), enabled = true, onClick = onClose)
    }
}

@Composable
private fun FindAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (enabled) Palette.TextDim else Palette.TextFaint, modifier = Modifier.size(17.dp))
    }
}
