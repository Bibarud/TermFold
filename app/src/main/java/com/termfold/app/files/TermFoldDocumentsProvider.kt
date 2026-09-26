package com.termfold.app.files

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import com.termfold.app.R
import com.termfold.app.shell.ShellPaths
import java.io.File
import java.io.FileNotFoundException

/**
 * Makes the Linux home folder (~) a storage location in Android's own file picker, so the
 * Files app, Gmail, WhatsApp, Drive or any "attach file" screen can browse projects, open files
 * and save back, without TermFold holding any storage permission: Android only lets other apps
 * in through the picker the user drives.
 *
 * Hidden files and folders are never shown or served. Home holds agent sign-ins and keys
 * (~/.claude, ~/.codex, ~/.ssh), and no other app should be able to reach those.
 */
class TermFoldDocumentsProvider : DocumentsProvider() {

    private val home: File get() = File(ShellPaths.rootfsDir(context!!), "root")

    override fun onCreate(): Boolean = true

    // ---- Ids: "home" or "home/relative/path" ---------------------------------------------------

    private fun idOf(file: File): String {
        val rel = file.absolutePath.removePrefix(home.absolutePath).trimStart('/')
        return if (rel.isEmpty()) ROOT_ID else "$ROOT_ID/$rel"
    }

    /** The file for an id, refusing anything outside home or hidden. */
    private fun fileOf(id: String): File {
        if (id != ROOT_ID && !id.startsWith("$ROOT_ID/")) throw FileNotFoundException(id)
        val rel = id.removePrefix(ROOT_ID).trimStart('/')
        if (rel.split('/').any { it.isEmpty() && rel.isNotEmpty() || it == ".." || it.startsWith(".") }) {
            throw FileNotFoundException(id)
        }
        val f = if (rel.isEmpty()) home else File(home, rel)
        // A symlink may point anywhere in the guest; only what really sits under home is served.
        val base = home.canonicalPath
        val real = f.canonicalPath
        if (real != base && !real.startsWith("$base/")) throw FileNotFoundException(id)
        return f
    }

    private fun visible(f: File) = !f.name.startsWith(".")

    // ---- Queries --------------------------------------------------------------------------------

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: ROOT_COLUMNS)
        if (!home.isDirectory) return c
        c.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, ROOT_ID)
            add(Root.COLUMN_TITLE, context!!.getString(R.string.app_name))
            add(Root.COLUMN_SUMMARY, context!!.getString(R.string.provider_summary))
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(
                Root.COLUMN_FLAGS,
                Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY,
            )
            add(Root.COLUMN_MIME_TYPES, "*/*")
            add(Root.COLUMN_AVAILABLE_BYTES, home.usableSpace)
        }
        return c
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOC_COLUMNS).also { row(it, fileOf(documentId)) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val c = MatrixCursor(projection ?: DOC_COLUMNS)
        fileOf(parentDocumentId).listFiles()?.filter(::visible)?.forEach { row(c, it) }
        return c
    }

    private fun row(c: MatrixCursor, f: File) {
        var flags = 0
        if (f.isDirectory) {
            flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else if (f.canWrite()) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        if (f != home) flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        c.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, idOf(f))
            add(Document.COLUMN_DISPLAY_NAME, if (f == home) context!!.getString(R.string.provider_home) else f.name)
            add(Document.COLUMN_MIME_TYPE, if (f.isDirectory) Document.MIME_TYPE_DIR else FileActions.mimeOf(f))
            add(Document.COLUMN_SIZE, if (f.isFile) f.length() else null)
            add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    override fun getDocumentType(documentId: String): String {
        val f = fileOf(documentId)
        return if (f.isDirectory) Document.MIME_TYPE_DIR else FileActions.mimeOf(f)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId == parentDocumentId || documentId.startsWith(parentDocumentId.trimEnd('/') + "/")

    // ---- Reading and writing --------------------------------------------------------------------

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val f = fileOf(documentId)
        if (f.isDirectory) throw FileNotFoundException(documentId)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val dir = fileOf(parentDocumentId)
        val name = com.termfold.app.shell.Projects.safeName(displayName)?.takeIf(::isShown)
            ?: throw FileNotFoundException("invalid name")
        val target = FileActions.unique(dir, name)
        val ok = if (mimeType == Document.MIME_TYPE_DIR) target.mkdir() else target.createNewFile()
        if (!ok) throw FileNotFoundException("could not create $name")
        return idOf(target)
    }

    override fun deleteDocument(documentId: String) {
        val f = fileOf(documentId)
        if (f == home || !f.deleteRecursively()) throw FileNotFoundException("could not delete")
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val f = fileOf(documentId)
        val name = com.termfold.app.shell.Projects.safeName(displayName)?.takeIf(::isShown)
            ?: throw FileNotFoundException("invalid name")
        val target = File(f.parentFile, name)
        if (f == home || target.exists() || !f.renameTo(target)) throw FileNotFoundException("could not rename")
        return idOf(target)
    }

    /** Other apps may not create hidden files either. */
    private fun isShown(name: String) = !name.startsWith(".")

    companion object {
        private const val ROOT_ID = "home"
        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_ICON, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES, Root.COLUMN_AVAILABLE_BYTES,
        )
        private val DOC_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )
    }
}
