package com.termfold.app.shell

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * Projects live inside the Ubuntu environment, in `~/projects/<name>` (`/root/projects` in the
 * guest), on the app's own Linux storage.
 *
 * Android's shared storage (/sdcard) is not a Linux filesystem as far as programs are concerned:
 * no symlinks, no hard links, no permissions, nothing executable. git, npm, Python virtualenvs,
 * build tools and the agents all trip over that. A project inside the guest is an ordinary
 * directory, so everything behaves as on a PC, and sessions simply start there: nothing has
 * to be bound in from outside.
 *
 * Files still move in and out through the app: import a device folder, upload files, share,
 * export and back up (see the file manager).
 */
object Projects {

    /** The projects directory in the guest. */
    const val GUEST_DIR = "/root/projects"

    fun dir(context: Context): File = File(ShellPaths.rootfsDir(context), GUEST_DIR.trimStart('/'))

    /** True when [hostPath] is inside the guest (a project, or any folder under the rootfs). */
    fun isInGuest(context: Context, hostPath: String?): Boolean {
        if (hostPath.isNullOrBlank()) return false
        val root = ShellPaths.rootfsDir(context).absolutePath.trimEnd('/')
        return hostPath == root || hostPath.startsWith("$root/")
    }

    /** The guest path of a host path inside the rootfs, e.g. `/root/projects/app`, or null. */
    fun guestPath(context: Context, hostPath: String?): String? {
        if (!isInGuest(context, hostPath)) return null
        val root = ShellPaths.rootfsDir(context).absolutePath.trimEnd('/')
        return hostPath!!.removePrefix(root).ifEmpty { "/" }
    }

    /** The host path for a guest path. */
    fun hostPath(context: Context, guestPath: String): File =
        File(ShellPaths.rootfsDir(context), guestPath.trimStart('/'))

    /**
     * A directory name for a project called [display]: readable, safe in a shell (no spaces or
     * quotes to trip over), and not already taken.
     */
    fun newDirFor(context: Context, display: String): File {
        val base = display.trim()
            .replace(Regex("\\s+"), "-")
            .replace(Regex("[^A-Za-z0-9._-]"), "")
            .trim('-', '.')
            .ifEmpty { "project" }
            .take(60)
        val parent = dir(context).apply { mkdirs() }
        var candidate = File(parent, base)
        var n = 2
        while (candidate.exists()) candidate = File(parent, "$base-${n++}")
        return candidate
    }

    /** Creates an empty project directory. */
    fun create(context: Context, display: String): File =
        newDirFor(context, display).apply { check(mkdirs() || isDirectory) { "Could not create $path" } }

    /**
     * Copies a folder picked with the system folder picker into a new project, following the
     * picker's own tree (so it works for any storage provider, not only local folders).
     * Returns the number of files copied.
     */
    fun importTree(context: Context, treeUri: Uri, into: File, onProgress: (String) -> Unit = {}): Int {
        val resolver = context.contentResolver
        val rootDoc = DocumentsContract.getTreeDocumentId(treeUri)
        var count = 0
        fun copyChildren(documentId: String, target: File, depth: Int) {
            if (depth > 64) return
            target.mkdirs()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = safeName(c.getString(1) ?: continue) ?: continue
                    val mime = c.getString(2)
                    val out = File(target, name)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        copyChildren(id, out, depth + 1)
                    } else {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        resolver.openInputStream(uri)?.use { input ->
                            out.outputStream().use { input.copyTo(it, 1 shl 16) }
                        }
                        count++
                        if (count % 25 == 0) onProgress("$count files")
                    }
                }
            }
        }
        copyChildren(rootDoc, into, 0)
        return count
    }

    /** A file name from another app, made safe to create: no path separators or dot names. */
    fun safeName(name: String): String? {
        val n = name.replace('/', '_').replace('\u0000', '_').trim()
        return n.takeIf { it.isNotEmpty() && it != "." && it != ".." }
    }

    /**
     * Clones a git repository into [into] (which must not exist yet or be empty), running git
     * inside the guest. Returns null on success or git's error output.
     */
    fun clone(context: Context, url: String, into: File, onProgress: (String) -> Unit = {}): String? {
        into.mkdirs()
        val guest = guestPath(context, into.absolutePath) ?: return "not a guest folder"
        val log = File(ShellPaths.tempDir(context), "clone-${System.nanoTime()}.log")
        val command = ProotCommand.build(
            context = context,
            argv = listOf("/usr/bin/git", "clone", "--progress", "--", url, guest),
            guestCwd = GUEST_DIR,
        )
        val process = ProcessBuilder(command).apply {
            environment().clear()
            environment().putAll(ProotCommand.hostEnvironment(context))
            redirectErrorStream(true)
            redirectOutput(log)
        }.start()
        process.outputStream.close()
        // git reports progress with carriage returns; the latest line is what the UI shows.
        while (process.isAlive) {
            runCatching {
                val tail = log.readText().takeLast(400).split('\r', '\n').lastOrNull { it.isNotBlank() }
                if (tail != null) onProgress(tail.trim())
            }
            Thread.sleep(400)
        }
        val output = runCatching { log.readText() }.getOrDefault("")
        log.delete()
        return if (process.exitValue() == 0) null else output.lines().filter { it.isNotBlank() }.takeLast(6).joinToString("\n")
    }
}
