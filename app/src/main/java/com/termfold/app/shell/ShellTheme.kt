package com.termfold.app.shell

import com.termux.terminal.TerminalColors

/**
 * The terminal's colour scheme.
 *
 * The emulator reads every colour — including the default foreground, background and cursor —
 * from `TerminalColors.COLOR_SCHEME.mDefaultColors`, whose indices are fixed by the library.
 * Nothing else configures them, so this has to be applied before the first session is created or
 * the terminal renders in the library's own palette instead of the app's.
 */
object ShellTheme {

    /** Matches `TextStyle.COLOR_INDEX_FOREGROUND` / `_BACKGROUND` / `_CURSOR`. */
    private const val INDEX_FOREGROUND = 256
    private const val INDEX_BACKGROUND = 257
    private const val INDEX_CURSOR = 258

    /** The app's near-black, so the terminal is not a differently-shaded rectangle. */
    private const val BACKGROUND = 0xFF08080A.toInt()
    private const val FOREGROUND = 0xFFD8D8DC.toInt()
    private const val CURSOR = 0xFFFF7A2E.toInt()

    /**
     * The 16 ANSI colours, chosen so the dim half stays legible on black. That half is what most
     * TUIs and `ls --color` use for their ordinary text.
     */
    private val ANSI = intArrayOf(
        0xFF1B1B20.toInt(), // black
        0xFFE06C75.toInt(), // red
        0xFF7EC87E.toInt(), // green
        0xFFE5C07B.toInt(), // yellow
        0xFF61AFEF.toInt(), // blue
        0xFFC678DD.toInt(), // magenta
        0xFF56B6C2.toInt(), // cyan
        0xFFC8C8CC.toInt(), // white
        0xFF5C6370.toInt(), // bright black
        0xFFFF7A85.toInt(), // bright red
        0xFF9BE39B.toInt(), // bright green
        0xFFFFD68A.toInt(), // bright yellow
        0xFF82C7FF.toInt(), // bright blue
        0xFFDC9BF0.toInt(), // bright magenta
        0xFF7FD8E4.toInt(), // bright cyan
        0xFFF4F4F6.toInt(), // bright white
    )

    /** Writes the colour table. Safe to call more than once. */
    fun apply() {
        val colors = TerminalColors.COLOR_SCHEME.mDefaultColors
        for (index in ANSI.indices) {
            if (index < colors.size) colors[index] = ANSI[index]
        }
        if (colors.size > INDEX_CURSOR) {
            colors[INDEX_FOREGROUND] = FOREGROUND
            colors[INDEX_BACKGROUND] = BACKGROUND
            colors[INDEX_CURSOR] = CURSOR
        }
    }

    /** The background colour, for the View that hosts the terminal. */
    val windowBackground: Int get() = BACKGROUND
}
