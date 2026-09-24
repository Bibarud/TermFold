package com.termfold.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termfold.app.ui.theme.Mono
import com.termfold.app.ui.theme.Palette
import com.termfold.app.ui.theme.TermFoldIcons

/**
 * Renders the Markdown agents write: headings, paragraphs, lists, quotes, rules, fenced code and
 * the common inline marks. Deliberately small — no HTML, no nested block quotes — because the
 * text arrives in streamed chunks and has to render sensibly mid-stream, including a code fence
 * whose closing ``` has not arrived yet.
 */
@Composable
fun Markdown(
    text: String,
    modifier: Modifier = Modifier,
    /** Body colour; the agent's reasoning renders dimmer than its reply. */
    color: androidx.compose.ui.graphics.Color = Palette.Text,
    /** Colour of list markers and quotes, one step quieter than [color]. */
    secondary: androidx.compose.ui.graphics.Color = Palette.TextDim,
    /** Base text style; headings and lists scale from its size. */
    bodyStyle: androidx.compose.ui.text.TextStyle? = null,
) {
    val blocks = remember(text) { parseMarkdown(text) }
    val body = bodyStyle ?: MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    text = inline(block.text),
                    // Chat headings stay close to body size; the app's display sizes would shout.
                    // A custom body style (the reasoning view) keeps headings at its own size.
                    style = when {
                        bodyStyle != null -> body.copy(fontWeight = FontWeight.SemiBold)
                        block.level == 1 -> MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp, lineHeight = 26.sp)
                        block.level == 2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.labelLarge
                    },
                    color = color,
                    modifier = Modifier.padding(top = if (block.level <= 2) 6.dp else 2.dp),
                )

                is MdBlock.Paragraph -> Text(
                    text = inline(block.text),
                    style = body,
                    color = color,
                )

                is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEachIndexed { index, item ->
                        Row(modifier = Modifier.padding(start = (item.indent * 14).dp)) {
                            Text(
                                text = if (block.ordered) "${block.start + index}." else "•",
                                style = body,
                                color = secondary,
                                modifier = Modifier.widthIn(min = if (block.ordered) 22.dp else 14.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = inline(item.text),
                                style = body,
                                color = color,
                            )
                        }
                    }
                }

                is MdBlock.Quote -> Row {
                    Box(
                        Modifier
                            .width(2.dp)
                            .height(22.dp)
                            .background(Palette.Border),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = inline(block.text),
                        style = body,
                        color = secondary,
                    )
                }

                is MdBlock.Code -> CodeBlock(language = block.language, code = block.code)

                MdBlock.Rule -> Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .height(1.dp)
                        .background(Palette.BorderSoft),
                )
            }
        }
    }
}

/** A fenced code block: monospace, horizontally scrollable, with a copy action. */
@Composable
fun CodeBlock(language: String, code: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.Card)
            .border(1.dp, Palette.BorderSoft, RoundedCornerShape(10.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = language.ifBlank { "code" },
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono),
                color = Palette.TextFaint,
                modifier = Modifier.weight(1f),
            )
            BareIconButton(
                icon = TermFoldIcons.Copy,
                contentDescription = "Copy code",
                onClick = { clipboard.setText(AnnotatedString(code)) },
                size = 32,
                tint = Palette.TextFaint,
            )
        }
        Text(
            text = code,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono, fontSize = 12.5.sp, lineHeight = 18.sp),
            color = Palette.TermOut,
            softWrap = false,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        )
    }
}

internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<ListItem>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data object Rule : MdBlock
}

internal data class ListItem(val indent: Int, val text: String)

private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val ORDERED = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
private val RULE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")

/** Splits Markdown into blocks. Tables and other unknowns stay as their literal lines. */
internal fun parseMarkdown(source: String): List<MdBlock> {
    val lines = source.replace("\r\n", "\n").split('\n')
    val blocks = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()
    var list: MutableList<ListItem>? = null
    var listOrdered = false
    var listStart = 1

    fun flushParagraph() {
        if (paragraph.isNotBlank()) blocks += MdBlock.Paragraph(paragraph.toString().trim())
        paragraph.clear()
    }

    fun flushList() {
        list?.let { if (it.isNotEmpty()) blocks += MdBlock.ListBlock(listOrdered, listStart, it) }
        list = null
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()

        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            flushParagraph(); flushList()
            val fence = trimmed.take(3)
            val language = trimmed.drop(3).trim()
            val code = StringBuilder()
            i++
            // An unclosed fence (still streaming) runs to the end of the text.
            while (i < lines.size && !lines[i].trimStart().startsWith(fence)) {
                code.appendLine(lines[i])
                i++
            }
            blocks += MdBlock.Code(language, code.toString().trimEnd('\n'))
            i++
            continue
        }

        if (line.isBlank()) {
            flushParagraph(); flushList()
            i++
            continue
        }

        val heading = HEADING.matchEntire(trimmed)
        if (heading != null) {
            flushParagraph(); flushList()
            blocks += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trimEnd('#', ' '))
            i++
            continue
        }

        if (RULE.matches(line)) {
            flushParagraph(); flushList()
            blocks += MdBlock.Rule
            i++
            continue
        }

        val bullet = BULLET.matchEntire(line)
        val ordered = ORDERED.matchEntire(line)
        if (bullet != null || ordered != null) {
            flushParagraph()
            val isOrdered = bullet == null
            if (list == null || listOrdered != isOrdered) {
                flushList()
                list = mutableListOf()
                listOrdered = isOrdered
                listStart = ordered?.groupValues?.get(2)?.toIntOrNull() ?: 1
            }
            val indent = ((bullet ?: ordered)!!.groupValues[1].length / 2).coerceAtMost(4)
            val body = if (bullet != null) bullet.groupValues[2] else ordered!!.groupValues[3]
            list!!.add(ListItem(indent, body))
            i++
            continue
        }

        if (trimmed.startsWith(">")) {
            flushParagraph(); flushList()
            blocks += MdBlock.Quote(trimmed.removePrefix(">").trim())
            i++
            continue
        }

        // A continuation line of a list item joins that item; anything else joins the paragraph.
        val current = list
        if (current != null && line.startsWith("  ") && current.isNotEmpty()) {
            val last = current.removeAt(current.lastIndex)
            current.add(last.copy(text = last.text + " " + trimmed))
        } else {
            flushList()
            if (paragraph.isNotEmpty()) paragraph.append('\n')
            paragraph.append(line)
        }
        i++
    }
    flushParagraph(); flushList()
    return blocks
}

private val INLINE = Regex(
    "`([^`]+)`" +                              // code
        "|\\*\\*(.+?)\\*\\*|__(.+?)__" +        // bold
        "|(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?!\\*)|(?<![_\\w])_(?!\\s)(.+?)(?<!\\s)_(?![_\\w])" + // italic
        "|~~(.+?)~~" +                          // strike
        "|\\[([^\\]]+)]\\((\\S+?)\\)",           // link
)

/** Inline marks to an AnnotatedString. Marks do not nest, which is what agents almost never need. */
internal fun inline(text: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    INLINE.findAll(text).forEach { match ->
        append(text.substring(cursor, match.range.first))
        val g = match.groupValues
        when {
            g[1].isNotEmpty() -> withStyle(
                SpanStyle(fontFamily = Mono, fontSize = 13.sp, background = Palette.CardPressed, color = Palette.TermOut),
            ) { append(" ${g[1]} ") }

            g[2].isNotEmpty() || g[3].isNotEmpty() ->
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(g[2].ifEmpty { g[3] }) }

            g[4].isNotEmpty() || g[5].isNotEmpty() ->
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[4].ifEmpty { g[5] }) }

            g[6].isNotEmpty() ->
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(g[6]) }

            else -> withLink(
                LinkAnnotation.Url(
                    g[8],
                    TextLinkStyles(SpanStyle(color = Palette.Accent, textDecoration = TextDecoration.Underline)),
                ),
            ) { append(g[7]) }
        }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}
