package com.termfold.app.ui.theme

import androidx.compose.ui.graphics.Color

/** Flat token set so every surface stays one shade of near-black. */
object Palette {
    val Bg = Color(0xFF08080A)
    val Card = Color(0xFF141417)
    val CardPressed = Color(0xFF1C1C21)
    val NavBg = Color(0xFF141417)
    val Field = Color(0xFF16161A)
    val Border = Color(0xFF232328)
    val BorderSoft = Color(0xFF1D1D22)

    val Accent = Color(0xFFFF7A2E)
    val AccentSoft = Color(0x1FFF7A2E)

    val Text = Color(0xFFF4F4F6)
    val TextDim = Color(0xFF8B8B93)
    val TextFaint = Color(0xFF5A5A62)
    val OnAccent = Color(0xFF0C0C0E)

    // Status dot / activity colours, also reused as folder tints.
    val Green = Color(0xFF34D399)
    val Purple = Color(0xFFA78BFA)
    val Blue = Color(0xFF4E7CFF)
    val Yellow = Color(0xFFFBBF24)
    val Pink = Color(0xFFFB7185)

    val FolderTints = listOf(Accent, Blue, Green, Yellow, Purple, Pink)

    // Terminal surface.
    val TermOut = Color(0xFFD8D8DC)
}

/** Maps a stored tint index onto the palette, tolerating stale indices. */
fun folderTint(index: Int): Color =
    Palette.FolderTints[index.mod(Palette.FolderTints.size)]
