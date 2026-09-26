package com.termfold.app.preview

import android.content.Context
import android.util.Log
import com.termfold.app.shell.ShellPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Lets agents in the Linux environment use the preview browser: open pages, read them, click,
 * type, scroll, take screenshots, read the console. The agent side is `termfold-browser` (a
 * command and an MCP server) talking to this over loopback.
 *
 * Only programs in the guest can use it: the port and a random key are written to
 * ~/.termfold/browser.json, inside the app's private storage, and every request must carry the
 * key. The listener is bound to 127.0.0.1 only.
 */
object BrowserBridge {

    private const val TAG = "BrowserBridge"
    private const val CONFIG = "root/.termfold/browser.json"

    /** What drives the WebView: the preview pane registers one while it is on screen. */
    interface Controller {
        suspend fun run(action: String, args: JSONObject): JSONObject
    }

    @Volatile
    var controller: Controller? = null

    /** What the user sees: whether an agent is driving, what it just did, and whether it is paused. */
    data class Activity(val active: Boolean = false, val label: String = "", val paused: Boolean = false)

    private val _activity = MutableStateFlow(Activity())
    val activity: StateFlow<Activity> = _activity.asStateFlow()

    @Volatile private var lastCommand = 0L

    /** Commands being carried out right now; the frame stays lit while any is running. */
    private val running = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var started = false
    private lateinit var token: String

    /** The user took the browser back: commands are refused until they let the agent continue. */
    fun pause() = _activity.update { it.copy(paused = true, active = false) }

    fun resume() = _activity.update { it.copy(paused = false) }

    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        val app = context.applicationContext
        Thread({ serve(app) }, "termfold-browser").apply { isDaemon = true }.start()
        // The busy frame fades a few seconds after the agent's last action.
        Thread({
            while (true) {
                Thread.sleep(1000)
                val a = _activity.value
                if (a.active && running.get() == 0 && System.currentTimeMillis() - lastCommand > 6000) _activity.update { it.copy(active = false) }
            }
        }, "termfold-browser-idle").apply { isDaemon = true }.start()
    }

    private fun serve(context: Context) {
        val server = runCatching {
            ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        }.getOrElse {
            Log.w(TAG, "Could not start the browser bridge", it)
            return
        }
        token = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        runCatching {
            val file = File(ShellPaths.rootfsDir(context), CONFIG)
            file.parentFile?.mkdirs()
            file.writeText(JSONObject().put("port", server.localPort).put("token", token).toString())
            file.setReadable(false, false)
            file.setReadable(true, true)
        }.onFailure { Log.w(TAG, "Could not write the browser config", it) }
        while (true) {
            val socket = runCatching { server.accept() }.getOrNull() ?: continue
            Thread({ handle(context, socket) }, "termfold-browser-call").apply { isDaemon = true }.start()
        }
    }

    private fun handle(context: Context, socket: Socket) {
        socket.use { s ->
            s.soTimeout = 120_000
            val input = BufferedInputStream(s.getInputStream())
            val headers = readHeaders(input) ?: return
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length > 2_000_000) return respond(s, 413, JSONObject().put("error", "Request too large"))
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) break
                read += n
            }
            val request = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull()
                ?: return respond(s, 400, JSONObject().put("error", "Bad request"))
            if (!sameKey(request.optString("token"))) return respond(s, 403, JSONObject().put("error", "Wrong key"))
            val result = runBlocking { execute(context, request.optString("action"), request.optJSONObject("args") ?: JSONObject()) }
            respond(s, 200, result)
        }
    }

    private suspend fun execute(context: Context, action: String, args: JSONObject): JSONObject {
        if (_activity.value.paused) {
            return JSONObject().put(
                "error",
                "The user has taken over the browser and paused agent control. Ask them to tap " +
                    "\"Let agent continue\" in the preview, then try again.",
            )
        }
        lastCommand = System.currentTimeMillis()
        _activity.update { it.copy(active = true, label = labelFor(action, args)) }
        // The browser draws only while the pane is on screen: open it and wait for it.
        var driver = controller
        if (driver == null) {
            withContext(Dispatchers.Main) { Preview.open() }
            val until = System.currentTimeMillis() + 15_000
            while (driver == null && System.currentTimeMillis() < until) {
                delay(100)
                driver = controller
            }
        }
        if (driver == null) {
            return JSONObject().put("error", "The preview browser did not open. Is TermFold on screen?")
        }
        running.incrementAndGet()
        return runCatching {
            withTimeout(90_000) { driver.run(action, args) }
        }.getOrElse { JSONObject().put("error", it.message ?: it.javaClass.simpleName) }.also {
            running.decrementAndGet()
            lastCommand = System.currentTimeMillis()
        }
    }

    private fun labelFor(action: String, args: JSONObject): String = when (action) {
        "open" -> "Opening " + Preview.display(Preview.normalize(args.optString("url"), null))
        "click" -> "Clicking"
        "fill", "type" -> "Typing"
        "press" -> "Pressing " + args.optString("key")
        "scroll" -> "Scrolling"
        "screenshot" -> "Taking a screenshot"
        "snapshot", "text" -> "Reading the page"
        "wait" -> "Waiting"
        "select", "check" -> "Choosing"
        "hover" -> "Hovering"
        "upload" -> "Uploading a file"
        "console", "network" -> "Reading the console"
        else -> "Working"
    }

    /** The label shown on the pointer and in the banner while a step runs. */
    fun setLabel(label: String) = _activity.update { it.copy(label = label) }

    private fun sameKey(given: String): Boolean =
        ::token.isInitialized && MessageDigest.isEqual(given.toByteArray(), token.toByteArray())

    private fun readHeaders(input: BufferedInputStream): Map<String, String>? {
        val lines = ArrayList<String>()
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return null
            if (c == '\n'.code) {
                val text = line.toString().trimEnd('\r')
                if (text.isEmpty()) break
                lines += text
                line.setLength(0)
                if (lines.size > 64) return null
            } else {
                line.append(c.toChar())
                if (line.length > 8192) return null
            }
        }
        if (lines.isEmpty() || !lines[0].startsWith("POST ")) return null
        return lines.drop(1).mapNotNull { h ->
            val i = h.indexOf(':')
            if (i <= 0) null else h.substring(0, i).trim().lowercase() to h.substring(i + 1).trim()
        }.toMap()
    }

    private fun respond(socket: Socket, code: Int, body: JSONObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n" +
            "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        runCatching {
            socket.getOutputStream().apply {
                write(head.toByteArray())
                write(bytes)
                flush()
            }
        }
    }
}
