package com.termfold.app.shell

import android.content.Context
import com.termux.terminal.TerminalColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The terminal's colour schemes.
 *
 * The emulator reads every colour — including the default foreground, background and cursor —
 * from `TerminalColors.COLOR_SCHEME.mDefaultColors`, whose indices are fixed by the library.
 * Nothing else configures them, so the chosen scheme is written there before the first session
 * is created, and running sessions are reset onto it when the user picks another.
 */
object ShellTheme {

    /** Matches `TextStyle.COLOR_INDEX_FOREGROUND` / `_BACKGROUND` / `_CURSOR`. */
    private const val INDEX_FOREGROUND = 256
    private const val INDEX_BACKGROUND = 257
    private const val INDEX_CURSOR = 258

    data class Theme(
        val id: String,
        val name: String,
        val background: Int,
        val foreground: Int,
        val cursor: Int,
        /** The 16 ANSI colours: black, red, green, yellow, blue, magenta, cyan, white, then bright. */
        val ansi: IntArray,
        val light: Boolean = false,
    )

    private fun c(hex: Long) = hex.toInt()

    val THEMES: List<Theme> = listOf(
        // The app's own near-black, so the terminal is not a differently-shaded rectangle. The
        // dim half stays legible on black; that half is what most TUIs and `ls --color` use.
        Theme(
            "termfold", "TermFold", c(0xFF08080A), c(0xFFD8D8DC), c(0xFFFF7A2E),
            intArrayOf(
                c(0xFF1B1B20), c(0xFFE06C75), c(0xFF7EC87E), c(0xFFE5C07B), c(0xFF61AFEF), c(0xFFC678DD), c(0xFF56B6C2), c(0xFFC8C8CC),
                c(0xFF5C6370), c(0xFFFF7A85), c(0xFF9BE39B), c(0xFFFFD68A), c(0xFF82C7FF), c(0xFFDC9BF0), c(0xFF7FD8E4), c(0xFFF4F4F6),
            ),
        ),
        Theme(
            "dracula", "Dracula", c(0xFF282A36), c(0xFFF8F8F2), c(0xFFF8F8F2),
            intArrayOf(
                c(0xFF21222C), c(0xFFFF5555), c(0xFF50FA7B), c(0xFFF1FA8C), c(0xFFBD93F9), c(0xFFFF79C6), c(0xFF8BE9FD), c(0xFFF8F8F2),
                c(0xFF6272A4), c(0xFFFF6E6E), c(0xFF69FF94), c(0xFFFFFFA5), c(0xFFD6ACFF), c(0xFFFF92DF), c(0xFFA4FFFF), c(0xFFFFFFFF),
            ),
        ),
        Theme(
            "solarized-dark", "Solarized Dark", c(0xFF002B36), c(0xFF93A1A1), c(0xFF93A1A1),
            intArrayOf(
                c(0xFF073642), c(0xFFDC322F), c(0xFF859900), c(0xFFB58900), c(0xFF268BD2), c(0xFFD33682), c(0xFF2AA198), c(0xFFEEE8D5),
                c(0xFF586E75), c(0xFFCB4B16), c(0xFF93A1A1), c(0xFF839496), c(0xFF6C71C4), c(0xFFD33682), c(0xFF2AA198), c(0xFFFDF6E3),
            ),
        ),
        Theme(
            "nord", "Nord", c(0xFF2E3440), c(0xFFD8DEE9), c(0xFFD8DEE9),
            intArrayOf(
                c(0xFF3B4252), c(0xFFBF616A), c(0xFFA3BE8C), c(0xFFEBCB8B), c(0xFF81A1C1), c(0xFFB48EAD), c(0xFF88C0D0), c(0xFFE5E9F0),
                c(0xFF4C566A), c(0xFFBF616A), c(0xFFA3BE8C), c(0xFFEBCB8B), c(0xFF81A1C1), c(0xFFB48EAD), c(0xFF8FBCBB), c(0xFFECEFF4),
            ),
        ),
        Theme(
            "gruvbox-dark", "Gruvbox Dark", c(0xFF282828), c(0xFFEBDBB2), c(0xFFFE8019),
            intArrayOf(
                c(0xFF282828), c(0xFFCC241D), c(0xFF98971A), c(0xFFD79921), c(0xFF458588), c(0xFFB16286), c(0xFF689D6A), c(0xFFA89984),
                c(0xFF928374), c(0xFFFB4934), c(0xFFB8BB26), c(0xFFFABD2F), c(0xFF83A598), c(0xFFD3869B), c(0xFF8EC07C), c(0xFFEBDBB2),
            ),
        ),
        // A light scheme whose "white" entries are dark enough to read on paper, since programs
        // assume white text is visible.
        Theme(
            "light", "Light", c(0xFFFAFAF7), c(0xFF26262B), c(0xFFE8590C),
            intArrayOf(
                c(0xFF26262B), c(0xFFC5283D), c(0xFF2F7D32), c(0xFF9A6700), c(0xFF1F5FBF), c(0xFF8B3FB0), c(0xFF0F7B84), c(0xFF6E6E76),
                c(0xFF8A8A92), c(0xFFE0364C), c(0xFF3A9A3F), c(0xFFB57C00), c(0xFF2D74DC), c(0xFFA24FCB), c(0xFF13939D), c(0xFF3C3C43),
            ),
            light = true,
        ),
    )

    private const val PREFS = "termfold_prefs"
    private const val KEY_THEME = "terminal_theme"

    private val _current = MutableStateFlow(THEMES.first())
    val current: StateFlow<Theme> = _current.asStateFlow()

    /** Reads the saved choice and writes its colour table. Called once at app start. */
    fun init(context: Context) {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_THEME, null)
        _current.value = THEMES.firstOrNull { it.id == id } ?: THEMES.first()
        apply()
    }

    /** Switches scheme, including the terminals already open. */
    fun select(context: Context, theme: Theme) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_THEME, theme.id).apply()
        _current.value = theme
        apply()
        TerminalHost.recolor()
    }

    /** Writes the current scheme's colour table. Safe to call more than once. */
    fun apply() {
        val theme = _current.value
        val colors = TerminalColors.COLOR_SCHEME.mDefaultColors
        for (index in theme.ansi.indices) {
            if (index < colors.size) colors[index] = theme.ansi[index]
        }
        if (colors.size > INDEX_CURSOR) {
            colors[INDEX_FOREGROUND] = theme.foreground
            colors[INDEX_BACKGROUND] = theme.background
            colors[INDEX_CURSOR] = theme.cursor
        }
    }

    /** The background colour, for the View that hosts the terminal. */
    val windowBackground: Int get() = _current.value.background
}
