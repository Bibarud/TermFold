package com.termfold.app.files

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Moving files between the Ubuntu environment and the rest of Android: uploading from other
 * apps, sharing, opening in another app, saving to the device, zipping. Files are handed out
 * through a FileProvider with a one-off read grant; nothing is exposed otherwise.
 */
object FileActions {

    private fun authority(context: Context) = context.packageName + ".files"

    /**
     * A content URI for [file]. A guest symlink can point somewhere Android resolves outside the
     * shared paths (its target is a guest path); such a file is handed out as a copy instead.
     */
    fun uriFor(context: Context, file: File): Uri =
        runCatching { FileProvider.getUriForFile(context, authority(context), file) }.getOrElse {
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val copy = File(dir, file.name)
            file.inputStream().use { input -> copy.outputStream().use { input.copyTo(it) } }
            FileProvider.getUriForFile(context, authority(context), copy)
        }

    fun mimeOf(file: File): String {
        if (file.isDirectory) return "resource/folder"
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) {
                "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "rs", "go", "c", "h", "cpp", "sh",
                "md", "json", "yml", "yaml", "toml", "ini", "cfg", "log", "txt", "csv", "sql", "gradle",
                -> "text/plain"
                else -> "application/octet-stream"
            }
    }

    /** Whether the built-in editor is the right place to open [file] (code, text, images). */
    fun opensInEditor(file: File): Boolean {
        val ext = file.extension.lowercase()
        if (ext in IMAGE_EXT) return true
        if (ext in OTHER_APP_EXT) return false
        val mime = mimeOf(file)
        if (mime.startsWith("text/") || mime == "application/json" || mime == "application/xml") return true
        // No or unknown extension: a small file without NUL bytes reads as text.
        if (file.length() > 4L * 1024 * 1024) return false
        return runCatching {
            file.inputStream().use { input ->
                val buf = ByteArray(8000)
                val n = input.read(buf)
                n <= 0 || (0 until n).none { buf[it] == 0.toByte() }
            }
        }.getOrDefault(false)
    }

    fun isImage(file: File): Boolean = file.extension.lowercase() in IMAGE_EXT

    /**
     * Puts the picture on the clipboard as a content URI, so it can be pasted into a chat, an
     * agent or any app that accepts images. The system grants the pasting app read access.
     */
    fun copyImage(context: Context, file: File) {
        val uri = uriFor(context, file)
        val clip = ClipData(file.name, arrayOf(mimeOf(file)), ClipData.Item(uri))
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(clip)
    }

    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
    private val OTHER_APP_EXT = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "epub",
        "mp4", "mkv", "webm", "mov", "mp3", "wav", "ogg", "m4a", "flac", "apk", "zip", "gz", "tar", "7z",
    )

    /** Opens [file] in another app chosen by the user. */
    fun openWith(context: Context, file: File) {
        val uri = uriFor(context, file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Shares files (a folder is zipped first, in the background by the caller). */
    fun share(context: Context, files: List<File>) {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { uriFor(context, it) })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(mimeOf(files[0]))
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).setType("*/*")
        }
        intent.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Copies documents picked from other apps into [dir], never overwriting: a clash becomes
     * "name (2).ext". Returns the files created.
     */
    fun upload(context: Context, uris: List<Uri>, dir: File): List<File> {
        dir.mkdirs()
        val out = ArrayList<File>()
        for (uri in uris) {
            val name = displayName(context, uri) ?: "upload"
            val target = unique(dir, name)
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it, 1 shl 16) }
            } ?: continue
            out += target
        }
        return out
    }

    fun displayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()?.let { com.termfold.app.shell.Projects.safeName(it) }

    /** [name] in [dir], or "name (2).ext" and so on when it is taken. */
    fun unique(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot)
        val ext = name.substring(dot)
        var n = 2
        while (f.exists()) {
            f = File(dir, "$base ($n)$ext")
            n++
        }
        return f
    }

    /** Writes [file] to a document the user created with the system "save as" screen. */
    fun exportTo(context: Context, file: File, target: Uri) {
        context.contentResolver.openOutputStream(target, "w")?.use { out ->
            if (file.isDirectory) zipTo(file, out) else file.inputStream().use { it.copyTo(out, 1 shl 16) }
        } ?: error("could not write the file")
    }

    /**
     * Copies files and folders into a folder the user picked with the system picker (any
     * storage: Downloads, an SD card, a cloud drive), keeping folder structure. Android grants
     * access to that one folder only, so no storage permission is involved. A clash is renamed
     * by the storage provider ("name (1)"), never overwritten. Returns the files written.
     */
    fun exportToTree(context: Context, sources: List<File>, treeUri: Uri, onProgress: (Int) -> Unit = {}): Int {
        val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
            ?: error("That folder cannot be written to")
        var count = 0
        fun put(src: File, into: androidx.documentfile.provider.DocumentFile) {
            val p = src.toPath()
            if (Files.isSymbolicLink(p) && !src.exists()) return
            if (src.isDirectory) {
                val dir = into.createDirectory(src.name) ?: error("Could not create ${src.name}")
                src.listFiles()?.sortedBy { it.name }?.forEach { put(it, dir) }
            } else if (src.isFile) {
                val doc = into.createFile(exportMime(src), src.name) ?: error("Could not create ${src.name}")
                context.contentResolver.openOutputStream(doc.uri, "w")?.use { out ->
                    src.inputStream().use { it.copyTo(out, 1 shl 16) }
                } ?: error("Could not write ${src.name}")
                count++
                if (count % 10 == 0) onProgress(count)
            }
        }
        sources.forEach { put(it, root) }
        return count
    }

    /**
     * The type to create an exported file with. Storage providers append the extension they
     * associate with a type ("notes.log" as text/plain became "notes.log.txt"), so the real type
     * is used only when it agrees with the file's own extension; otherwise a generic type keeps
     * the name exactly as it is.
     */
    private fun exportMime(file: File): String {
        val mime = mimeOf(file)
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        return if (ext != null && file.name.endsWith(".$ext", ignoreCase = true)) mime else "application/octet-stream"
    }

    /** Zips [sources] into a new file in the app's cache, for sharing. */
    fun zipToCache(context: Context, sources: List<File>, name: String): File {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.deleteRecursively() }
        val out = File(dir, name.removeSuffix(".zip") + ".zip")
        out.outputStream().use { zipTo(sources, it) }
        return out
    }

    private fun zipTo(dir: File, out: java.io.OutputStream) = zipTo(listOf(dir), out)

    /** Zips files and folders; symlinks are skipped (zip has no portable way to keep them). */
    private fun zipTo(sources: List<File>, out: java.io.OutputStream) {
        ZipOutputStream(out.buffered()).use { zip ->
            fun add(f: File, path: String) {
                if (Files.isSymbolicLink(f.toPath())) return
                if (Files.isDirectory(f.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    zip.putNextEntry(ZipEntry("$path/")); zip.closeEntry()
                    f.listFiles()?.sortedBy { it.name }?.forEach { add(it, "$path/${it.name}") }
                } else if (f.isFile) {
                    zip.putNextEntry(ZipEntry(path).apply { time = f.lastModified() })
                    f.inputStream().use { it.copyTo(zip, 1 shl 16) }
                    zip.closeEntry()
                }
            }
            sources.forEach { add(it, it.name) }
        }
    }

    /** Copies a file or folder tree into [dir] under a free name. Symlinks are copied as links. */
    fun copyInto(source: File, dir: File): File {
        val target = unique(dir, source.name)
        copyTree(source, target)
        return target
    }

    private fun copyTree(source: File, target: File) {
        val p = source.toPath()
        when {
            Files.isSymbolicLink(p) -> Files.createSymbolicLink(target.toPath(), Files.readSymbolicLink(p))
            Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) -> {
                target.mkdirs()
                source.listFiles()?.forEach { copyTree(it, File(target, it.name)) }
            }
            else -> {
                Files.copy(p, target.toPath(), java.nio.file.StandardCopyOption.COPY_ATTRIBUTES)
            }
        }
    }

    /** Moves into [dir] under a free name; refuses to move a folder into itself. */
    fun moveInto(source: File, dir: File): File {
        check(!(dir.absolutePath + "/").startsWith(source.absolutePath + "/")) { "a folder cannot be moved into itself" }
        if (source.parentFile?.absolutePath == dir.absolutePath) return source
        val target = unique(dir, source.name)
        Files.move(source.toPath(), target.toPath())
        return target
    }
}
