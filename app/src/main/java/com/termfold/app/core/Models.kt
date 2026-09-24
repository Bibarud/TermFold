package com.termfold.app.core

import androidx.annotation.StringRes
import com.termfold.app.R

/**
 * A session preset.
 *
 * Every preset is interactive, because every preset now runs inside the app's own terminal: the
 * shell, and the agents, are just commands started in the bundled Ubuntu environment.
 */
enum class SessionPreset(
    @param:StringRes val labelRes: Int,
    val defaultCommand: String,
) {
    SHELL(R.string.session_preset_shell, ""),
    OPENCODE(R.string.session_preset_opencode, "opencode"),
    PI(R.string.session_preset_pi, "pi"),
    CLAUDE(R.string.session_preset_claude, "claude"),
    CUSTOM(R.string.session_preset_custom, ""),
}

data class Session(
    val id: String,
    val name: String,
    val command: String,
    val tint: Int,
    /**
     * Registry id of the ACP agent this session runs as (e.g. "opencode"). Blank means the
     * session opens as an ordinary shell in the terminal.
     */
    val acpAgentId: String = "",
)

data class Folder(
    val id: String,
    val name: String,
    val treeUri: String,
    /** Real filesystem path, or empty when the provider cannot be mapped to one. */
    val path: String,
    val tint: Int,
    val sessions: List<Session> = emptyList(),
)

data class AppData(
    val folders: List<Folder> = emptyList(),
) {
    fun folder(id: String?): Folder? = folders.firstOrNull { it.id == id }

    fun session(sessionId: String?): Pair<Folder, Session>? {
        if (sessionId == null) return null
        for (folder in folders) {
            folder.sessions.firstOrNull { it.id == sessionId }?.let { return folder to it }
        }
        return null
    }
}
