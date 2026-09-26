package com.termfold.app.ui.screens

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termfold.app.R
import com.termfold.app.core.Folder
import com.termfold.app.ui.components.BareIconButton
import com.termfold.app.ui.theme.Mono
import com.termfold.app.ui.theme.Palette
import com.termfold.app.ui.theme.TermFoldIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The project's files beside whatever is on screen (a chat, a shell or the folder's session
 * list): a file tree in a sidebar, and an editor over the content when a file is opened.
 *
 * On a wide screen the tree is docked at the start and the content keeps working next to it;
 * on a phone it slides in as a drawer. The content stays composed under the editor, so a
 * running shell or agent is not disturbed by looking at a file.
 */
@Composable
fun FilesWorkspace(
    folder: Folder?,
    filesOpen: Boolean,
    onCloseFiles: () -> Unit,
    wide: Boolean,
    content: @Composable () -> Unit,
) {
    val root = folder?.path?.takeIf { it.isNotBlank() }?.let(::File)
    var editing by remember(folder?.id) { mutableStateOf<File?>(null) }
    var editorDirty by remember { mutableStateOf(false) }
    // The file to switch to once the user decides about unsaved changes (null: close).
    var confirmLeave by remember { mutableStateOf<Pair<Boolean, File?>?>(null) }
    val showPanel = filesOpen && folder != null

    fun openFile(file: File) {
        if (file == editing) {
            if (!wide) onCloseFiles()
            return
        }
        if (editing != null && editorDirty) {
            confirmLeave = true to file
        } else {
            editing = file
            editorDirty = false
        }
        if (!wide) onCloseFiles()
    }

    fun closeEditor() {
        if (editorDirty) confirmLeave = true to null else editing = null
    }

    val tree: @Composable (Modifier) -> Unit = { mod ->
        if (folder != null) {
            FileTree(
                folderName = folder.name,
                root = root,
                selected = editing,
                onOpen = ::openFile,
                onMoved = { from, to ->
                    // The open file (or the folder holding it) was renamed or moved: follow it.
                    val open = editing
                    if (open != null && (open == from || open.path.startsWith(from.path + File.separator))) {
                        editing = File(to, open.path.removePrefix(from.path).trimStart(File.separatorChar)).takeIf { open != from } ?: to
                    }
                },
                onDeleted = { gone ->
                    // A deleted file (or one inside a deleted folder) cannot stay open.
                    val open = editing
                    if (open != null && (open == gone || open.path.startsWith(gone.path + File.separator))) {
                        editorDirty = false
                        editing = null
                    }
                },
                onClose = onCloseFiles,
                modifier = mod,
            )
        }
    }

    Row(Modifier.fillMaxSize()) {
        if (wide) {
            AnimatedVisibility(
                visible = showPanel,
                enter = slideInHorizontally { -it } + fadeIn(),
                exit = slideOutHorizontally { -it } + fadeOut(),
            ) {
                Row {
                    tree(
                        Modifier
                            .width(300.dp)
                            .fillMaxHeight()
                            .background(Palette.Bg)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom)),
                    )
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.BorderSoft))
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            content()

            editing?.let { file ->
                CodeEditor(
                    file = file,
                    root = root,
                    onDirtyChange = { editorDirty = it },
                    onClose = ::closeEditor,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Palette.Bg)
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                if (wide) {
                                    WindowInsetsSides.Top + WindowInsetsSides.End + WindowInsetsSides.Bottom
                                } else {
                                    WindowInsetsSides.Horizontal + WindowInsetsSides.Vertical
                                },
                            ),
                        ),
                )
            }

            if (!wide) DrawerOverlay(visible = showPanel, onDismiss = onCloseFiles, panel = tree)
        }
    }

    // Back closes the drawer first, then the editor.
    BackHandler(enabled = showPanel && !wide) { onCloseFiles() }
    BackHandler(enabled = editing != null && !(showPanel && !wide)) { closeEditor() }

    confirmLeave?.let { (_, next) ->
        AlertDialog(
            onDismissRequest = { confirmLeave = null },
            containerColor = Palette.Card,
            shape = RoundedCornerShape(20.dp),
            title = { Text(stringResource(R.string.files_unsaved_title), color = Palette.Text) },
            text = {
                Text(
                    stringResource(R.string.files_unsaved_body, editing?.name.orEmpty()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextDim,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = null
                    editorDirty = false
                    editing = next
                }) { Text(stringResource(R.string.files_discard), color = Palette.Pink) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = null }) {
                    Text(stringResource(R.string.action_cancel), color = Palette.TextDim)
                }
            },
        )
    }
}

/** The phone layout: the tree slides in over the content, which dims behind it. */
@Composable
private fun DrawerOverlay(visible: Boolean, onDismiss: () -> Unit, panel: @Composable (Modifier) -> Unit) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally { -it },
        exit = slideOutHorizontally { -it },
    ) {
        BoxWithConstraints {
            panel(
                Modifier
                    .width(minOf(maxWidth * 0.86f, 360.dp))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp))
                    .background(Palette.Card)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.Start,
                        ),
                    ),
            )
        }
    }
}

// ---- The tree ---------------------------------------------------------------------------------

private data class TreeRow(val file: File, val depth: Int, val isDir: Boolean)

/** A directory entry with its type read once, off the main thread. */
private data class Entry(val file: File, val isDir: Boolean)

private fun listChildren(dir: File): List<Entry> =
    (dir.listFiles() ?: emptyArray())
        .map { Entry(it, it.isDirectory) }
        .sortedWith(compareBy<Entry>({ !it.isDir }, { it.file.name.lowercase() }, { it.file.name }))

/**
 * Deletes a file, or a folder and everything in it, without following symbolic links: a link
 * inside the folder is removed as a link, never used to reach and delete what it points to.
 */
private fun deleteTree(target: File): Boolean {
    val root = target.toPath()
    if (!java.nio.file.Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        return runCatching { java.nio.file.Files.deleteIfExists(root) }.isSuccess && !target.exists()
    }
    return runCatching {
        java.nio.file.Files.walkFileTree(
            root,
            object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes) =
                    java.nio.file.FileVisitResult.CONTINUE.also { java.nio.file.Files.delete(file) }

                override fun postVisitDirectory(dir: java.nio.file.Path, exc: java.io.IOException?) =
                    java.nio.file.FileVisitResult.CONTINUE.also { java.nio.file.Files.delete(dir) }
            },
        )
    }.isSuccess && !target.exists()
}

@Composable
private fun FileTree(
    folderName: String,
    root: File?,
    selected: File?,
    onOpen: (File) -> Unit,
    onMoved: (from: File, to: File) -> Unit,
    onDeleted: (File) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Expanded directories and their listings. Listings are read off the main thread and
    // refreshed every few seconds, so files an agent or a shell creates show up on their own.
    val expanded = rememberSaveable(root?.path, saver = PathSetSaver) { mutableSetOf() }
    var expandedVersion by remember { mutableStateOf(0) }
    val listings = remember(root?.path) { mutableStateMapOf<String, List<Entry>>() }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(root?.path, expandedVersion, refreshTick) {
        if (root == null) return@LaunchedEffect
        val dirs = listOf(root.path) + expanded.toList()
        val fresh = withContext(Dispatchers.IO) {
            dirs.associateWith { runCatching { listChildren(File(it)) }.getOrDefault(emptyList()) }
        }
        fresh.forEach { (path, list) -> if (listings[path] != list) listings[path] = list }
    }
    LaunchedEffect(root?.path) {
        while (true) {
            delay(3_000)
            refreshTick++
        }
    }

    val rows = remember(listings.toMap(), expandedVersion, root?.path) {
        val out = mutableListOf<TreeRow>()
        fun walk(dir: File, depth: Int) {
            listings[dir.path]?.forEach { (child, isDir) ->
                out += TreeRow(child, depth, isDir)
                if (isDir && child.path in expanded) walk(child, depth + 1)
            }
        }
        if (root != null) walk(root, 0)
        out
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    var iconsReady by remember { mutableStateOf(FileIcons.ready) }
    LaunchedEffect(Unit) {
        if (!iconsReady) {
            withContext(Dispatchers.IO) { FileIcons.load(context) }
            iconsReady = true
        }
    }
    // Long-press on a row offers Delete; deleting asks first.
    var menuFor by remember { mutableStateOf<File?>(null) }
    var confirmDelete by remember { mutableStateOf<File?>(null) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // Create / rename / move. For a folder, new items go inside it; for a file, beside it.
    var naming by remember { mutableStateOf<Naming?>(null) }
    var moving by remember { mutableStateOf<File?>(null) }
    var opError by remember { mutableStateOf<String?>(null) }
    fun afterChange(showDir: File?) {
        if (showDir != null && root != null && showDir.path != root.path && showDir.path.startsWith(root.path)) {
            // Make sure the folder that changed is open, so the result is visible.
            var d: File? = showDir
            while (d != null && d.path != root.path) { expanded.add(d.path); d = d.parentFile }
        }
        expandedVersion++
        refreshTick++
    }
    fun runOp(op: () -> File, done: (File) -> Unit) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(op) }
            result.onSuccess { made ->
                opError = null
                naming = null
                moving = null
                done(made)
            }.onFailure { opError = it.message ?: it.javaClass.simpleName }
        }
    }

    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.files_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = Palette.Text,
                )
                Text(
                    folderName,
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (root != null) {
                BareIconButton(
                    icon = TermFoldIcons.FilePlus,
                    contentDescription = stringResource(R.string.files_new_file),
                    onClick = { opError = null; naming = Naming.NewFile(root) },
                    tint = Palette.TextDim,
                    size = 36,
                )
                BareIconButton(
                    icon = TermFoldIcons.FolderPlus,
                    contentDescription = stringResource(R.string.files_new_folder),
                    onClick = { opError = null; naming = Naming.NewFolder(root) },
                    tint = Palette.TextDim,
                    size = 36,
                )
            }
            BareIconButton(
                icon = TermFoldIcons.Refresh,
                contentDescription = stringResource(R.string.files_refresh),
                onClick = { refreshTick++ },
                tint = Palette.TextDim,
                size = 36,
            )
            BareIconButton(
                icon = TermFoldIcons.Close,
                contentDescription = stringResource(R.string.action_close),
                onClick = onClose,
                tint = Palette.TextDim,
                size = 36,
            )
        }

        when {
            root == null -> TreeMessage(stringResource(R.string.files_no_path))
            listings[root.path] == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Palette.Accent)
            }
            rows.isEmpty() -> TreeMessage(stringResource(R.string.files_empty))
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                items(rows, key = { it.file.path }) { row ->
                    val isOpen = row.isDir && row.file.path in expanded
                    TreeRowView(
                        row = row,
                        open = isOpen,
                        loading = isOpen && listings[row.file.path] == null,
                        selected = row.file == selected,
                        iconsReady = iconsReady,
                        menuOpen = menuFor == row.file,
                        onLongClick = { menuFor = row.file },
                        onDismissMenu = { menuFor = null },
                        onDelete = { menuFor = null; confirmDelete = row.file },
                        onNewFile = { menuFor = null; opError = null; naming = Naming.NewFile(if (row.isDir) row.file else row.file.parentFile ?: row.file) },
                        onNewFolder = { menuFor = null; opError = null; naming = Naming.NewFolder(if (row.isDir) row.file else row.file.parentFile ?: row.file) },
                        onRename = { menuFor = null; opError = null; naming = Naming.Rename(row.file) },
                        onMove = { menuFor = null; opError = null; moving = row.file },
                        onClick = {
                            if (row.isDir) {
                                if (!expanded.remove(row.file.path)) expanded.add(row.file.path)
                                expandedVersion++
                            } else {
                                onOpen(row.file)
                            }
                        },
                    )
                }
            }
        }
    }

    confirmDelete?.let { target ->
        DeleteDialog(
            file = target,
            error = deleteError,
            onConfirm = {
                scope.launch {
                    val deleted = withContext(Dispatchers.IO) {
                        deleteTree(target)
                    }
                    if (deleted) {
                        confirmDelete = null
                        deleteError = null
                        expanded.removeAll { it == target.path || it.startsWith(target.path + File.separator) }
                        expandedVersion++
                        refreshTick++
                        onDeleted(target)
                    } else {
                        deleteError = context.getString(R.string.files_delete_denied)
                    }
                }
            },
            onDismiss = {
                confirmDelete = null
                deleteError = null
            },
        )
    }

    naming?.let { n ->
        NameDialog(
            naming = n,
            error = opError,
            onConfirm = { name ->
                when (n) {
                    is Naming.NewFile -> runOp({ createEntry(n.inDir, name, folder = false) }) { made ->
                        afterChange(made.parentFile)
                        onOpen(made)
                    }
                    is Naming.NewFolder -> runOp({ createEntry(n.inDir, name, folder = true) }) { made ->
                        afterChange(made)
                    }
                    is Naming.Rename -> runOp({ moveEntry(n.target, n.target.parentFile ?: n.target, name) }) { made ->
                        if (n.target.path in expanded) { expanded.remove(n.target.path); expanded.add(made.path) }
                        afterChange(made.parentFile)
                        onMoved(n.target, made)
                    }
                }
            },
            onDismiss = { naming = null; opError = null },
        )
    }

    moving?.let { target ->
        if (root != null) {
            MoveDialog(
                root = root,
                target = target,
                error = opError,
                onPick = { dest ->
                    runOp({ moveEntry(target, dest, target.name) }) { made ->
                        expanded.removeAll { it == target.path || it.startsWith(target.path + File.separator) }
                        afterChange(dest)
                        onMoved(target, made)
                    }
                },
                onDismiss = { moving = null; opError = null },
            )
        }
    }
}

/** What the name dialog is for. */
private sealed interface Naming {
    data class NewFile(val inDir: File) : Naming
    data class NewFolder(val inDir: File) : Naming
    data class Rename(val target: File) : Naming
}

/** Checks a single file or folder name typed by the user; null when it is fine. */
private fun badName(name: String): Int? = when {
    name.isBlank() -> R.string.files_name_empty
    name == "." || name == ".." || '/' in name || '\\' in name || '\u0000' in name -> R.string.files_name_invalid
    name.length > 255 -> R.string.files_name_invalid
    else -> null
}

private fun createEntry(dir: File, name: String, folder: Boolean): File {
    val target = File(dir, name.trim())
    check(!target.exists()) { "\"${target.name}\" already exists" }
    if (folder) check(target.mkdirs()) { "could not create the folder" }
    else check(target.createNewFile()) { "could not create the file" }
    return target
}

/** Renames (same folder) or moves [target] into [destDir], never replacing anything. */
private fun moveEntry(target: File, destDir: File, name: String): File {
    val dest = File(destDir, name.trim())
    if (dest.path == target.path) return target
    check(!dest.exists()) { "\"${dest.name}\" already exists there" }
    check(!(target.isDirectory && (destDir.path + File.separator).startsWith(target.path + File.separator))) {
        "a folder cannot be moved into itself"
    }
    java.nio.file.Files.move(target.toPath(), dest.toPath())
    return dest
}

@Composable
private fun NameDialog(naming: Naming, error: String?, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val initial = (naming as? Naming.Rename)?.target?.name.orEmpty()
    val field = androidx.compose.foundation.text.input.rememberTextFieldState(initial)
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        // Select the name without its extension, as file managers do, so typing replaces it.
        val dot = initial.lastIndexOf('.').takeIf { it > 0 } ?: initial.length
        field.edit { selection = androidx.compose.ui.text.TextRange(0, dot) }
    }
    val typed = field.text.toString()
    val problem = if (typed.isEmpty()) null else badName(typed)
    val title = when (naming) {
        is Naming.NewFile -> stringResource(R.string.files_new_file)
        is Naming.NewFolder -> stringResource(R.string.files_new_folder)
        is Naming.Rename -> stringResource(R.string.files_rename_title, naming.target.name)
    }
    val submit = { if (badName(typed) == null) onConfirm(typed.trim()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Card,
        shape = RoundedCornerShape(20.dp),
        title = { Text(title, color = Palette.Text) },
        text = {
            Column {
                androidx.compose.foundation.text.BasicTextField(
                    state = field,
                    lineLimits = androidx.compose.foundation.text.input.TextFieldLineLimits.SingleLine,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono, color = Palette.Text),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.Accent),
                    onKeyboardAction = { submit() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Palette.Bg)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                )
                val message = error ?: problem?.let { stringResource(it) }
                if (message != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(message, style = MaterialTheme.typography.bodySmall, color = Palette.Pink)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = typed.isNotBlank() && problem == null) {
                Text(
                    stringResource(if (naming is Naming.Rename) R.string.files_rename else R.string.files_create),
                    color = if (typed.isNotBlank() && problem == null) Palette.Accent else Palette.TextFaint,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = Palette.TextDim) }
        },
    )
}

/** Picks the folder to move [target] into: every folder of the project, as an indented list. */
@Composable
private fun MoveDialog(root: File, target: File, error: String?, onPick: (File) -> Unit, onDismiss: () -> Unit) {
    val folders by androidx.compose.runtime.produceState<List<Pair<File, Int>>?>(null, root, target) {
        value = withContext(Dispatchers.IO) {
            val out = mutableListOf(root to 0)
            fun walk(dir: File, depth: Int) {
                if (out.size > 1500 || depth > 12) return
                (dir.listFiles() ?: emptyArray())
                    .filter { it.isDirectory && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                    .sortedBy { it.name.lowercase() }
                    .forEach { d ->
                        // A folder cannot go inside itself, and heavy generated trees are left out.
                        if (d.path == target.path || d.name in SKIP_IN_MOVE) return@forEach
                        out += d to depth + 1
                        walk(d, depth + 1)
                    }
            }
            walk(root, 0)
            out
        }
    }
    val current = target.parentFile
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Card,
        shape = RoundedCornerShape(20.dp),
        title = { Text(stringResource(R.string.files_move_title, target.name), color = Palette.Text) },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                if (error != null) {
                    Text(error, style = MaterialTheme.typography.bodySmall, color = Palette.Pink, modifier = Modifier.padding(bottom = 8.dp))
                }
                val list = folders
                if (list == null) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Palette.Accent)
                    }
                } else {
                    LazyColumn {
                        items(list, key = { it.first.path }) { (dir, depth) ->
                            val here = dir.path == current?.path
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable(enabled = !here) { onPick(dir) }
                                    .padding(start = (8 + depth * 14).dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(TermFoldIcons.Folder, null, tint = if (here) Palette.TextFaint else Palette.Accent, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    if (depth == 0) stringResource(R.string.files_move_root, root.name) else dir.name,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.5.sp),
                                    color = if (here) Palette.TextFaint else Palette.Text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = Palette.TextDim) }
        },
    )
}

private val SKIP_IN_MOVE = setOf(".git", "node_modules", "build", ".gradle", "__pycache__", ".venv", "venv", "dist", "target")

@Composable
private fun DeleteDialog(file: File, error: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val isDir = file.isDirectory
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Card,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                stringResource(if (isDir) R.string.files_delete_folder_title else R.string.files_delete_title, file.name),
                color = Palette.Text,
            )
        },
        text = {
            Text(
                error?.let { stringResource(R.string.files_delete_failed, it) }
                    ?: stringResource(if (isDir) R.string.files_delete_folder_body else R.string.files_delete_body),
                style = MaterialTheme.typography.bodyMedium,
                color = if (error != null) Palette.Pink else Palette.TextDim,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.files_delete), color = Palette.Pink) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = Palette.TextDim) }
        },
    )
}

@Composable
private fun TreeMessage(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = Palette.TextFaint,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun TreeRowView(
    row: TreeRow,
    open: Boolean,
    loading: Boolean,
    selected: Boolean,
    iconsReady: Boolean,
    menuOpen: Boolean,
    onLongClick: () -> Unit,
    onDismissMenu: () -> Unit,
    onDelete: () -> Unit,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onClick: () -> Unit,
) {
  Box {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    menuOpen -> Palette.CardPressed
                    selected -> Palette.AccentSoft
                    else -> Color.Transparent
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = (8 + row.depth * 14).dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            when {
                loading -> CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp, color = Palette.TextFaint)
                row.isDir -> Icon(
                    if (open) TermFoldIcons.ChevronDown else TermFoldIcons.ChevronRight,
                    null,
                    tint = Palette.TextFaint,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        if (iconsReady) FileTypeIcon(name = row.file.name, isDir = row.isDir, open = open) else Spacer(Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = row.file.name,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.5.sp),
            color = when {
                selected -> Palette.Text
                row.file.name.startsWith(".") -> Palette.TextDim
                else -> Palette.Text.copy(alpha = 0.88f)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    androidx.compose.material3.DropdownMenu(
        expanded = menuOpen,
        onDismissRequest = onDismissMenu,
        offset = androidx.compose.ui.unit.DpOffset((24 + row.depth * 14).dp, 0.dp),
        containerColor = Palette.Card,
        shape = RoundedCornerShape(12.dp),
    ) {
        MenuEntry(TermFoldIcons.FilePlus, stringResource(R.string.files_new_file), onNewFile)
        MenuEntry(TermFoldIcons.FolderPlus, stringResource(R.string.files_new_folder), onNewFolder)
        MenuEntry(TermFoldIcons.Pencil, stringResource(R.string.files_rename), onRename)
        MenuEntry(TermFoldIcons.MoveTo, stringResource(R.string.files_move), onMove)
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.files_delete), color = Palette.Pink) },
            leadingIcon = { Icon(TermFoldIcons.Trash, null, tint = Palette.Pink, modifier = Modifier.size(18.dp)) },
            onClick = onDelete,
        )
    }
  }
}

@Composable
private fun MenuEntry(icon: ImageVector, label: String, onClick: () -> Unit) {
    androidx.compose.material3.DropdownMenuItem(
        text = { Text(label, color = Palette.Text) },
        leadingIcon = { Icon(icon, null, tint = Palette.TextDim, modifier = Modifier.size(18.dp)) },
        onClick = onClick,
    )
}

/**
 * VS Code's Material Icon Theme (MIT, bundled by tools/make-file-icons.py): which SVG in
 * assets/fileicons draws a given file or folder, by exact name first, then by extension.
 */
private object FileIcons {
    private class Lookup(
        val ext: Map<String, String>,
        val names: Map<String, String>,
        val folders: Map<String, String>,
        val foldersOpen: Map<String, String>,
        val file: String,
        val folder: String,
        val folderOpen: String,
    )

    @Volatile private var lookup: Lookup? = null

    fun load(context: android.content.Context) {
        if (lookup != null) return
        lookup = runCatching {
            val json = org.json.JSONObject(context.assets.open("fileicons/map.json").bufferedReader().use { it.readText() })
            fun map(key: String): Map<String, String> {
                val o = json.getJSONObject(key)
                return o.keys().asSequence().associateWith { o.getString(it) }
            }
            Lookup(
                map("ext"), map("names"), map("folders"), map("foldersOpen"),
                json.getString("file"), json.getString("folder"), json.getString("folderOpen"),
            )
        }.getOrNull()
    }

    val ready: Boolean get() = lookup != null

    fun iconFor(name: String, isDir: Boolean, open: Boolean): String? {
        val l = lookup ?: return null
        val lower = name.lowercase()
        val icon = if (isDir) {
            (if (open) l.foldersOpen[lower] else l.folders[lower]) ?: if (open) l.folderOpen else l.folder
        } else {
            l.names[lower] ?: extensionIcon(l, lower) ?: l.file
        }
        return "file:///android_asset/fileicons/$icon.svg"
    }

    /** "foo.test.ts" tries "test.ts" before "ts", as VS Code does. */
    private fun extensionIcon(l: Lookup, lower: String): String? {
        var dot = lower.indexOf('.', startIndex = 1)
        while (dot >= 0) {
            l.ext[lower.substring(dot + 1)]?.let { return it }
            dot = lower.indexOf('.', startIndex = dot + 1)
        }
        return null
    }
}

/** The tree's icon for a file or folder, from the same icon set as VS Code's Material theme. */
@Composable
private fun FileTypeIcon(name: String, isDir: Boolean, open: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val url = FileIcons.iconFor(name, isDir, open)
    if (url == null) {
        Spacer(Modifier.size(16.dp))
        return
    }
    coil.compose.AsyncImage(
        model = url,
        contentDescription = null,
        imageLoader = com.termfold.app.ui.components.AgentIconLoader.of(context),
        modifier = Modifier.size(17.dp),
    )
}

private val PathSetSaver = androidx.compose.runtime.saveable.Saver<MutableSet<String>, ArrayList<String>>(
    save = { ArrayList(it) },
    restore = { it.toMutableSet() },
)

// ---- The editor -------------------------------------------------------------------------------

private const val MAX_EDIT_BYTES = 4L * 1024 * 1024
private const val EDITOR_PAGE = "file:///android_asset/editor/index.html"
private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

private sealed interface Loaded {
    data object Loading : Loaded
    data class Text(val bytes: ByteArray, val writable: Boolean, val modified: Long) : Loaded
    data class Picture(val bitmap: ImageBitmap) : Loaded
    data class Unavailable(val reason: String) : Loaded
}

/** Bridges calls from the page (on a WebView thread) back to Compose state on the main thread. */
private class EditorBridge(
    private val post: (Runnable) -> Unit,
    var onReady: () -> Unit = {},
    var onDirty: (Boolean) -> Unit = {},
    var onSave: (String) -> Unit = {},
    var onCursor: (Int, Int) -> Unit = { _, _ -> },
) {
    @JavascriptInterface fun ready() = post { onReady() }
    @JavascriptInterface fun dirty(value: Boolean) = post { onDirty(value) }
    @JavascriptInterface fun save(text: String) = post { onSave(text) }
    @JavascriptInterface fun cursor(line: Int, column: Int) = post { onCursor(line, column) }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CodeEditor(
    file: File,
    root: File?,
    onDirtyChange: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var loaded by remember(file) { mutableStateOf<Loaded>(Loaded.Loading) }
    var dirty by remember(file) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember(file) { mutableStateOf<String?>(null) }
    var cursor by remember(file) { mutableStateOf(1 to 1) }
    var wrap by rememberSaveable { mutableStateOf(false) }
    var pageReady by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    // The file's modification time as last read or written, to notice outside edits.
    var knownModified by remember(file) { mutableStateOf(0L) }

    val unableToRead = stringResource(R.string.files_unreadable)
    val tooLarge = stringResource(R.string.files_too_large)
    val binary = stringResource(R.string.files_binary)

    LaunchedEffect(file) {
        onDirtyChange(false)
        loaded = withContext(Dispatchers.IO) { load(file, unableToRead, tooLarge, binary) }
        (loaded as? Loaded.Text)?.let { knownModified = it.modified }
    }

    fun js(code: String) = webView?.evaluateJavascript(code, null)

    // Hand the text to the page once both are ready.
    LaunchedEffect(loaded, pageReady) {
        val text = loaded as? Loaded.Text ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
        val b64 = Base64.encodeToString(text.bytes, Base64.NO_WRAP)
        val name = org.json.JSONObject.quote(file.name)
        js("tf.setWrap($wrap); tf.open('$b64', $name, ${!text.writable});")
    }

    // Pick up edits made outside the editor (an agent or the shell) while there are no local
    // changes to lose.
    LaunchedEffect(file, pageReady) {
        while (true) {
            delay(1_500)
            if (loaded !is Loaded.Text || !pageReady || dirty || saving) continue
            val changed = withContext(Dispatchers.IO) {
                val modified = file.lastModified()
                if (modified != 0L && modified != knownModified && file.length() <= MAX_EDIT_BYTES) {
                    modified to runCatching { file.readBytes() }.getOrNull()
                } else {
                    null
                }
            } ?: continue
            knownModified = changed.first
            val bytes = changed.second ?: continue
            js("tf.replace('${Base64.encodeToString(bytes, Base64.NO_WRAP)}');")
        }
    }

    val bridge = remember {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        EditorBridge(post = { r -> main.post(r) })
    }
    bridge.onReady = { pageReady = true }
    bridge.onDirty = { dirty = it; onDirtyChange(it) }
    bridge.onCursor = { line, col -> cursor = line to col }
    bridge.onSave = { text ->
        if (!saving && (loaded as? Loaded.Text)?.writable == true) {
            saving = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        file.writeText(text)
                        file.lastModified()
                    }
                }
                saving = false
                result.onSuccess { modified ->
                    knownModified = modified
                    saveError = null
                    js("tf.markSaved();")
                }.onFailure { saveError = it.message ?: it.javaClass.simpleName }
            }
        }
    }

    val relative = root?.let { file.relativeToOrNull(it)?.path }?.replace('\\', '/') ?: file.path
    val text = loaded as? Loaded.Text

    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BareIconButton(icon = TermFoldIcons.Close, contentDescription = stringResource(R.string.action_close), onClick = onClose)
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        file.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = Palette.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (dirty) {
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(7.dp).clip(CircleShape).background(Palette.Accent))
                    }
                }
                Text(
                    text = saveError?.let { stringResource(R.string.files_save_failed, it) } ?: relative,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (saveError != null) Palette.Pink else Palette.TextFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (text != null) {
                EditorAction(TermFoldIcons.Search, stringResource(R.string.files_search)) { js("tf.search();") }
                EditorAction(TermFoldIcons.Wrap, stringResource(R.string.files_wrap), active = wrap) {
                    wrap = !wrap
                    js("tf.setWrap($wrap);")
                }
                EditorAction(TermFoldIcons.Undo, stringResource(R.string.files_undo)) { js("tf.undo();") }
                EditorAction(TermFoldIcons.Redo, stringResource(R.string.files_redo)) { js("tf.redo();") }
                if (text.writable) {
                    Spacer(Modifier.width(6.dp))
                    SaveButton(dirty = dirty, saving = saving) { js("tf.requestSave();") }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.BorderSoft))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            // The WebView lives as long as the editor, so switching files keeps it warm.
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        setBackgroundColor(android.graphics.Color.parseColor("#08080A"))
                        settings.javaScriptEnabled = true
                        // The page is the app's own bundled asset; it needs nothing else. No
                        // content:// access, no file:// reads from script, no network, and no
                        // navigating away (a pasted or dropped link must not load a web page
                        // that could reach the bridge).
                        settings.allowFileAccess = true
                        settings.allowContentAccess = false
                        @Suppress("DEPRECATION")
                        settings.allowFileAccessFromFileURLs = false
                        @Suppress("DEPRECATION")
                        settings.allowUniversalAccessFromFileURLs = false
                        settings.blockNetworkLoads = true
                        settings.domStorageEnabled = false
                        settings.builtInZoomControls = false
                        settings.setGeolocationEnabled(false)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: android.webkit.WebResourceRequest,
                            ): Boolean = !request.url.toString().startsWith(EDITOR_PAGE)
                        }
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                                android.util.Log.i("TermFoldEditor", "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                                return true
                            }
                        }
                        addJavascriptInterface(bridge, "Android")
                        loadUrl(EDITOR_PAGE)
                        webView = this
                    }
                },
            )
            when (val state = loaded) {
                Loaded.Loading -> Box(Modifier.fillMaxSize().background(Palette.Bg), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Palette.Accent)
                }
                is Loaded.Picture -> Box(Modifier.fillMaxSize().background(Palette.Bg).padding(16.dp), contentAlignment = Alignment.Center) {
                    Image(state.bitmap, contentDescription = file.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
                is Loaded.Unavailable -> Box(Modifier.fillMaxSize().background(Palette.Bg).padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(state.reason, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
                }
                is Loaded.Text -> if (!pageReady) {
                    Box(Modifier.fillMaxSize().background(Palette.Bg))
                }
            }
        }

        if (text != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Palette.Bg)
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                FooterText(stringResource(R.string.files_cursor, cursor.first, cursor.second))
                FooterText(languageLabel(file.name))
                if (!text.writable) FooterText(stringResource(R.string.files_read_only), Palette.Yellow)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                removeJavascriptInterface("Android")
                destroy()
            }
            webView = null
        }
    }
}

@Composable
private fun FooterText(text: String, color: Color = Palette.TextFaint) {
    Text(text, style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono, fontSize = 11.sp), color = color)
}

@Composable
private fun EditorAction(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) Palette.AccentSoft else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (active) Palette.Accent else Palette.TextDim, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun SaveButton(dirty: Boolean, saving: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (dirty) Palette.Accent else Palette.Card)
            .clickable(enabled = !saving, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (saving) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = if (dirty) Palette.OnAccent else Palette.Accent)
        } else {
            Icon(TermFoldIcons.Save, null, tint = if (dirty) Palette.OnAccent else Palette.TextDim, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(R.string.files_save),
            style = MaterialTheme.typography.labelMedium,
            color = if (dirty) Palette.OnAccent else Palette.TextDim,
        )
    }
}

private fun load(file: File, unreadable: String, tooLarge: String, binary: String): Loaded {
    if (!file.canRead()) return Loaded.Unavailable(unreadable)
    val extension = file.extension.lowercase()
    if (extension in IMAGE_EXTENSIONS) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        return bitmap?.let { Loaded.Picture(it.asImageBitmap()) } ?: Loaded.Unavailable(binary)
    }
    if (file.length() > MAX_EDIT_BYTES) return Loaded.Unavailable(tooLarge)
    val bytes = runCatching { file.readBytes() }.getOrElse { return Loaded.Unavailable(unreadable) }
    // A NUL byte early on means a binary file, which the editor would only mangle.
    if (bytes.take(8_000).any { it == 0.toByte() }) return Loaded.Unavailable(binary)
    return Loaded.Text(bytes, writable = file.canWrite(), modified = file.lastModified())
}

private fun languageLabel(name: String): String {
    val lower = name.lowercase()
    if (lower == "dockerfile") return "Dockerfile"
    if (lower == "makefile") return "Makefile"
    return when (lower.substringAfterLast('.', "")) {
        "kt", "kts" -> "Kotlin"
        "java" -> "Java"
        "js", "mjs", "cjs" -> "JavaScript"
        "jsx" -> "JSX"
        "ts", "mts", "cts" -> "TypeScript"
        "tsx" -> "TSX"
        "py", "pyi" -> "Python"
        "json", "jsonc" -> "JSON"
        "md", "markdown", "mdx" -> "Markdown"
        "html", "htm" -> "HTML"
        "css", "scss", "less" -> "CSS"
        "rs" -> "Rust"
        "c", "h" -> "C"
        "cc", "cpp", "cxx", "hpp", "hh" -> "C++"
        "go" -> "Go"
        "php" -> "PHP"
        "sql" -> "SQL"
        "xml", "svg" -> "XML"
        "yml", "yaml" -> "YAML"
        "toml" -> "TOML"
        "sh", "bash", "zsh" -> "Shell"
        "swift" -> "Swift"
        "rb" -> "Ruby"
        "lua" -> "Lua"
        "dart" -> "Dart"
        "cs" -> "C#"
        "gradle" -> "Gradle"
        "" -> "Plain text"
        else -> lower.substringAfterLast('.').uppercase()
    }
}

/** The header button that shows or hides the file tree; lit while the tree is open. */
@Composable
fun FilesButton(open: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (open) Palette.AccentSoft else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            TermFoldIcons.Files,
            contentDescription = stringResource(R.string.files_toggle),
            tint = if (open) Palette.Accent else Palette.TextDim,
            modifier = Modifier.size(21.dp),
        )
    }
}

/** A header icon button that stays lit while its panel (search, files) is open. */
@Composable
fun HeaderToggle(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) Palette.AccentSoft else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (active) Palette.Accent else Palette.TextDim, modifier = Modifier.size(20.dp))
    }
}
