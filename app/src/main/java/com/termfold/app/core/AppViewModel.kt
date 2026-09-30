package com.termfold.app.core

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.termfold.app.data.FolderStore
import com.termfold.app.shell.DistroMigration
import com.termfold.app.shell.Projects
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

/** A project being created in the background (a git clone or a device-folder import). */
data class ProjectJob(
    val title: String,
    val progress: String = "",
    val error: String? = null,
    val done: Boolean = false,
    /** The new project, once it exists. */
    val folderId: String? = null,
)

/**
 * Whether the bundled Linux environment has been unpacked yet. [MIGRATE_OFFER] and [MIGRATING]
 * are for an install made with Ubuntu, which is offered the move to Debian ([DistroMigration]).
 */
enum class ShellState { UNKNOWN, PREPARING, READY, FAILED, UNSUPPORTED, MIGRATE_OFFER, MIGRATING }

/** Progress of the one-time setup, so the UI can narrate it. */
data class ShellProgress(
    val state: ShellState = ShellState.UNKNOWN,
    val step: String = "",
    val fraction: Float = 0f,
    val error: String? = null,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        /** How long "Later" holds the offer back. */
        const val SNOOZE_MS = 24L * 60 * 60 * 1000
    }

    private val store = FolderStore(application)

    val folders: StateFlow<AppData> = store.data
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppData())

    private val _shell = MutableStateFlow(ShellProgress())
    val shell: StateFlow<ShellProgress> = _shell.asStateFlow()

    private val _job = MutableStateFlow<ProjectJob?>(null)
    val job: StateFlow<ProjectJob?> = _job.asStateFlow()

    fun dismissJob() {
        if (_job.value?.done == true || _job.value?.error != null) _job.value = null
    }

    /** Set when the last attempt to move to Debian failed, so Retry tries that again. */
    private var migrationFailed = false

    private val migrationPrefs =
        application.getSharedPreferences("distro_migration", android.content.Context.MODE_PRIVATE)

    init {
        // Provisioning is started from the UI rather than here, so a failure surfaces on screen
        // instead of being swallowed during ViewModel construction.
        _shell.value = ShellProgress(
            state = when {
                !ShellRuntime.isReady(application) -> ShellState.UNKNOWN
                // An Ubuntu install is offered the move to Debian, unless the user said "later"
                // recently. Until then it keeps working exactly as before.
                ShellRuntime.isLegacyUbuntu(application) && !migrationSnoozed() -> ShellState.MIGRATE_OFFER
                else -> ShellState.READY
            }
        )
    }

    private fun migrationSnoozed(): Boolean =
        System.currentTimeMillis() - migrationPrefs.getLong("snoozed_at", 0L) < SNOOZE_MS

    /** Shows the offer to move to Debian; used by the button in Settings. */
    fun offerMigration() {
        if (_shell.value.state == ShellState.READY && ShellRuntime.isLegacyUbuntu(getApplication())) {
            _shell.value = ShellProgress(state = ShellState.MIGRATE_OFFER)
        }
    }

    /** "Later": carry on with Ubuntu for now, and ask again tomorrow. */
    fun postponeMigration() {
        migrationPrefs.edit().putLong("snoozed_at", System.currentTimeMillis()).apply()
        _shell.value = ShellProgress(state = ShellState.READY)
        // The refresh an existing environment gets on launch was held back by the offer.
        refreshed = false
        ensureShellReady()
    }

    /** Moves the environment from Ubuntu to Debian, keeping the user's files. */
    fun startMigration() {
        if (_shell.value.state == ShellState.MIGRATING) return
        migrationFailed = false
        _shell.value = ShellProgress(state = ShellState.MIGRATING, step = "", fraction = 0f)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    DistroMigration.migrate(getApplication()) { step, fraction ->
                        _shell.update { it.copy(step = step, fraction = fraction) }
                    }
                }
            }
            _shell.value = result.fold(
                onSuccess = {
                    refreshed = true
                    ShellProgress(state = ShellState.READY, step = "", fraction = 1f)
                },
                onFailure = { error ->
                    Log.e("AppViewModel", "Migration to Debian failed", error)
                    migrationFailed = true
                    ShellProgress(state = ShellState.FAILED, error = error.message ?: error.javaClass.simpleName)
                },
            )
            syncProjects()
        }
    }

    /**
     * Unpacks the bundled Linux environment if it is not already there.
     *
     * Safe to call repeatedly: [ShellRuntime.provision] returns immediately once the rootfs
     * marker is in place, so this is a cheap no-op on every launch after the first.
     */
    /** Whether this process has already run the refresh for an existing environment. */
    private var refreshed = false

    fun ensureShellReady() {
        val current = _shell.value
        if (current.state == ShellState.MIGRATE_OFFER || current.state == ShellState.MIGRATING) return
        if (current.state == ShellState.READY) {
            // Already unpacked: still run provision's cheap path once per process. It is what
            // brings an existing install up to date after an app update (setup script, git
            // defaults, helper libraries); skipping it left those only on fresh installs.
            if (!refreshed) {
                refreshed = true
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching { ShellRuntime.provision(getApplication()) }
                        .onFailure { Log.w("AppViewModel", "Environment refresh failed", it) }
                }
            }
            return
        }
        if (current.state == ShellState.PREPARING) return

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
        if (migrationFailed) {
            startMigration()
            return
        }
        _shell.value = ShellProgress(state = ShellState.UNKNOWN)
        ensureShellReady()
    }

    /**
     * Makes the project list match `~/projects`: a folder created there from a shell appears,
     * one deleted there disappears, and entries for folders outside the Linux environment
     * (the old Android-storage folders) are dropped. The files of those stay on the device.
     */
    fun syncProjects() {
        val app = getApplication<Application>()
        if (!ShellRuntime.isReady(app)) return
        viewModelScope.launch(Dispatchers.IO) {
            val dirs = Projects.dir(app).apply { mkdirs() }
                .listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
                .orEmpty()
                .sortedBy { it.name.lowercase() }
            store.update { data ->
                val kept = data.folders.filter { f ->
                    Projects.isInGuest(app, f.path) && java.io.File(f.path).isDirectory
                }
                val known = kept.map { java.io.File(it.path).absolutePath }.toSet()
                val added = dirs.filter { it.absolutePath !in known }.mapIndexed { i, dir ->
                    newFolder(dir, dir.name, (kept.size + i) % 6)
                }
                if (added.isEmpty() && kept.size == data.folders.size) data else data.copy(folders = kept + added)
            }
        }
    }

    private fun newFolder(dir: java.io.File, name: String, tint: Int) = Folder(
        id = Ids.new(),
        name = name,
        path = dir.absolutePath,
        tint = tint,
        sessions = defaultSessions(),
    )

    private suspend fun addProject(dir: java.io.File, name: String): String {
        val folder = newFolder(dir, name, folders.value.folders.size % 6)
        store.update { data -> data.copy(folders = data.folders + folder) }
        return folder.id
    }

    /** An empty project. Calls [onCreated] with its id. */
    fun createProject(name: String, onCreated: (String) -> Unit = {}) {
        viewModelScope.launch {
            val dir = withContext(Dispatchers.IO) { Projects.create(getApplication(), name) }
            onCreated(addProject(dir, name.trim().ifEmpty { dir.name }))
        }
    }

    /** Clones a git repository into a new project, reporting progress through [job]. */
    fun cloneProject(url: String, name: String) {
        val app = getApplication<Application>()
        val display = name.trim().ifEmpty {
            url.trimEnd('/').substringAfterLast('/').removeSuffix(".git").ifEmpty { "project" }
        }
        _job.value = ProjectJob(title = display, progress = "Starting…")
        viewModelScope.launch {
            val dir = withContext(Dispatchers.IO) { Projects.newDirFor(app, display) }
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    Projects.clone(app, url.trim(), dir) { line -> _job.update { it?.copy(progress = line) } }
                }.getOrElse { it.message ?: "clone failed" }
            }
            if (error != null) {
                withContext(Dispatchers.IO) { dir.deleteRecursively() }
                _job.update { it?.copy(error = error) }
            } else {
                val id = addProject(dir, display)
                _job.update { it?.copy(done = true, folderId = id, progress = "") }
            }
        }
    }

    /** Copies a folder from the device into a new project, reporting progress through [job]. */
    fun importProject(treeUri: Uri) {
        val app = getApplication<Application>()
        val display = androidx.documentfile.provider.DocumentFile.fromTreeUri(app, treeUri)?.name
            ?.takeIf { it.isNotBlank() } ?: "Imported"
        _job.value = ProjectJob(title = display, progress = "Copying…")
        viewModelScope.launch {
            val dir = withContext(Dispatchers.IO) { Projects.newDirFor(app, display) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    Projects.importTree(app, treeUri, dir) { p -> _job.update { it?.copy(progress = p) } }
                }
            }
            result.onSuccess { count ->
                val id = addProject(dir, display)
                _job.update { it?.copy(done = true, folderId = id, progress = "$count files") }
            }.onFailure { e ->
                withContext(Dispatchers.IO) { dir.deleteRecursively() }
                _job.update { it?.copy(error = e.message ?: "import failed") }
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
        val app = getApplication<Application>()
        viewModelScope.launch {
            val folder = folders.value.folder(folderId)
            store.update { data ->
                data.copy(folders = data.folders.filterNot { it.id == folderId })
            }
            // A project's files live only inside the app, so removing it deletes them (the
            // confirmation says so). Anything outside ~/projects is never touched.
            val path = folder?.path
            if (path != null) {
                withContext(Dispatchers.IO) {
                    val dir = java.io.File(path)
                    val projects = Projects.dir(app).absolutePath + "/"
                    if (dir.absolutePath.startsWith(projects)) dir.deleteRecursively()
                }
            }
        }
    }

    fun addSession(folderId: String, name: String, command: String, acpAgentId: String = "") {
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
                            acpAgentId = acpAgentId,
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
