package com.termfold.app.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract

/**
 * Maps a SAF tree URI onto the real filesystem path Termux can actually `cd` into.
 *
 * Storage providers expose document ids as `<volumeId>:<relative/path>`, where `primary` is the
 * emulated shared storage. Anything else (an SD card, a USB volume) lives under `/storage/<id>`.
 */
object SafPaths {

    fun realPath(context: Context, treeUri: Uri): String? {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return null
        return realPathFromDocumentId(documentId)
    }

    fun realPathFromDocumentId(documentId: String): String? {
        // Some providers hand back an already-absolute path.
        if (documentId.startsWith("raw:")) {
            return documentId.removePrefix("raw:").takeIf { it.startsWith('/') }
        }
        if (documentId.startsWith('/')) return documentId

        val separator = documentId.indexOf(':')
        if (separator <= 0) return null

        val volume = documentId.substring(0, separator)
        val relative = documentId.substring(separator + 1)

        val root = if (volume.equals("primary", ignoreCase = true)) {
            @Suppress("DEPRECATION")
            Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"
        } else {
            "/storage/$volume"
        }

        return if (relative.isEmpty()) root else "$root/${relative.trimStart('/')}"
    }

    /** Last path segment of the folder, used as the initial display name. */
    fun displayName(treeUri: Uri): String? {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return null
        val relative = documentId.substringAfter(':', documentId).trimEnd('/')
        val name = relative.substringAfterLast('/')
        return name.ifEmpty { null }
    }
}
