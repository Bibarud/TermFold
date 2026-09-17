package com.termfold.app.core

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.termfold.app.data.FolderStore
import com.termfold.app.data.SafPaths
import com.termfold.app.shell.ShellRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Whether the bundled Linux environment has been unpacked yet. */
enum class ShellState { UNKNOWN, PREPARING, READY, FAILED, UNSUPPORTED }

/** Progress of the one-time setup, so the UI can narrate it. */
data class ShellProgress(
    val state: ShellState = ShellState.UNKNOWN,
    val step: String = "",
    val fraction: Float = 0f,
    val error: String? = null,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val store = FolderStore(application)

    val folders: StateFlow<AppData> = store.data
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppData())

    private val _shell = MutableStateFlow(ShellProgress())
    val shell: StateFlow<ShellProgress> = _shell.asStateFlow()

    init {
        // Provisioning is started from the UI rather than here, so a failure surfaces on screen
        // instead of being swallowed during ViewModel construction.
        _shell.value = ShellProgress(
            state = if (ShellRuntime.isReady(application)) ShellState.READY else ShellState.UNKNOWN
        )
    }

    /**
     * Unpacks the bundled Linux environment if it is not already there.
     *
     * Safe to call repeatedly: [ShellRuntime.provision] returns immediately once the rootfs
     * marker is in place, so this is a cheap no-op on every launch after the first.
     */
    fun ensureShellReady() {
        val current = _shell.value
        if (current.state == ShellState.PREPARING || current.state == ShellState.READY) return

        _shell.value = ShellProgress(state = ShellState.PREPARING, step = "", fraction = 0f)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ShellRuntime.provision(getApplication()) { step, fraction ->
                        _shell.update { it.copy(step = step, fraction = fraction) }
                    }
                }
            }
            _shell.value = result.fold(
                onSuccess = {
                    ShellProgress(state = ShellState.READY, step = "", fraction = 1f)
                },
                onFailure = { error ->
                    Log.e("AppViewModel", "Linux setup failed", error)
                    ShellProgress(
                        state = ShellState.FAILED,
                        error = error.message ?: error.javaClass.simpleName,
                    )
                },
            )
        }
    }

    /** Clears a previous failure and tries the setup again. */
    fun retryShell() {
        _shell.value = ShellProgress(state = ShellState.UNKNOWN)
        ensureShellReady()
    }

    fun addFolder(treeUri: Uri, name: String?) {
        val display = name?.takeIf { it.isNotBlank() }
            ?: SafPaths.displayName(treeUri)
            ?: "Folder"
        val path = SafPaths.realPath(getApplication(), treeUri).orEmpty()

        viewModelScope.launch {
            store.update { data ->
                data.copy(
                    folders = data.folders + Folder(
                        id = Ids.new(),
                        name = display,
                        treeUri = treeUri.toString(),
                        path = path,
                        tint = data.folders.size % 6,
                        sessions = defaultSessions(),
                    )
                )
            }
        }
    }

    fun renameFolder(folderId: String, name: String) {
        viewModelScope.launch {
            store.update { data ->
                data.copy(
                    folders = data.folders.map {
                        if (it.id == folderId) it.copy(name = name) else it
                    }
                )
            }
        }
    }

    fun removeFolder(folderId: String) {
        viewModelScope.launch {
            store.update { data ->
                data.copy(folders = data.folders.filterNot { it.id == folderId })
            }
        }
    }

    fun addSession(folderId: String, name: String, command: String) {
        viewModelScope.launch {
            store.update { data ->
                data.copy(
                    folders = data.folders.map { folder ->
                        if (folder.id != folderId) return@map folder
                        val session = Session(
                            id = Ids.new(),
                            name = name,
                            command = command,
                            tint = folder.sessions.size % 6,
                        )
                        folder.copy(sessions = folder.sessions + session)
                    }
                )
            }
        }
    }

    fun removeSession(folderId: String, sessionId: String) {
        viewModelScope.launch {
            store.update { data ->
                data.copy(
                    folders = data.folders.map { folder ->
                        if (folder.id != folderId) return@map folder
                        folder.copy(sessions = folder.sessions.filterNot { it.id == sessionId })
                    }
                )
            }
        }
    }

    /** Sessions every new folder starts with, matching the agent presets. */
    private fun defaultSessions(): List<Session> = listOf(
        Session(Ids.new(), "Shell", "", tint = 0),
        Session(Ids.new(), "OpenCode", "opencode", tint = 1),
        Session(Ids.new(), "Pi", "pi", tint = 2),
    )
}
