package com.termfold.app.preview

import android.content.Context
import android.webkit.MimeTypeMap
import android.webkit.WebResourceResponse
import com.termfold.app.shell.ShellPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The in-app browser: which page it shows and for which project. Any screen can open it (a
 * header button, a file's Preview action, a link in a chat); the main screen draws it, beside
 * the work on a tablet and over it on a phone.
 */
object Preview {

    data class State(
        val open: Boolean = false,
        /** The page to show; null shows the start page (running servers, the project's pages). */
        val url: String? = null,
        /** Bumped on every open, so asking for the same page again reloads it. */
        val nonce: Int = 0,
        /** The project the preview was opened from, as a guest path, when there is one. */
        val projectDir: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** The last page shown per project (or "" for none), so reopening picks up where it was. */
    private val lastUrl = HashMap<String, String>()

    fun open(url: String? = null, projectDir: String? = null) {
        _state.update { current ->
            val project = projectDir ?: current.projectDir
            State(
                open = true,
                url = url ?: lastUrl[project.orEmpty()] ?: current.url.takeIf { project == current.projectDir },
                nonce = current.nonce + 1,
                projectDir = project,
            )
        }
    }

    fun close() = _state.update { it.copy(open = false) }

    /** Whether [file] can be shown as a page: HTML or SVG, or a folder with an index.html. */
    fun canPreview(file: File): Boolean =
        if (file.isDirectory) File(file, "index.html").isFile || File(file, "index.htm").isFile
        else file.extension.lowercase() in setOf("html", "htm", "svg")

    /** Opens a file (a host path inside the guest) in the preview, for its project. */
    fun openFile(context: Context, file: File) {
        val rootfs = ShellPaths.rootfsDir(context)
        val guest = "/" + file.relativeTo(rootfs).path.replace('\\', '/').trim('/')
        val project = Regex("^/root/projects/[^/]+").find(guest)?.value
        open(fileUrl(guest), project)
    }

    /** The page the pane is on now, remembered for its project. */
    fun visited(url: String) {
        lastUrl[_state.value.projectDir.orEmpty()] = url
        _state.update { it.copy(url = url) }
    }

    // ---- Addresses -------------------------------------------------------------------------

    /** Project files are served from here, so pages load like on a real server (modules, fetch). */
    const val FILES_HOST = "files.termfold"
    private const val FILES_ORIGIN = "http://$FILES_HOST"

    fun fileUrl(guestPath: String): String =
        FILES_ORIGIN + guestPath.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    /** The guest path behind a file preview address, or null for any other page. */
    fun guestPathOf(url: String): String? {
        if (!url.startsWith("$FILES_ORIGIN/")) return null
        val path = url.removePrefix(FILES_ORIGIN).substringBefore('?').substringBefore('#')
        return runCatching { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrNull()
    }

    /** What the address bar shows: ~/projects/app/index.html for files, the URL otherwise. */
    fun display(url: String): String {
        val guest = guestPathOf(url) ?: return url.removePrefix("http://").removePrefix("https://").trimEnd('/')
        return if (guest == "/root" || guest.startsWith("/root/")) "~" + guest.removePrefix("/root") else guest
    }

    /**
     * Turns what was typed into an address: "5173" or ":5173" is a local port, "localhost:3000"
     * gets http://, "~/site/index.html" or "/root/..." is a file, a bare name.tld is https, and
     * anything else is a web search.
     */
    fun normalize(input: String, projectDir: String?): String {
        val text = input.trim()
        return when {
            text.isEmpty() -> ""
            Regex("^:?\\d{2,5}(/.*)?$").matches(text) -> "http://localhost:" + text.removePrefix(":")
            Regex("^(localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0|\\[::1])(:\\d+)?(/.*)?$", RegexOption.IGNORE_CASE).matches(text) ->
                "http://" + text.replace("0.0.0.0", "localhost")
            Regex("^(\\d{1,3}\\.){3}\\d{1,3}(:\\d+)?(/.*)?$").matches(text) -> "http://$text"
            text.startsWith("~/") || text == "~" -> fileUrl("/root" + text.removePrefix("~"))
            text.startsWith("/") -> fileUrl(text)
            text.startsWith("./") && projectDir != null -> fileUrl(projectDir.trimEnd('/') + text.removePrefix("."))
            Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE).containsMatchIn(text) -> text
            !text.contains(' ') && Regex("^[^/\\s]+\\.[a-z]{2,}(:\\d+)?(/.*)?$", RegexOption.IGNORE_CASE).matches(text) -> "https://$text"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(text, "UTF-8")
        }
    }

    fun isLocal(url: String): Boolean =
        Regex("^https?://(localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0|\\[::1])(:|/|$)", RegexOption.IGNORE_CASE).containsMatchIn(url) ||
            guestPathOf(url) != null
}

/**
 * Dev servers running in the Linux environment. Android does not let apps list open ports, so
 * the usual dev-server ports are tried on the loopback address (IPv4 and IPv6, since some tools
 * listen on ::1 only).
 */
object DevServers {
    private val PORTS = listOf(
        3000, 3001, 3002, 4000, 4173, 4200, 4321, 5000, 5001, 5173, 5174, 5500, 6006, 7860,
        8000, 8001, 8080, 8081, 8501, 8787, 8888, 9000, 1234, 1313,
    )

    suspend fun scan(): List<Int> = withContext(Dispatchers.IO) {
        coroutineScope {
            PORTS.map { port -> async { port.takeIf { open("127.0.0.1", port) || open("::1", port) } } }
                .awaitAll()
                .filterNotNull()
        }
    }

    private fun open(host: String, port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, port), 250) }
        true
    }.getOrDefault(false)
}

/**
 * Serves files from the Linux environment to the preview, under [Preview.FILES_HOST]. Hidden
 * files and folders (agent sign-ins, keys) are never served, and nothing outside the guest is
 * reachable. No CORS headers are sent, so another site loaded in the preview cannot read them.
 */
object PreviewFiles {

    fun respond(context: Context, guestPath: String): WebResourceResponse {
        val rootfs = ShellPaths.rootfsDir(context)
        val segments = guestPath.split('/').filter { it.isNotEmpty() }
        if (segments.any { it.startsWith(".") }) return error(403, "This file is hidden.")
        var file = File(rootfs, segments.joinToString("/"))
        val canonical = runCatching { file.canonicalFile }.getOrNull()
        val root = rootfs.canonicalFile
        if (canonical == null || !(canonical.path == root.path || canonical.path.startsWith(root.path + File.separator))) {
            return error(403, "Outside the Linux environment.")
        }
        if (file.isDirectory) {
            val index = listOf("index.html", "index.htm").map { File(file, it) }.firstOrNull { it.isFile }
            if (index == null) return listing(file, guestPath)
            file = index
        }
        if (!file.isFile) return error(404, "Not found: $guestPath")
        return runCatching {
            WebResourceResponse(mimeOf(file.name), charsetOf(file.name), FileInputStream(file)).apply {
                // Always fresh: the page is reloaded as files change.
                responseHeaders = mapOf("Cache-Control" to "no-store")
            }
        }.getOrElse { error(403, "This file cannot be read.") }
    }

    private fun mimeOf(name: String): String = when (val ext = name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "js", "mjs", "cjs" -> "text/javascript"
        "css" -> "text/css"
        "json", "map" -> "application/json"
        "svg" -> "image/svg+xml"
        "wasm" -> "application/wasm"
        "md", "txt", "log", "csv", "ts", "tsx", "jsx", "py", "sh" -> "text/plain"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun charsetOf(name: String): String? =
        if (mimeOf(name).let { it.startsWith("text/") || it == "application/json" || it == "image/svg+xml" }) "utf-8" else null

    private fun listing(dir: File, guestPath: String): WebResourceResponse {
        val base = guestPath.trimEnd('/')
        val rows = (dir.listFiles() ?: emptyArray())
            .filter { !it.name.startsWith(".") }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .joinToString("") { f ->
                val name = esc(f.name) + if (f.isDirectory) "/" else ""
                "<li><a href=\"${Preview.fileUrl("$base/${f.name}")}\">$name</a></li>"
            }
        return html(
            200,
            "<h1>${esc(base.ifEmpty { "/" })}</h1>" +
                (if (base.count { it == '/' } > 1) "<p><a href=\"${Preview.fileUrl(base.substringBeforeLast('/'))}\">..</a></p>" else "") +
                "<ul>$rows</ul>",
        )
    }

    private fun error(code: Int, message: String) = html(code, "<h1>$code</h1><p>${esc(message)}</p>")

    private fun html(code: Int, body: String): WebResourceResponse {
        val page = "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width\">" +
            "<style>body{font:15px system-ui,sans-serif;margin:24px;color:#ddd;background:#0b0b0d}" +
            "a{color:#ff9a5c;text-decoration:none}li{margin:6px 0}h1{font-size:18px;font-weight:500}</style>$body"
        return WebResourceResponse("text/html", "utf-8", code, if (code == 200) "OK" else "Error", mapOf("Cache-Control" to "no-store"), page.byteInputStream())
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /**
     * The newest modification time under [dir] (skipping hidden folders, node_modules and other
     * build output), to reload a file preview when anything in its folder changes.
     */
    fun newest(dir: File, limit: Int = 3000): Long {
        var newest = 0L
        var seen = 0
        val stack = ArrayDeque<File>()
        stack.add(dir)
        while (stack.isNotEmpty() && seen < limit) {
            val kids = stack.removeLast().listFiles() ?: continue
            for (f in kids) {
                if (f.name.startsWith(".") || f.name in SKIP) continue
                seen++
                if (f.isDirectory) stack.add(f) else newest = maxOf(newest, f.lastModified())
            }
        }
        return newest
    }

    private val SKIP = setOf("node_modules", "dist", "build", "target", "__pycache__", "venv", "screenshots")
}
