package com.termfold.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termfold.app.core.AppViewModel
import com.termfold.app.core.SessionPreset
import com.termfold.app.core.ShellState
import com.termfold.app.shell.TerminalHost
import com.termfold.app.ui.components.BottomNav
import com.termfold.app.ui.components.NavRail
import com.termfold.app.ui.components.currentWindowWidth
import com.termfold.app.ui.components.isWide
import com.termfold.app.ui.components.AppSurface
import com.termfold.app.ui.components.NavTab
import com.termfold.app.ui.screens.AgentSessionScreen
import com.termfold.app.ui.screens.ConfirmDialog
import com.termfold.app.ui.screens.FileManagerScreen
import com.termfold.app.ui.screens.FilesWorkspace
import com.termfold.app.ui.screens.FolderDetailScreen
import com.termfold.app.ui.screens.FoldersScreen
import com.termfold.app.ui.screens.OptionsSheet
import com.termfold.app.ui.screens.PresetChips
import com.termfold.app.ui.screens.ProvisioningScreen
import com.termfold.app.ui.screens.SearchField
import com.termfold.app.ui.screens.SettingsScreen
import com.termfold.app.ui.screens.TerminalScreen
import com.termfold.app.ui.screens.TextPromptDialog
import com.termfold.app.ui.theme.Palette
import com.termfold.app.ui.theme.TermFoldTheme

class MainActivity : TermFoldActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Creating the channels while on screen is what makes Android 13+ ask for the
        // notification permission.
        com.termfold.app.notify.Notifier.ensureChannels(this)
        com.termfold.app.notify.Notifier.placeFrom(intent)?.let(com.termfold.app.notify.AppPresence::request)

        setContent {
            TermFoldTheme {
                TermFoldRoot(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        com.termfold.app.notify.Notifier.placeFrom(intent)?.let(com.termfold.app.notify.AppPresence::request)
    }

    override fun onStart() {
        super.onStart()
        // Back in the full app: the bubble would only be a second copy of it.
        com.termfold.app.notify.Notifier.cancelBubble(this)
    }

    /**
     * The user is leaving for another app (Home, Recents, a gesture), not opening one of ours
     * like the photo picker. If they were working in a folder or a session, the app floats on as
     * a bubble that opens right there.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val place = com.termfold.app.notify.AppPresence.place ?: return
        com.termfold.app.notify.Notifier.showBubble(this, place, com.termfold.app.notify.AppPresence.label)
    }
}

/**
 * What every TermFold window shares: the physical-keyboard handling. The main window and the
 * bubble both extend it.
 */
open class TermFoldActivity : ComponentActivity() {

    /**
     * The first key typed on a physical keyboard puts the on-screen one away: it is covering the
     * terminal and nobody is using it. It comes back as soon as the user taps a text field or the
     * terminal, which is them choosing touch again (see [PhysicalKeyboard]).
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val wasInUse = com.termfold.app.ui.components.PhysicalKeyboard.inUse
        if (event.action == android.view.KeyEvent.ACTION_DOWN &&
            com.termfold.app.ui.components.PhysicalKeyboard.onKeyEvent(event) && !wasInUse
        ) {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        com.termfold.app.ui.components.PhysicalKeyboard.onTouch(event)
        return super.dispatchTouchEvent(event)
    }
}


/** Destinations. Plain state is enough for a graph this shallow. */
private sealed interface Destination {    data object Tabs : Destination
    data class Folder(val folderId: String) : Destination
    data class Terminal(val folderId: String, val sessionId: String) : Destination
}

/** How deep a destination sits, used to give the transitions a consistent direction. */
private fun Destination.depth(): Int = when (this) {
    Destination.Tabs -> 0
    is Destination.Folder -> 1
    is Destination.Terminal -> 2
}

/**
 * The shared screen transition: a short directional slide with a fade. Moving deeper into the app
 * enters from the right; coming back out reverses it, so navigation reads as spatial movement.
 * [rankOf] maps each value to its position in that navigation depth, e.g. tabs before folder
 * before terminal.
 */
private fun <T> directionalTransition(rankOf: (T) -> Int): AnimatedContentTransitionScope<T>.() -> ContentTransform = {
    val forward = rankOf(targetState) > rankOf(initialState)
    val enter = slideInHorizontally(tween(260)) { full ->
        if (forward) full / 4 else -full / 4
    } + fadeIn(tween(220))
    val exit = slideOutHorizontally(tween(260)) { full ->
        if (forward) -full / 4 else full / 4
    } + fadeOut(tween(180))
    enter togetherWith exit
}

/** What an options sheet or confirmation is acting on. */
private sealed interface OptionsTarget {
    val folderId: String

    data class Folder(override val folderId: String) : OptionsTarget
    data class Session(override val folderId: String, val sessionId: String) : OptionsTarget
}

@Composable
internal fun TermFoldRoot(viewModel: AppViewModel) {
    val data by viewModel.folders.collectAsStateWithLifecycle()
    val shell by viewModel.shell.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var destination by remember { mutableStateOf<Destination>(Destination.Tabs) }

    // A notification or the bubble asked to show a folder or session.
    val request by com.termfold.app.notify.AppPresence.requests.collectAsStateWithLifecycle()
    LaunchedEffect(request, data) {
        val place = request ?: return@LaunchedEffect
        val folder = data.folder(place.folderId) ?: return@LaunchedEffect
        destination = if (place.sessionId != null && folder.sessions.any { it.id == place.sessionId }) {
            Destination.Terminal(folder.id, place.sessionId)
        } else {
            Destination.Folder(folder.id)
        }
        com.termfold.app.notify.AppPresence.consume(place)
    }
    // Tell the rest of the app where the user is, for notifications and the bubble.
    LaunchedEffect(destination, data) {
        val presence = com.termfold.app.notify.AppPresence
        when (val d = destination) {
            is Destination.Folder -> {
                presence.place = com.termfold.app.notify.AppPresence.Place(d.folderId)
                presence.label = data.folder(d.folderId)?.name.orEmpty()
            }
            is Destination.Terminal -> {
                presence.place = com.termfold.app.notify.AppPresence.Place(d.folderId, d.sessionId)
                val folder = data.folder(d.folderId)
                val session = folder?.sessions?.firstOrNull { it.id == d.sessionId }
                presence.label = listOfNotNull(session?.name, folder?.name).joinToString(" \u00B7 ")
                com.termfold.app.notify.Notifier.cancelFor(context, "${d.folderId}/${d.sessionId}")
            }
            else -> presence.place = null
        }
    }
    var filesOpen by rememberSaveable { mutableStateOf(false) }
    var tab by remember { mutableStateOf(NavTab.FOLDERS) }
    var newProject by remember { mutableStateOf(false) }
    val job by viewModel.job.collectAsStateWithLifecycle()
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    var optionsFor by remember { mutableStateOf<OptionsTarget?>(null) }
    var renameFolderId by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<OptionsTarget?>(null) }
    var addSessionFor by remember { mutableStateOf<String?>(null) }

    val pickFolder = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri: Uri? ->
            if (uri != null) viewModel.importProject(uri)
            searchOpen = false
        },
    )

    // The bundled Linux environment is unpacked once, lazily, the first time the app runs. It
    // costs a few seconds and is then never repeated.
    LaunchedEffect(Unit) { viewModel.ensureShellReady() }

    // Projects are folders in ~/projects; the list follows that folder (a project made with
    // mkdir in a shell appears, a deleted one goes), checked on start and on every return.
    LaunchedEffect(shell.state) { viewModel.syncProjects() }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) viewModel.syncProjects()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }


    when (shell.state) {
        ShellState.UNKNOWN, ShellState.PREPARING -> {
            AppSurface {
                ProvisioningScreen(
                    step = shell.step,
                    progress = shell.fraction,
                    error = null,
                    onRetry = { viewModel.retryShell() },
                )
            }
            return
        }

        ShellState.FAILED -> {
            AppSurface {
                ProvisioningScreen(
                    step = shell.step,
                    progress = shell.fraction,
                    error = shell.error,
                    onRetry = { viewModel.retryShell() },
                )
            }
            return
        }

        ShellState.READY, ShellState.UNSUPPORTED -> Unit
    }

    val windowWidth = currentWindowWidth()

    // Phone and tablet share one tree; only the navigation container and the folder presentation
    // differ, so there is no second layout to keep in sync.
    AppSurface {
        Row(modifier = Modifier.fillMaxSize()) {
            if (windowWidth.isWide) {
                // The rail is visible over every destination, so picking a tab must also leave
                // whatever folder or terminal screen is open — setting the tab alone would change
                // nothing, because the destination branch is what the content area displays.
                NavRail(
                    selected = tab,
                    onSelect = { selected ->
                        tab = selected
                        if (selected != NavTab.FOLDERS) searchOpen = false
                        destination = Destination.Tabs
                    },
                )
            }
            // The folder being worked in, if any: its files can be shown beside the folder view,
            // a chat or a shell (not on the home screen).
            val workspaceFolder = when (val d = destination) {
                is Destination.Folder -> data.folder(d.folderId)
                is Destination.Terminal -> data.folder(d.folderId)
                else -> null
            }
            // A phone's drawer closes when the screen changes; a tablet keeps the tree docked.
            LaunchedEffect(destination, windowWidth.isWide) {
                if (!windowWidth.isWide) filesOpen = false
            }
            Box(modifier = Modifier.weight(1f)) {
              FilesWorkspace(
                folder = workspaceFolder,
                filesOpen = filesOpen,
                onCloseFiles = { filesOpen = false },
                wide = windowWidth.isWide,
              ) {
                AnimatedContent(
                    targetState = destination,
                    transitionSpec = directionalTransition { it.depth() },
                    label = "destination",
                ) { dest ->
                when (val current = dest) {
                    Destination.Tabs -> Column(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .windowInsetsPadding(
                                    WindowInsets.safeDrawing.only(
                                        WindowInsetsSides.Top + WindowInsetsSides.End
                                    )
                                ),
                        ) {
                            AnimatedContent(
                                targetState = tab,
                                transitionSpec = directionalTransition { it.ordinal },
                                label = "tab",
                            ) { currentTab ->
                            when (currentTab) {
                                NavTab.FOLDERS -> FoldersScreen(
                                    folders = data.folders,
                                    query = query,
                                    onQueryChange = { query = it },
                                    onOpenFolder = { destination = Destination.Folder(it) },
                                    onAddFolder = { newProject = true },
                                    onSearchToggle = {
                                        searchOpen = !searchOpen
                                        if (!searchOpen) query = ""
                                    },
                                    searchOpen = searchOpen,
                                    onMore = { tab = NavTab.SETTINGS },
                                    useGrid = windowWidth.isWide,
                                )

                                NavTab.FILES -> FileManagerScreen(wide = windowWidth.isWide)

                                NavTab.SETTINGS -> SettingsScreen(
                                    shellState = shell.state,
                                    folderCount = data.folders.size,
                                    sessionCount = data.folders.sumOf { it.sessions.size },
                                    onRepairShell = { viewModel.retryShell() },
                                    wide = windowWidth.isWide,
                                )
                            }
                            }
                        }

                        if (!windowWidth.isWide) {
                            BottomNav(
                                selected = tab,
                                onSelect = { selected ->
                                    tab = selected
                                    if (selected != NavTab.FOLDERS) searchOpen = false
                                },
                            )
                        }
                    }

                is Destination.Folder -> {
                    val folder = data.folder(current.folderId)
                    if (folder == null) {
                        LaunchedEffect(current.folderId) { destination = Destination.Tabs }
                    } else {
                        BackHandler { destination = Destination.Tabs }
                        FolderDetailScreen(
                            folder = folder,
                            wide = windowWidth.isWide,
                            modifier = Modifier.windowInsetsPadding(
                                WindowInsets.safeDrawing.only(
                                    WindowInsetsSides.Top + WindowInsetsSides.End
                                )
                            ),
                            onBack = { destination = Destination.Tabs },
                            filesOpen = filesOpen,
                            onToggleFiles = { filesOpen = !filesOpen },
                            onOpenSession = { sessionId ->
                                destination = Destination.Terminal(folder.id, sessionId)
                            },
                            onAddSession = { addSessionFor = folder.id },
                            onRenameFolder = { renameFolderId = folder.id },
                            onOpenOptions = { sessionId ->
                                optionsFor = if (sessionId == null) {
                                    OptionsTarget.Folder(folder.id)
                                } else {
                                    OptionsTarget.Session(folder.id, sessionId)
                                }
                            },
                        )
                    }
                }

                is Destination.Terminal -> {
                    val folder = data.folder(current.folderId)
                    val session = folder?.sessions?.firstOrNull { it.id == current.sessionId }
                    if (folder == null || session == null) {
                        LaunchedEffect(current.folderId, current.sessionId) {
                            destination = Destination.Tabs
                        }
                    } else if (session.acpAgentId.isNotBlank()) {
                        BackHandler { destination = Destination.Folder(folder.id) }
                        AgentSessionScreen(
                            folder = folder,
                            sessionId = session.id,
                            agentId = session.acpAgentId,
                            modifier = Modifier.windowInsetsPadding(
                                WindowInsets.safeDrawing.only(
                                    WindowInsetsSides.Top + WindowInsetsSides.End
                                )
                            ),
                            onBack = { destination = Destination.Folder(folder.id) },
                            filesOpen = filesOpen,
                            onToggleFiles = { filesOpen = !filesOpen },
                        )
                    } else {
                        BackHandler { destination = Destination.Folder(folder.id) }
                        TerminalScreen(
                            folder = folder,
                            sessionId = session.id,
                            sessionName = session.name,
                            initialCommand = session.command,
                            wide = windowWidth.isWide,
                            modifier = Modifier.windowInsetsPadding(
                                WindowInsets.safeDrawing.only(
                                    WindowInsetsSides.Top + WindowInsetsSides.End
                                )
                            ),
                            onBack = { destination = Destination.Folder(folder.id) },
                            onRestart = { TerminalHost.restart(context) },
                            filesOpen = filesOpen,
                            onToggleFiles = { filesOpen = !filesOpen },
                        )
                    }
                }
                    }
                }
              }
            }
        }
    }

    if (newProject) {
        com.termfold.app.ui.screens.NewProjectDialog(
            onCreateEmpty = { name ->
                newProject = false
                viewModel.createProject(name) { id -> destination = Destination.Folder(id) }
            },
            onClone = { url, name ->
                newProject = false
                viewModel.cloneProject(url, name)
            },
            onImport = {
                newProject = false
                pickFolder.launch(null)
            },
            onDismiss = { newProject = false },
        )
    }
    job?.let { j ->
        com.termfold.app.ui.screens.ProjectJobDialog(
            job = j,
            onOpen = { id ->
                viewModel.dismissJob()
                destination = Destination.Folder(id)
            },
            onDismiss = { viewModel.dismissJob() },
        )
    }

    OptionsLayer(
        viewModel = viewModel,
        optionsFor = optionsFor,
        onDismissOptions = { optionsFor = null },
        onRequestDelete = { confirmDelete = it },
        renameFolderId = renameFolderId,
        onDismissRename = { renameFolderId = null },
        confirmDelete = confirmDelete,
        onDismissDelete = { confirmDelete = null },
        addSessionFor = addSessionFor,
        onDismissAddSession = { addSessionFor = null },
        folderNameFor = { id -> data.folder(id)?.name.orEmpty() },
        sessionIdsFor = { id -> data.folder(id)?.sessions?.map { it.id }.orEmpty() },
    )
}

@Composable
private fun OptionsLayer(
    viewModel: AppViewModel,
    optionsFor: OptionsTarget?,
    onDismissOptions: () -> Unit,
    onRequestDelete: (OptionsTarget) -> Unit,
    renameFolderId: String?,
    onDismissRename: () -> Unit,
    confirmDelete: OptionsTarget?,
    onDismissDelete: () -> Unit,
    addSessionFor: String?,
    onDismissAddSession: () -> Unit,
    folderNameFor: (String) -> String,
    sessionIdsFor: (String) -> List<String>,
) {
    optionsFor?.let { target ->
        val actions = listOf(
            stringResource(R.string.action_remove) to { onRequestDelete(target) },
        )

        OptionsSheet(
            title = folderNameFor(target.folderId),
            actions = actions,
            onDismiss = onDismissOptions,
        )
    }

    renameFolderId?.let { folderId ->
        var name by remember(folderId) { mutableStateOf(folderNameFor(folderId)) }
        TextPromptDialog(
            title = stringResource(R.string.cd_name),
            value = name,
            onValueChange = { name = it },
            onConfirm = {
                if (name.isNotBlank()) viewModel.renameFolder(folderId, name.trim())
                onDismissRename()
            },
            onDismiss = onDismissRename,
            confirmLabel = stringResource(R.string.action_add),
        )
    }

    confirmDelete?.let { target ->
        val isFolder = target is OptionsTarget.Folder
        ConfirmDialog(
            title = if (isFolder) stringResource(R.string.project_delete_title, folderNameFor(target.folderId)) else stringResource(R.string.action_remove),
            body = if (isFolder) stringResource(R.string.project_delete_body) else folderNameFor(target.folderId),
            confirmLabel = if (isFolder) stringResource(R.string.files_delete) else stringResource(R.string.action_remove),
            onConfirm = {
                if (isFolder) {
                    // End the folder's running sessions before the definitions disappear,
                    // otherwise their processes would idle on with no way to reach them.
                    sessionIdsFor(target.folderId).forEach { sessionId ->
                        val key = TerminalHost.sessionKey(target.folderId, sessionId)
                        TerminalHost.closeSession(key)
                        com.termfold.app.acp.AcpSessions.close(key)
                    }
                    viewModel.removeFolder(target.folderId)
                } else {
                    val sessionId = (target as OptionsTarget.Session).sessionId
                    val key = TerminalHost.sessionKey(target.folderId, sessionId)
                    // Removing a session ends it: the terminal closes, the agent process dies,
                    // and the process tree is released.
                    TerminalHost.closeSession(key)
                    com.termfold.app.acp.AcpSessions.close(key)
                    viewModel.removeSession(target.folderId, sessionId)
                }
            },
            onDismiss = onDismissDelete,
        )
    }

    addSessionFor?.let { folderId ->
        NewSessionDialog(
            onDismiss = onDismissAddSession,
            onCreate = { name, command, acpAgentId ->
                viewModel.addSession(folderId, name, command, acpAgentId)
                onDismissAddSession()
            },
        )
    }
}

/** Preset-driven session creation, with a free-text command for the custom case. */
@Composable
private fun NewSessionDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, command: String, acpAgentId: String) -> Unit,
) {
    val context = LocalContext.current
    val presets = SessionPreset.entries
    var selected by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }

    // The trailing pseudo-preset switches the session into ACP mode: it runs a registry agent
    // through the native agent interface instead of a shell.
    val acpIndex = presets.size
    var agents by remember { mutableStateOf<List<com.termfold.app.acp.AcpAgent>>(emptyList()) }
    var loadingAgents by remember { mutableStateOf(false) }
    var selectedAgent by remember {
        mutableStateOf<com.termfold.app.acp.AcpAgent?>(null)
    }

    LaunchedEffect(selected) {
        if (selected == acpIndex && agents.isEmpty() && !loadingAgents) {
            loadingAgents = true
            agents = com.termfold.app.acp.AcpRegistry.agents(context)
            loadingAgents = false
        }
    }

    val preset = presets.getOrNull(selected)
    val effectiveCommand = if (preset == SessionPreset.CUSTOM) command
    else preset?.defaultCommand.orEmpty()
    val isAcp = selected == acpIndex
    val fallbackName = when {
        isAcp -> selectedAgent?.name ?: "ACP"
        preset != null -> stringResource(preset.labelRes)
        else -> "ACP"
    }
    val canConfirm = isAcp && selectedAgent != null ||
        preset == SessionPreset.SHELL ||
        effectiveCommand.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Card,
        titleContentColor = Palette.Text,
        title = {
            Text(
                text = stringResource(R.string.new_session_title),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                PresetChips(
                    labels = presets.map { stringResource(it.labelRes) } + stringResource(R.string.acp_chip),
                    selectedIndex = selected,
                    onSelect = { selected = it },
                )

                Spacer(Modifier.height(16.dp))

                if (isAcp) {
                    Text(
                        text = stringResource(R.string.acp_agent_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.TextFaint,
                    )
                    Spacer(Modifier.height(6.dp))
                    when {
                        loadingAgents -> Text(
                            text = stringResource(R.string.acp_registry_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.TextDim,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )

                        agents.isEmpty() -> Text(
                            text = stringResource(R.string.acp_registry_offline),
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.Pink,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )

                        else -> Column(
                            modifier = Modifier
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            agents.forEach { entry ->
                                val active = selectedAgent?.id == entry.id
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (active) Palette.CardPressed else Color.Transparent)
                                        .clickable {
                                            selectedAgent = entry
                                            if (name.isBlank()) name = entry.name
                                        }
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    com.termfold.app.ui.components.AgentIconImage(
                                        iconUrl = entry.iconUrl,
                                        name = entry.name,
                                        size = 26.dp,
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = entry.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Palette.Text,
                                            maxLines = 1,
                                        )
                                        if (entry.description.isNotBlank()) {
                                            Text(
                                                text = entry.description,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Palette.TextFaint,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.acp_session_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.TextFaint,
                    )
                    Spacer(Modifier.height(14.dp))
                }

                if (preset == SessionPreset.CUSTOM && !isAcp) {
                    LabelledField(
                        label = stringResource(R.string.custom_command),
                        value = command,
                        onValueChange = { command = it },
                        placeholder = "npm start",
                    )
                    Spacer(Modifier.height(14.dp))
                }

                LabelledField(
                    label = stringResource(R.string.cd_name),
                    value = name,
                    onValueChange = { name = it },
                    placeholder = fallbackName,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCreate(
                        name.ifBlank { fallbackName }.trim(),
                        effectiveCommand.trim(),
                        if (isAcp) selectedAgent?.id.orEmpty() else "",
                    )
                },
                enabled = canConfirm,
            ) {
                Text(
                    text = stringResource(R.string.action_add),
                    color = if (canConfirm) Palette.Accent else Palette.TextFaint,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.action_cancel),
                    color = Palette.TextDim,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
    )
}

@Composable
private fun LabelledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Palette.TextFaint,
        )
        Spacer(Modifier.height(6.dp))
        SearchField(
            query = value,
            onQueryChange = onValueChange,
            placeholder = placeholder,
        )
    }
}
