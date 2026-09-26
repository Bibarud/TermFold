package com.termfold.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.termfold.app.R
import com.termfold.app.ui.components.LaunchedWhileVisible
import com.termfold.app.files.FileActions
import com.termfold.app.shell.Projects
import com.termfold.app.shell.ShellPaths
import com.termfold.app.ui.components.AgentIconLoader
import com.termfold.app.ui.components.BareIconButton
import com.termfold.app.ui.theme.Mono
import com.termfold.app.ui.theme.Palette
import com.termfold.app.ui.theme.TermFoldIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.coroutineContext

/** Files copied or cut in the file manager, waiting to be pasted. */
private data class Clip(val files: List<File>, val cut: Boolean)

private enum class SortBy { NAME, MODIFIED, SIZE }

/** A listed file with its size and date read once, off the main thread. */
private data class Item(val file: File, val isDir: Boolean, val size: Long, val modified: Long) {
    val key: String get() = file.absolutePath
}

private const val HOME = "/root"
private const val SEARCH_LIMIT = 300

/**
 * A file manager for the Ubuntu environment: browse from the home folder (or anywhere in the
 * system), search, create, rename, copy, cut, paste and delete, upload files from other apps,
 * and share, open with, export or save files to the device. Code opens in the built-in editor
 * and pictures in a viewer that can copy them to the clipboard.
 *
 * On a tablet a Places pane (home, every project, the system root) sits beside the listing;
 * on a phone the same places are one tap away on the title.
 */
@Composable
fun FileManagerScreen(wide: Boolean, modifier: Modifier = Modifier, startGuestPath: String = HOME) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val rootfs = remember { ShellPaths.rootfsDir(context) }
    var cwd by rememberSaveable { mutableStateOf(startGuestPath) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var sortName by rememberSaveable { mutableStateOf(SortBy.NAME.name) }
    val sort = SortBy.valueOf(sortName)
    var grid by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var clip by remember { mutableStateOf<Clip?>(null) }
    var menuFor by remember { mutableStateOf<File?>(null) }
    var naming by remember { mutableStateOf<Naming?>(null) }
    var confirmDelete by remember { mutableStateOf<List<File>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var opError by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<File?>(null) }
    var iconsReady by remember { mutableStateOf(FileIcons.ready) }
    var exportSource by remember { mutableStateOf<File?>(null) }
    var projects by remember { mutableStateOf<List<String>>(emptyList()) }
    val dir = File(rootfs, cwd.trimStart('/'))

    LaunchedEffect(Unit) {
        if (!iconsReady) {
            withContext(Dispatchers.IO) { FileIcons.load(context) }
            iconsReady = true
        }
    }
    LaunchedWhileVisible(refresh) {
        while (true) {
            val names = withContext(Dispatchers.IO) {
                Projects.dir(context).listFiles()
                    ?.filter { it.isDirectory && !it.name.startsWith(".") }
                    ?.map { it.name }
                    ?.sortedBy { it.lowercase() }
                    .orEmpty()
            }
            if (names != projects) projects = names
            delay(4000)
        }
    }
    LaunchedEffect(message) {
        if (message != null && busy == null) {
            delay(3000)
            message = null
        }
    }

    fun reload() { refresh++ }
    // Only Home (your projects included) is changed from here; the rest of the system is view-only.
    val home = remember { File(rootfs, HOME.trimStart('/')) }
    fun isUser(file: File) = file.path == home.path || file.path.startsWith(home.path + File.separator)
    val readOnly = !isGuestHome(cwd)
    fun guestOf(file: File): String = "/" + file.relativeTo(rootfs).path.replace('\\', '/').trim('/')
    fun go(guest: String) {
        cwd = guest.ifEmpty { "/" }
        selected = emptySet()
        query = ""
        focus.clearFocus()
    }
    fun open(item: Item) {
        when {
            item.isDir -> go(guestOf(item.file))
            FileActions.opensInEditor(item.file) -> editing = item.file
            !isUser(item.file) -> message = context.getString(R.string.fm_system_view_only)
            else -> runCatching { FileActions.openWith(context, item.file) }.onFailure { message = it.message }
        }
    }
    fun toggle(item: Item) {
        selected = if (item.key in selected) selected - item.key else selected + item.key
    }
    fun run(label: String, work: suspend () -> Unit) {
        busy = label
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { work() } }.onFailure { message = it.message ?: it.javaClass.simpleName }
            busy = null
            reload()
        }
    }

    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) run(context.getString(R.string.fm_uploading)) {
            val made = FileActions.upload(context, uris, dir)
            message = context.resources.getQuantityString(R.plurals.fm_uploaded, made.size, made.size)
        }
    }
    // A whole folder from the device, copied in with its structure.
    val uploadFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
        if (tree != null) run(context.getString(R.string.fm_uploading)) {
            val name = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, tree)?.name ?: "folder"
            val target = FileActions.unique(dir, Projects.safeName(name) ?: "folder")
            val count = Projects.importTree(context, tree, target)
            message = context.resources.getQuantityString(R.plurals.fm_uploaded, count, count)
        }
    }
    // Export: copy to any folder the user picks (Downloads, SD card, a cloud drive).
    var exportFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    val exportTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
        val files = exportFiles
        exportFiles = emptyList()
        if (tree != null && files.isNotEmpty()) run(context.getString(R.string.fm_exporting)) {
            val n = FileActions.exportToTree(context, files, tree) { done -> busy = context.getString(R.string.fm_exporting_n, done) }
            message = context.resources.getQuantityString(R.plurals.fm_exported, n, n)
        }
    }
    fun exportTo(files: List<File>) {
        exportFiles = files
        exportTree.launch(null)
    }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { target: Uri? ->
        val src = exportSource
        exportSource = null
        if (target != null && src != null) run(context.getString(R.string.fm_saving)) {
            FileActions.exportTo(context, src, target)
            message = context.getString(R.string.fm_saved, src.name)
        }
    }
    fun share(files: List<File>) {
        run(context.getString(R.string.fm_preparing)) {
            val ready = if (files.size == 1 && files[0].isFile) files else listOf(
                FileActions.zipToCache(context, files, if (files.size == 1) files[0].name else "files"),
            )
            withContext(Dispatchers.Main) { FileActions.share(context, ready) }
        }
    }
    fun saveToDevice(file: File) {
        exportSource = file
        saveAs.launch(if (file.isDirectory) file.name + ".zip" else file.name)
    }
    fun copyImage(file: File) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { FileActions.copyImage(context, file) }.isSuccess }
            // Android 13+ confirms clipboard copies itself.
            if (!ok) message = context.getString(R.string.files_unreadable)
            else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) message = context.getString(R.string.img_copied)
        }
    }
    fun copyPath(file: File) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(file.name, guestOf(file)))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) message = context.getString(R.string.fm_path_copied)
    }

    val selectedFiles = selected.map(::File)
    val searching = query.isNotBlank()
    BackHandler(enabled = editing == null && (selected.isNotEmpty() || searchOpen || cwd != "/" && cwd != startGuestPath)) {
        when {
            selected.isNotEmpty() -> selected = emptySet()
            searchOpen -> { query = ""; searchOpen = false }
            else -> go(cwd.substringBeforeLast('/'))
        }
    }

    val itemMenu: @Composable (Item) -> Unit = { item ->
        DropdownMenu(
            expanded = menuFor == item.file,
            onDismissRequest = { menuFor = null },
            containerColor = Palette.Card,
            shape = RoundedCornerShape(14.dp),
        ) {
            val f = item.file
            fun act(block: () -> Unit) { menuFor = null; block() }
            if (com.termfold.app.preview.Preview.canPreview(f)) {
                MenuEntry(TermFoldIcons.Globe, stringResource(R.string.preview_title)) {
                    act { com.termfold.app.preview.Preview.openFile(context, f) }
                }
            }
            if (!isUser(f)) {
                // A system file: look, take its path, or copy it into Home to work on.
                if (!item.isDir && FileActions.isImage(f)) MenuEntry(TermFoldIcons.ImageCopy, stringResource(R.string.img_copy)) { act { copyImage(f) } }
                MenuEntry(TermFoldIcons.Terminal, stringResource(R.string.fm_copy_path)) { act { copyPath(f) } }
                MenuEntry(TermFoldIcons.Copy, stringResource(R.string.action_copy)) { act { clip = Clip(listOf(f), cut = false) } }
                return@DropdownMenu
            }
            if (!item.isDir) {
                if (FileActions.isImage(f)) MenuEntry(TermFoldIcons.ImageCopy, stringResource(R.string.img_copy)) { act { copyImage(f) } }
                MenuEntry(TermFoldIcons.OpenExternal, stringResource(R.string.fm_open_with)) {
                    act { runCatching { FileActions.openWith(context, f) }.onFailure { message = it.message } }
                }
            }
            MenuEntry(TermFoldIcons.Share, stringResource(R.string.fm_share)) { act { share(listOf(f)) } }
            MenuEntry(TermFoldIcons.FolderExport, stringResource(R.string.fm_export)) { act { exportTo(listOf(f)) } }
            MenuEntry(TermFoldIcons.SaveToDevice, stringResource(if (item.isDir) R.string.fm_save_zip else R.string.fm_save_device)) { act { saveToDevice(f) } }
            MenuEntry(TermFoldIcons.Terminal, stringResource(R.string.fm_copy_path)) { act { copyPath(f) } }
            HorizontalDivider(color = Palette.Border, modifier = Modifier.padding(vertical = 4.dp))
            MenuEntry(TermFoldIcons.Pencil, stringResource(R.string.files_rename)) { act { opError = null; naming = Naming.Rename(f) } }
            MenuEntry(TermFoldIcons.Copy, stringResource(R.string.action_copy)) { act { clip = Clip(listOf(f), cut = false) } }
            MenuEntry(TermFoldIcons.MoveTo, stringResource(R.string.fm_cut)) { act { clip = Clip(listOf(f), cut = true) } }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.files_delete), color = Palette.Pink) },
                leadingIcon = { Icon(TermFoldIcons.Trash, null, tint = Palette.Pink, modifier = Modifier.size(18.dp)) },
                onClick = { act { confirmDelete = listOf(f) } },
            )
        }
    }
    val itemActions = ItemActions(
        selected = selected,
        iconsReady = iconsReady,
        onOpen = { item -> if (selected.isNotEmpty()) toggle(item) else open(item) },
        onLongPress = { item -> toggle(item) },
        onMenu = { item -> menuFor = item.file },
        menu = itemMenu,
    )

    val edge = if (wide) 28.dp else 16.dp
    Row(modifier.fillMaxSize()) {
        if (wide) {
            PlacesPane(
                cwd = cwd,
                projects = projects,
                onGo = ::go,
                modifier = Modifier.width(236.dp).fillMaxHeight(),
            )
            Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.BorderSoft))
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize()) {
                // ---- Header; while items are selected, the selection bar slides over its top row.
                Box {
                    Header(
                        title = placeTitle(cwd),
                        cwd = cwd,
                        wide = wide,
                        edge = edge,
                        projects = projects,
                        canGoUp = cwd != "/",
                        readOnly = readOnly,
                        onUp = { go(cwd.substringBeforeLast('/')) },
                        onGo = ::go,
                        query = query,
                        onQuery = { query = it },
                        searchOpen = searchOpen,
                        onSearchToggle = {
                            searchOpen = !searchOpen
                            if (!searchOpen) query = ""
                        },
                        clip = clip,
                        onPaste = {
                            val c = clip ?: return@Header
                            run(context.getString(R.string.fm_pasting)) {
                                c.files.forEach { f -> if (c.cut) FileActions.moveInto(f, dir) else FileActions.copyInto(f, dir) }
                            }
                            clip = null
                        },
                        onClearClip = { clip = null },
                        onUploadFiles = { upload.launch(arrayOf("*/*")) },
                        onUploadFolder = { uploadFolder.launch(null) },
                        onNewFile = { opError = null; naming = Naming.NewFile(dir) },
                        onNewFolder = { opError = null; naming = Naming.NewFolder(dir) },
                        sort = sort,
                        onSort = { sortName = it.name },
                        grid = grid,
                        onGrid = { grid = it },
                        showHidden = showHidden,
                        onShowHidden = { showHidden = it },
                    )
                    androidx.compose.animation.AnimatedVisibility(
                        visible = selected.isNotEmpty(),
                        enter = fadeIn(tween(160)) + slideInVertically(tween(200)) { -it / 3 },
                        exit = fadeOut(tween(140)) + slideOutVertically(tween(160)) { -it / 3 },
                    ) {
                        var count by remember { mutableIntStateOf(0) }
                        if (selected.isNotEmpty()) count = selected.size
                        SelectionBar(
                            count = count,
                            wide = wide,
                            readOnly = selectedFiles.any { !isUser(it) },
                            edge = edge,
                            onClose = { selected = emptySet() },
                            onSelectAll = {
                                scope.launch {
                                    selected = withContext(Dispatchers.IO) {
                                        (dir.listFiles() ?: emptyArray()).filter { showHidden || !it.name.startsWith(".") }.map { it.absolutePath }.toSet()
                                    }
                                }
                            },
                            onCopy = { clip = Clip(selectedFiles, cut = false); selected = emptySet() },
                            onCut = { clip = Clip(selectedFiles, cut = true); selected = emptySet() },
                            onShare = { share(selectedFiles); selected = emptySet() },
                            onExport = { exportTo(selectedFiles); selected = emptySet() },
                            onDelete = { opError = null; confirmDelete = selectedFiles },
                        )
                    }
                }

                // ---- The listing: the folder, or search results within it.
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (searching) {
                        SearchResults(
                            root = dir,
                            query = query.trim(),
                            showHidden = showHidden,
                            refresh = refresh,
                            edge = edge,
                            actions = itemActions,
                        )
                    } else {
                        AnimatedContent(
                            targetState = cwd,
                            transitionSpec = {
                                val deeper = depth(targetState) > depth(initialState)
                                val sign = if (deeper) 1 else -1
                                (slideInHorizontally(tween(240)) { sign * it / 6 } + fadeIn(tween(240))) togetherWith
                                    (slideOutHorizontally(tween(200)) { -sign * it / 8 } + fadeOut(tween(160)))
                            },
                            label = "folder",
                        ) { path ->
                            FolderListing(
                                dir = File(rootfs, path.trimStart('/')),
                                showHidden = showHidden,
                                sort = sort,
                                grid = grid,
                                wide = wide,
                                readOnly = !isGuestHome(path),
                                refresh = refresh,
                                edge = edge,
                                actions = itemActions,
                            )
                        }
                    }
                }
            }

            // ---- Status
            val status = busy ?: message
            androidx.compose.animation.AnimatedVisibility(
                visible = status != null,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp, start = 16.dp, end = 16.dp),
            ) {
                var shown by remember { mutableStateOf("") }
                if (status != null) shown = status
                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Palette.CardPressed)
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (busy != null) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Palette.Accent)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(shown, style = MaterialTheme.typography.bodySmall, color = Palette.Text)
                }
            }

            // ---- Editor or viewer over the listing
            androidx.compose.animation.AnimatedVisibility(
                visible = editing != null,
                enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 12 },
                exit = fadeOut(tween(150)),
            ) {
                var shownFile by remember { mutableStateOf<File?>(null) }
                editing?.let { shownFile = it }
                shownFile?.let { file ->
                    CodeEditor(
                        file = file,
                        root = rootfs,
                        readOnly = !isUser(file),
                        onDirtyChange = {},
                        onClose = { editing = null },
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Palette.Bg)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.End)),
                    )
                }
            }
            BackHandler(enabled = editing != null) { editing = null }
        }
    }

    naming?.let { n ->
        NameDialog(
            naming = n,
            error = opError,
            onConfirm = { name ->
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            when (n) {
                                is Naming.NewFile -> createEntry(n.inDir, name, folder = false)
                                is Naming.NewFolder -> createEntry(n.inDir, name, folder = true)
                                is Naming.Rename -> moveEntry(n.target, n.target.parentFile ?: n.target, name)
                            }
                        }
                    }
                    result.onSuccess { made ->
                        naming = null
                        reload()
                        if (n is Naming.NewFile) editing = made
                    }.onFailure { opError = it.message }
                }
            },
            onDismiss = { naming = null },
        )
    }

    confirmDelete?.let { targets ->
        fun done() {
            confirmDelete = null
            selected = emptySet()
        }
        if (targets.size == 1) {
            DeleteDialog(
                file = targets[0],
                error = opError,
                onConfirm = {
                    run(context.getString(R.string.fm_deleting)) {
                        check(deleteTree(targets[0])) {
                            context.getString(R.string.files_delete_failed, context.getString(R.string.files_delete_denied))
                        }
                    }
                    done()
                },
                onDismiss = { confirmDelete = null },
            )
        } else {
            ConfirmDialog(
                title = stringResource(R.string.fm_delete_n, targets.size),
                body = stringResource(R.string.fm_delete_n_body),
                confirmLabel = stringResource(R.string.files_delete),
                onConfirm = {
                    run(context.getString(R.string.fm_deleting)) { targets.forEach { deleteTree(it) } }
                    done()
                },
                onDismiss = { confirmDelete = null },
            )
        }
    }
}

/** What a listed item does when tapped, long-pressed, or its menu is opened. */
private class ItemActions(
    val selected: Set<String>,
    val iconsReady: Boolean,
    val onOpen: (Item) -> Unit,
    val onLongPress: (Item) -> Unit,
    val onMenu: (Item) -> Unit,
    val menu: @Composable (Item) -> Unit,
)

/** Home and everything in it (projects included): the part of the system that is yours. */
private fun isGuestHome(path: String) = path == HOME || path.startsWith("$HOME/")

private fun depth(path: String) = path.trim('/').split('/').count { it.isNotEmpty() }

@Composable
private fun placeTitle(cwd: String): String = when (cwd) {
    HOME -> stringResource(R.string.fm_home)
    Projects.GUEST_DIR -> stringResource(R.string.folders_title)
    "/" -> stringResource(R.string.fm_system)
    else -> cwd.substringAfterLast('/')
}

// ---- Header ------------------------------------------------------------------------------------

@Composable
private fun Header(
    title: String,
    cwd: String,
    wide: Boolean,
    edge: androidx.compose.ui.unit.Dp,
    projects: List<String>,
    canGoUp: Boolean,
    readOnly: Boolean,
    onUp: () -> Unit,
    onGo: (String) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    searchOpen: Boolean,
    onSearchToggle: () -> Unit,
    clip: Clip?,
    onPaste: () -> Unit,
    onClearClip: () -> Unit,
    onUploadFiles: () -> Unit,
    onUploadFolder: () -> Unit,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    sort: SortBy,
    onSort: (SortBy) -> Unit,
    grid: Boolean,
    onGrid: (Boolean) -> Unit,
    showHidden: Boolean,
    onShowHidden: (Boolean) -> Unit,
) {
    var uploadMenu by remember { mutableStateOf(false) }
    var newMenu by remember { mutableStateOf(false) }
    var viewMenu by remember { mutableStateOf(false) }
    var placesMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(top = if (wide) 18.dp else 8.dp)) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(start = if (canGoUp) edge - 10.dp else edge, end = edge - 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(
                visible = canGoUp,
                enter = fadeIn() + expandHorizontally(),
                exit = fadeOut() + shrinkHorizontally(),
            ) {
                BareIconButton(icon = TermFoldIcons.Back, contentDescription = stringResource(R.string.fm_up), onClick = onUp)
            }
            // The title: on a phone it opens the places menu (the tablet has the pane).
            Box(Modifier.weight(1f)) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = !wide) { placesMenu = true }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AnimatedContent(
                        targetState = title,
                        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                        label = "title",
                    ) { t ->
                        Text(
                            t,
                            style = if (wide) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium.copy(fontSize = 21.sp),
                            color = Palette.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!wide) {
                        Spacer(Modifier.width(4.dp))
                        Icon(TermFoldIcons.ChevronDown, null, tint = Palette.TextFaint, modifier = Modifier.size(16.dp))
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = readOnly,
                        enter = fadeIn() + expandHorizontally(),
                        exit = fadeOut() + shrinkHorizontally(),
                    ) {
                        Text(
                            stringResource(R.string.fm_read_only),
                            style = MaterialTheme.typography.labelSmall,
                            color = Palette.TextDim,
                            maxLines = 1,
                            modifier = Modifier
                                .padding(start = 10.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Palette.Card)
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                }
                DropdownMenu(
                    expanded = placesMenu,
                    onDismissRequest = { placesMenu = false },
                    containerColor = Palette.Card,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    MenuEntry(TermFoldIcons.Home, stringResource(R.string.fm_home)) { placesMenu = false; onGo(HOME) }
                    projects.forEach { name ->
                        MenuEntry(TermFoldIcons.Folder, name) { placesMenu = false; onGo(Projects.GUEST_DIR + "/" + name) }
                    }
                    MenuEntry(TermFoldIcons.Terminal, stringResource(R.string.fm_system)) { placesMenu = false; onGo("/") }
                }
            }

            if (wide) {
                SearchBox(query = query, onQuery = onQuery, autoFocus = false, modifier = Modifier.width(260.dp))
                Spacer(Modifier.width(6.dp))
            } else {
                HeaderIcon(if (searchOpen) TermFoldIcons.Close else TermFoldIcons.Search, stringResource(R.string.cd_search), active = searchOpen, onClick = onSearchToggle)
            }
            if (!readOnly) Box {
                HeaderIcon(TermFoldIcons.Upload, stringResource(R.string.fm_upload)) { uploadMenu = true }
                DropdownMenu(
                    expanded = uploadMenu,
                    onDismissRequest = { uploadMenu = false },
                    containerColor = Palette.Card,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    MenuEntry(TermFoldIcons.FileText, stringResource(R.string.fm_upload_files)) { uploadMenu = false; onUploadFiles() }
                    MenuEntry(TermFoldIcons.FolderImport, stringResource(R.string.fm_upload_folder)) { uploadMenu = false; onUploadFolder() }
                }
            }
            if (!readOnly) Box {
                HeaderIcon(TermFoldIcons.Plus, stringResource(R.string.fm_new)) { newMenu = true }
                DropdownMenu(
                    expanded = newMenu,
                    onDismissRequest = { newMenu = false },
                    containerColor = Palette.Card,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    MenuEntry(TermFoldIcons.FilePlus, stringResource(R.string.files_new_file)) { newMenu = false; onNewFile() }
                    MenuEntry(TermFoldIcons.FolderPlus, stringResource(R.string.files_new_folder)) { newMenu = false; onNewFolder() }
                }
            }
            Box {
                HeaderIcon(TermFoldIcons.Sliders, stringResource(R.string.fm_view)) { viewMenu = true }
                DropdownMenu(
                    expanded = viewMenu,
                    onDismissRequest = { viewMenu = false },
                    containerColor = Palette.Card,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    MenuLabel(stringResource(R.string.fm_sort))
                    CheckEntry(stringResource(R.string.fm_sort_name), sort == SortBy.NAME) { onSort(SortBy.NAME) }
                    CheckEntry(stringResource(R.string.fm_sort_modified), sort == SortBy.MODIFIED) { onSort(SortBy.MODIFIED) }
                    CheckEntry(stringResource(R.string.fm_sort_size), sort == SortBy.SIZE) { onSort(SortBy.SIZE) }
                    HorizontalDivider(color = Palette.Border, modifier = Modifier.padding(vertical = 4.dp))
                    MenuLabel(stringResource(R.string.fm_view))
                    CheckEntry(stringResource(R.string.fm_view_list), !grid, TermFoldIcons.ListView) { onGrid(false) }
                    CheckEntry(stringResource(R.string.fm_view_grid), grid, TermFoldIcons.Grid) { onGrid(true) }
                    HorizontalDivider(color = Palette.Border, modifier = Modifier.padding(vertical = 4.dp))
                    CheckEntry(stringResource(R.string.fm_hidden), showHidden, if (showHidden) TermFoldIcons.Eye else TermFoldIcons.EyeOff) { onShowHidden(!showHidden) }
                }
            }
        }

        AnimatedVisibility(
            visible = !wide && searchOpen,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            SearchBox(query = query, onQuery = onQuery, autoFocus = true, modifier = Modifier.fillMaxWidth().padding(start = edge, end = edge, top = 6.dp))
        }

        // At the top of Home or of the system the title already says where this is.
        androidx.compose.animation.AnimatedVisibility(
            visible = cwd != HOME && cwd != "/",
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Breadcrumbs(cwd, onGo = onGo, modifier = Modifier.padding(start = edge - 6.dp, end = edge, top = 4.dp, bottom = 2.dp))
        }

        // Waiting to be pasted: stays visible while browsing to the destination.
        AnimatedVisibility(
            visible = clip != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            var shown by remember { mutableStateOf<Clip?>(null) }
            clip?.let { shown = it }
            val c = shown ?: return@AnimatedVisibility
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = edge, end = edge, top = 6.dp, bottom = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.Card)
                    .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (c.cut) TermFoldIcons.MoveTo else TermFoldIcons.Copy, null, tint = Palette.TextDim, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    pluralStringResource(if (c.cut) R.plurals.fm_clip_cut else R.plurals.fm_clip_copied, c.files.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextDim,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(if (readOnly) R.string.fm_paste_in_home else R.string.fm_paste_here),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
                    color = if (readOnly) Palette.TextFaint else Palette.Accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !readOnly, onClick = onPaste)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                )
                BareIconButton(icon = TermFoldIcons.Close, contentDescription = stringResource(R.string.action_cancel), onClick = onClearClip, tint = Palette.TextFaint, size = 34)
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun pluralStringResource(id: Int, count: Int): String =
    androidx.compose.ui.res.pluralStringResource(id, count, count)

@Composable
private fun SelectionBar(
    count: Int,
    wide: Boolean,
    readOnly: Boolean,
    edge: androidx.compose.ui.unit.Dp,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var more by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.Bg)
            .padding(top = if (wide) 18.dp else 8.dp, start = edge - 10.dp, end = edge - 8.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.Card)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BareIconButton(icon = TermFoldIcons.Close, contentDescription = stringResource(R.string.action_cancel), onClick = onClose)
        AnimatedContent(targetState = count, label = "count", transitionSpec = {
            (slideInVertically { if (targetState > initialState) it else -it } + fadeIn()) togetherWith
                (slideOutVertically { if (targetState > initialState) -it else it } + fadeOut())
        }) { n ->
            Text(
                stringResource(R.string.fm_selected, n),
                style = MaterialTheme.typography.titleMedium,
                color = Palette.Text,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        // System files can only be copied (into Home) from here.
        if (wide || readOnly) HeaderIcon(TermFoldIcons.Check, stringResource(R.string.fm_select_all), onClick = onSelectAll)
        HeaderIcon(TermFoldIcons.Copy, stringResource(R.string.action_copy), onClick = onCopy)
        if (!readOnly) {
            HeaderIcon(TermFoldIcons.MoveTo, stringResource(R.string.fm_cut), onClick = onCut)
            HeaderIcon(TermFoldIcons.Share, stringResource(R.string.fm_share), onClick = onShare)
            if (wide) HeaderIcon(TermFoldIcons.FolderExport, stringResource(R.string.fm_export), onClick = onExport)
            HeaderIcon(TermFoldIcons.Trash, stringResource(R.string.files_delete), danger = true, onClick = onDelete)
        }
        if (!wide && !readOnly) {
            Box {
                HeaderIcon(TermFoldIcons.More, stringResource(R.string.cd_more)) { more = true }
                DropdownMenu(
                    expanded = more,
                    onDismissRequest = { more = false },
                    containerColor = Palette.Card,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    MenuEntry(TermFoldIcons.Check, stringResource(R.string.fm_select_all)) { more = false; onSelectAll() }
                    MenuEntry(TermFoldIcons.FolderExport, stringResource(R.string.fm_export)) { more = false; onExport() }
                }
            }
        }
    }
}

@Composable
private fun HeaderIcon(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val tint by animateColorAsState(
        when {
            danger -> Palette.Pink
            active -> Palette.Accent
            else -> Palette.TextDim
        },
        label = "headerIcon",
    )
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun MenuLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Palette.TextFaint,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun CheckEntry(label: String, checked: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = if (checked) Palette.Text else Palette.TextDim) },
        leadingIcon = icon?.let { { Icon(it, null, tint = if (checked) Palette.Text else Palette.TextFaint, modifier = Modifier.size(18.dp)) } },
        trailingIcon = {
            AnimatedVisibility(visible = checked, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                Icon(TermFoldIcons.Check, null, tint = Palette.Accent, modifier = Modifier.size(16.dp))
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun SearchBox(query: String, onQuery: (String) -> Unit, autoFocus: Boolean, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Field)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TermFoldIcons.Search, null, tint = Palette.TextFaint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(stringResource(R.string.fm_search_hint), style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp), color = Palette.TextFaint, maxLines = 1)
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, color = Palette.Text),
                cursorBrush = SolidColor(Palette.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        AnimatedVisibility(visible = query.isNotEmpty(), enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
            BareIconButton(icon = TermFoldIcons.Close, contentDescription = stringResource(R.string.action_cancel), onClick = { onQuery("") }, tint = Palette.TextFaint, size = 32)
        }
    }
}

/** The path as tappable segments: / › ~ › projects › app. */
@Composable
private fun Breadcrumbs(cwd: String, onGo: (String) -> Unit, modifier: Modifier = Modifier) {
    val parts = cwd.trim('/').split('/').filter { it.isNotEmpty() }
    val home = parts.isNotEmpty() && parts[0] == "root"
    val scroll = rememberScrollState()
    LaunchedEffect(cwd) { scroll.animateScrollTo(scroll.maxValue) }
    Row(
        modifier.fillMaxWidth().horizontalScroll(scroll),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        @Composable
        fun crumb(label: String, target: String, last: Boolean) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono, fontSize = 12.sp),
                color = if (last) Palette.TextDim else Palette.TextFaint,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = !last) { onGo(target) }
                    .padding(horizontal = 5.dp, vertical = 4.dp),
            )
            if (!last && label != "/") Text("/", color = Palette.Border, style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono, fontSize = 12.sp))
        }
        if (home) {
            crumb("~", HOME, parts.size == 1)
            parts.drop(1).forEachIndexed { i, p ->
                crumb(p, "/" + parts.take(i + 2).joinToString("/"), i == parts.size - 2)
            }
        } else {
            crumb("/", "/", parts.isEmpty())
            parts.forEachIndexed { i, p -> crumb(p, "/" + parts.take(i + 1).joinToString("/"), i == parts.lastIndex) }
        }
    }
}

// ---- Places (tablet) ---------------------------------------------------------------------------

@Composable
private fun PlacesPane(cwd: String, projects: List<String>, onGo: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 14.dp, end = 14.dp, top = 22.dp, bottom = 24.dp),
    ) {
        Text(
            stringResource(R.string.files_title),
            style = MaterialTheme.typography.titleLarge,
            color = Palette.Text,
            modifier = Modifier.padding(start = 10.dp, bottom = 18.dp),
        )
        val active = when {
            cwd == HOME -> HOME
            cwd.startsWith(Projects.GUEST_DIR + "/") -> Projects.GUEST_DIR + "/" + cwd.removePrefix(Projects.GUEST_DIR + "/").substringBefore('/')
            cwd.startsWith("$HOME/") -> HOME
            else -> "/"
        }
        PlaceRow(TermFoldIcons.Home, stringResource(R.string.fm_home), active == HOME) { onGo(HOME) }
        PlaceRow(TermFoldIcons.Terminal, stringResource(R.string.fm_system), active == "/") { onGo("/") }
        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(R.string.folders_title).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = Palette.TextFaint,
            modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
        )
        if (projects.isEmpty()) {
            Text(
                stringResource(R.string.fm_no_projects),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextFaint,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp),
            )
        }
        projects.forEach { name ->
            val path = Projects.GUEST_DIR + "/" + name
            PlaceRow(TermFoldIcons.Folder, name, active == path) { onGo(path) }
        }
    }
}

@Composable
private fun PlaceRow(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (active) Palette.CardPressed else Color.Transparent, tween(180), label = "placeBg")
    val fg by animateColorAsState(if (active) Palette.Text else Palette.TextDim, tween(180), label = "placeFg")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (active) Palette.Accent else Palette.TextFaint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---- Listings ----------------------------------------------------------------------------------

@Composable
private fun FolderListing(
    dir: File,
    showHidden: Boolean,
    sort: SortBy,
    grid: Boolean,
    wide: Boolean,
    readOnly: Boolean,
    refresh: Int,
    edge: androidx.compose.ui.unit.Dp,
    actions: ItemActions,
) {
    var items by remember(dir) { mutableStateOf<List<Item>?>(null) }
    var readable by remember(dir) { mutableStateOf(true) }
    // The listing follows the disk: agents and shells change files while this is open.
    LaunchedWhileVisible(dir, showHidden, sort, refresh) {
        while (true) {
            val (canRead, list) = withContext(Dispatchers.IO) { (dir.list() != null) to list(dir, showHidden, sort) }
            readable = canRead
            if (list != items) items = list
            delay(2500)
        }
    }
    val list = items
    when {
        list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Palette.Accent)
        }
        !readable -> Empty(
            icon = TermFoldIcons.Folder,
            title = stringResource(R.string.fm_unreadable),
            hint = stringResource(R.string.fm_unreadable_hint),
        )
        list.isEmpty() -> Empty(
            icon = TermFoldIcons.Folder,
            title = stringResource(R.string.files_empty),
            hint = stringResource(if (readOnly) R.string.fm_system_hint else R.string.fm_empty_hint),
        )
        grid -> LazyVerticalGrid(
            columns = GridCells.Adaptive(if (wide) 132.dp else 104.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = edge - 6.dp, end = edge - 6.dp, top = 6.dp, bottom = 80.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(list, key = { it.key }) { item ->
                Tile(item, actions, Modifier.animateItem())
            }
        }
        else -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = edge - 8.dp, end = edge - 8.dp, top = 2.dp, bottom = 80.dp),
        ) {
            items(list, key = { it.key }) { item ->
                ItemRow(item, describe(item), wide, actions, Modifier.animateItem())
            }
        }
    }
}

@Composable
private fun SearchResults(
    root: File,
    query: String,
    showHidden: Boolean,
    refresh: Int,
    edge: androidx.compose.ui.unit.Dp,
    actions: ItemActions,
) {
    var results by remember { mutableStateOf<List<Item>?>(null) }
    LaunchedEffect(root, query, showHidden, refresh) {
        delay(220)
        results = null
        results = withContext(Dispatchers.IO) { search(root, query, showHidden) }
    }
    val list = results
    when {
        list == null -> Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.TopCenter) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Palette.Accent)
        }
        list.isEmpty() -> Empty(
            icon = TermFoldIcons.Search,
            title = stringResource(R.string.fm_no_results, query),
            hint = stringResource(R.string.fm_search_scope),
        )
        else -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = edge - 8.dp, end = edge - 8.dp, top = 2.dp, bottom = 80.dp),
        ) {
            items(list, key = { it.key }) { item ->
                val where = item.file.parentFile?.relativeToOrNull(root)?.path?.replace('\\', '/').orEmpty()
                val subtitle = if (where.isEmpty()) describe(item) else where
                ItemRow(item, subtitle, wide = false, actions = actions, modifier = Modifier.animateItem(), highlight = query)
            }
            if (list.size >= SEARCH_LIMIT) {
                item {
                    Text(
                        stringResource(R.string.fm_search_more, SEARCH_LIMIT),
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextFaint,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemRow(
    item: Item,
    subtitle: String,
    wide: Boolean,
    actions: ItemActions,
    modifier: Modifier = Modifier,
    highlight: String? = null,
) {
    val isSel = item.key in actions.selected
    val bg by animateColorAsState(if (isSel) Palette.CardPressed else Color.Transparent, tween(160), label = "rowBg")
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .combinedClickable(onClick = { actions.onOpen(item) }, onLongClick = { actions.onLongPress(item) })
                .padding(start = 10.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumb(item, actions.iconsReady, size = 36)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    highlighted(item.file.name, highlight),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (item.file.name.startsWith(".")) Palette.TextDim else Palette.Text,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                    color = Palette.TextFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (wide) {
                Text(
                    if (item.isDir) "" else humanSize(item.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextFaint,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(84.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = actions.selected.isEmpty() to isSel,
                    transitionSpec = { (scaleIn(initialScale = 0.6f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.6f) + fadeOut()) },
                    label = "trailing",
                ) { (idle, sel) ->
                    when {
                        idle -> BareIconButton(
                            icon = TermFoldIcons.More,
                            contentDescription = stringResource(R.string.cd_more),
                            onClick = { actions.onMenu(item) },
                            tint = Palette.TextFaint,
                            size = 40,
                        )
                        else -> SelectMark(sel)
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.TopEnd)) { actions.menu(item) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(item: Item, actions: ItemActions, modifier: Modifier = Modifier) {
    val isSel = item.key in actions.selected
    val bg by animateColorAsState(if (isSel) Palette.CardPressed else Palette.Card, tween(160), label = "tileBg")
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(bg)
                .combinedClickable(onClick = { actions.onOpen(item) }, onLongClick = { actions.onLongPress(item) })
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(1.25f).clip(RoundedCornerShape(10.dp)).background(Palette.Bg), contentAlignment = Alignment.Center) {
                if (FileActions.isImage(item.file)) {
                    ImageThumb(item, Modifier.fillMaxSize())
                } else if (actions.iconsReady) {
                    FileTypeIcon(item.file.name, item.isDir, open = false, size = 38.dp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                item.file.name,
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Text,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box(Modifier.align(Alignment.TopEnd).padding(10.dp)) {
            AnimatedVisibility(visible = actions.selected.isNotEmpty(), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                SelectMark(isSel)
            }
            actions.menu(item)
        }
        if (actions.selected.isEmpty()) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Palette.Card.copy(alpha = 0.85f))
                    .clickable { actions.onMenu(item) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(TermFoldIcons.More, stringResource(R.string.cd_more), tint = Palette.TextDim, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun SelectMark(selected: Boolean) {
    val bg by animateColorAsState(if (selected) Palette.Accent else Color.Transparent, tween(140), label = "markBg")
    Box(
        Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.5.dp, if (selected) Palette.Accent else Palette.TextFaint, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(visible = selected, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
            Icon(TermFoldIcons.Check, null, tint = Palette.OnAccent, modifier = Modifier.size(14.dp))
        }
    }
}

/** A picture's thumbnail, or the file-type icon for everything else. */
@Composable
private fun Thumb(item: Item, iconsReady: Boolean, size: Int) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (FileActions.isImage(item.file)) {
            ImageThumb(item, Modifier.fillMaxSize().background(Palette.Card))
        } else if (iconsReady) {
            FileTypeIcon(item.file.name, item.isDir, open = false, size = 24.dp)
        }
    }
}

@Composable
private fun ImageThumb(item: Item, modifier: Modifier) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(item.file)
            .size(320)
            .memoryCacheKey(item.key + ":" + item.modified)
            .crossfade(180)
            .build(),
        imageLoader = AgentIconLoader.of(context),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

@Composable
private fun Empty(icon: ImageVector, title: String, hint: String) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp).padding(bottom = 60.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Card), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Palette.TextFaint, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(hint, style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint, textAlign = TextAlign.Center)
    }
}

@Composable
private fun highlighted(name: String, query: String?): androidx.compose.ui.text.AnnotatedString {
    if (query.isNullOrEmpty()) return androidx.compose.ui.text.AnnotatedString(name)
    val at = name.indexOf(query, ignoreCase = true)
    return androidx.compose.ui.text.buildAnnotatedString {
        append(name)
        if (at >= 0) addStyle(androidx.compose.ui.text.SpanStyle(color = Palette.Accent), at, at + query.length)
    }
}

// ---- Disk --------------------------------------------------------------------------------------

private fun itemOf(f: File, isDir: Boolean) = Item(f, isDir, if (isDir) 0L else f.length(), f.lastModified())

private fun list(dir: File, showHidden: Boolean, sort: SortBy): List<Item> {
    val all = runCatching { listChildren(dir) }.getOrDefault(emptyList())
        .filter { showHidden || !it.file.name.startsWith(".") }
        .map { itemOf(it.file, it.isDir) }
    val order = when (sort) {
        SortBy.NAME -> compareBy<Item>({ !it.isDir }, { it.file.name.lowercase() })
        SortBy.MODIFIED -> compareBy<Item>({ !it.isDir }, { -it.modified })
        SortBy.SIZE -> compareBy<Item>({ !it.isDir }, { -it.size }, { it.file.name.lowercase() })
    }
    return all.sortedWith(order)
}

private val SEARCH_SKIP = setOf("node_modules", ".git", "proc", "sys", "dev", "__pycache__", ".cache")

/** Names containing [query] anywhere under [root], nearest first, without following links. */
private suspend fun search(root: File, query: String, showHidden: Boolean): List<Item> {
    val out = ArrayList<Item>()
    val queue = ArrayDeque<File>()
    queue.add(root)
    val context = coroutineContext
    while (queue.isNotEmpty() && out.size < SEARCH_LIMIT && context.isActive) {
        val dir = queue.removeFirst()
        val kids = dir.listFiles() ?: continue
        kids.sortBy { it.name.lowercase() }
        for (f in kids) {
            if (out.size >= SEARCH_LIMIT) break
            if (!showHidden && f.name.startsWith(".")) continue
            val link = Files.isSymbolicLink(f.toPath())
            val isDir = f.isDirectory
            if (f.name.contains(query, ignoreCase = true)) out += itemOf(f, isDir)
            if (isDir && !link && f.name !in SEARCH_SKIP) queue.add(f)
        }
    }
    return out.sortedWith(compareBy<Item>({ !it.isDir }, { it.file.path.count { c -> c == '/' } }, { it.file.name.lowercase() }))
}

private fun describe(item: Item): String {
    val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.modified))
    return if (item.isDir) date else "${humanSize(item.size)} · $date"
}
