package com.termfold.app

import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
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
import com.termfold.app.ui.components.GlowScaffold
import com.termfold.app.ui.components.NavTab
import com.termfold.app.ui.screens.ConfirmDialog
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

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            TermFoldTheme {
                TermFoldRoot(viewModel)
            }
        }
    }
}

/**
 * The storage permissions this app needs, in the order they are requested.
 *
 * `READ_EXTERNAL_STORAGE` is capped at API 32: from API 33 the platform splits it into the media
 * permissions, none of which grant access to an arbitrary folder. Access to a folder the user
 * picked comes from the folder picker's own grant plus the legacy storage model this app targets,
 * and these are what make the app's own process treat it as an ordinary directory.
 */
private val REQUIRED_STORAGE_PERMISSIONS = listOf(
    android.Manifest.permission.READ_EXTERNAL_STORAGE,
    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
)

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
private fun TermFoldRoot(viewModel: AppViewModel) {
    val data by viewModel.folders.collectAsStateWithLifecycle()
    val shell by viewModel.shell.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var destination by remember { mutableStateOf<Destination>(Destination.Tabs) }
    var tab by remember { mutableStateOf(NavTab.FOLDERS) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    var optionsFor by remember { mutableStateOf<OptionsTarget?>(null) }
    var renameFolderId by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<OptionsTarget?>(null) }
    var addSessionFor by remember { mutableStateOf<String?>(null) }

    val pickFolder = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri: Uri? ->
            if (uri != null) {
                // Without this the grant dies with the process and stored folders become unreadable.
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                viewModel.addFolder(uri, null)
            }
            searchOpen = false
        },
    )

    // The bundled Linux environment is unpacked once, lazily, the first time the app runs. It
    // costs a few seconds and is then never repeated.
    LaunchedEffect(Unit) { viewModel.ensureShellReady() }

    // Storage access has to be requested at runtime. Without it the app cannot read a folder the
    // user picked, and the failure surfaces far away from the cause: the folder is bind-mounted
    // into the guest but every access inside the terminal returns "Permission denied".
    val storagePermissions = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { },
    )
    LaunchedEffect(Unit) {
        val missing = REQUIRED_STORAGE_PERMISSIONS.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) storagePermissions.launch(missing.toTypedArray())
    }

    when (shell.state) {
        ShellState.UNKNOWN, ShellState.PREPARING -> {
            GlowScaffold {
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
            GlowScaffold {
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
    GlowScaffold {
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
            Box(modifier = Modifier.weight(1f)) {
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
                                    onAddFolder = { pickFolder.launch(null) },
                                    onSearchToggle = {
                                        searchOpen = !searchOpen
                                        if (!searchOpen) query = ""
                                    },
                                    searchOpen = searchOpen,
                                    onMore = { tab = NavTab.SETTINGS },
                                    useGrid = windowWidth.isWide,
                                )

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
                        )
                    }
                }
                    }
                }
            }
        }
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
            title = stringResource(R.string.action_remove),
            body = folderNameFor(target.folderId),
            confirmLabel = stringResource(R.string.action_remove),
            onConfirm = {
                if (isFolder) {
                    // End the folder's running sessions before the definitions disappear,
                    // otherwise their PRoot processes would idle on with no way to reach them.
                    sessionIdsFor(target.folderId).forEach { sessionId ->
                        TerminalHost.closeSession(TerminalHost.sessionKey(target.folderId, sessionId))
                    }
                    viewModel.removeFolder(target.folderId)
                } else {
                    val sessionId = (target as OptionsTarget.Session).sessionId
                    // Removing a session ends it: the terminal closes and the process tree is
                    // released. Opening the session again means starting a fresh one.
                    TerminalHost.closeSession(TerminalHost.sessionKey(target.folderId, sessionId))
                    viewModel.removeSession(target.folderId, sessionId)
                }
            },
            onDismiss = onDismissDelete,
        )
    }

    addSessionFor?.let { folderId ->
        NewSessionDialog(
            onDismiss = onDismissAddSession,
            onCreate = { name, command ->
                viewModel.addSession(folderId, name, command)
                onDismissAddSession()
            },
        )
    }
}

/** Preset-driven session creation, with a free-text command for the custom case. */
@Composable
private fun NewSessionDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, command: String) -> Unit,
) {
    val presets = SessionPreset.entries
    var selected by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }

    val preset = presets[selected]
    val presetLabel = stringResource(preset.labelRes)
    val effectiveCommand = if (preset == SessionPreset.CUSTOM) command else preset.defaultCommand
    val canConfirm = preset == SessionPreset.SHELL || effectiveCommand.isNotBlank()

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
                    labels = presets.map { stringResource(it.labelRes) },
                    selectedIndex = selected,
                    onSelect = { selected = it },
                )

                Spacer(Modifier.height(16.dp))

                if (preset == SessionPreset.CUSTOM) {
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
                    placeholder = presetLabel,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCreate(
                        name.ifBlank { presetLabel }.trim(),
                        effectiveCommand.trim(),
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
