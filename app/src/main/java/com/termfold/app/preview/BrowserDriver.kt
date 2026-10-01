package com.termfold.app.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Base64
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebView
import com.termfold.app.shell.ShellPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume

/** What the driver needs from the preview pane around it. */
interface DriverUi {
    fun projectDir(): String?
    fun navigate(url: String)
    fun progress(): Int
    fun loadError(): String?
    /** The HTTP error the page itself was served with (404, 500...), if any. */
    fun httpError(): String?
    fun setViewport(mode: String): Boolean
    fun viewportName(): String
    /** Glides the agent's pointer to a point in the WebView (view pixels), showing [label]. */
    suspend fun moveCursor(x: Float, y: Float, label: String)
    fun pulse()
    fun consoleSize(): Int
    fun consoleText(errorsOnly: Boolean, since: Int): String
    fun consoleErrorsSince(since: Int): Int
    fun networkText(failedOnly: Boolean): String
    fun offerUpload(file: File)
    fun uploadTaken(): Boolean
}

/**
 * Drives the preview WebView for an agent. Taps, swipes and key presses are real input events
 * sent to the WebView, so pages see them exactly as a person's; reading the page (the numbered
 * map of buttons, links and fields) and filling fields go through a small script in the page.
 */
class BrowserDriver(
    private val context: Context,
    private val web: WebView,
    private val ui: DriverUi,
) : BrowserBridge.Controller {

    override suspend fun run(action: String, args: JSONObject): JSONObject = withContext(Dispatchers.Main) {
        // A minimized browser is paused to save power; the agent needs it running.
        web.onResume()
        when (action) {
            "open" -> open(args)
            "back" -> history { if (web.canGoBack()) web.goBack() else return@history "There is no earlier page." ; null }
            "forward" -> history { if (web.canGoForward()) web.goForward() else return@history "There is no later page."; null }
            "reload" -> history { web.reload(); null }
            "status" -> ok(pageLine())
            "snapshot" -> snapshot(args)
            "text" -> text(args)
            "outline" -> outline()
            "click" -> click(args)
            "fill" -> fill(args)
            "type" -> type(args)
            "press" -> press(args)
            "select" -> select(args)
            "check" -> check(args)
            "hover" -> hover(args)
            "scroll" -> scroll(args)
            "wait" -> waitFor(args)
            "screenshot" -> screenshot(args)
            "console" -> ok(ui.consoleText(args.optBoolean("errors_only"), 0))
            "network" -> ok(ui.networkText(args.optBoolean("failed_only")))
            "eval" -> eval(args)
            "viewport" -> viewport(args)
            "upload" -> upload(args)
            else -> err("Unknown action \"$action\".")
        }
    }

    // ---- Navigation --------------------------------------------------------------------------

    private suspend fun open(args: JSONObject): JSONObject {
        val raw = args.optString("url").trim()
        if (raw.isEmpty()) return err("Give a URL, a port (5173) or a file path.")
        val url = Preview.normalize(raw, ui.projectDir())
        ui.navigate(url)
        awaitLoad()
        ui.loadError()?.let { return err("Could not open ${Preview.display(url)}: $it. If it is a dev server, is it running?") }
        ui.httpError()?.let { return err("${Preview.display(url)} answered $it. " + pageLine()) }
        return ok("Opened. " + pageLine())
    }

    private suspend fun history(step: () -> String?): JSONObject {
        step()?.let { return err(it) }
        awaitLoad()
        return ok(pageLine())
    }

    private suspend fun viewport(args: JSONObject): JSONObject {
        val mode = args.optString("mode").lowercase()
        if (!ui.setViewport(mode)) return err("Viewport must be fit, phone or desktop.")
        awaitLoad()
        return ok("Viewport is now $mode. " + pageLine())
    }

    // ---- Reading -----------------------------------------------------------------------------

    /** The visible elements of the last snapshot, to show only what changed on request. */
    private var lastSnapshot: Pair<String, List<String>>? = null

    private var lastShotHash = 0L
    private var lastShotPath: String? = null

    /**
     * Reads the page as a numbered list of controls, kept short on purpose: only what is on
     * screen, capped, without link addresses. The complete list (every element, with its
     * address) is written to a file for the agent to search instead of reading it all again.
     */
    private suspend fun snapshot(args: JSONObject): JSONObject {
        val scope = args.optString("scope").trim()
        val options = JSONObject()
        if (scope.isNotEmpty()) {
            options.put("scope", if (scope.all(Char::isDigit)) JSONObject().put("ref", scope.toInt()) else JSONObject().put("selector", scope))
        }
        val data = call("snapshot($options)") ?: return err("The page could not be read.")
        if (data.has("error")) return err("Nothing on the page matches scope \"$scope\".")
        val items = data.optJSONArray("items") ?: JSONArray()
        val find = args.optString("find").trim().lowercase()
        val all = args.optBoolean("all", false)
        val links = args.optBoolean("links", false)
        val changed = args.optBoolean("changed", false)

        fun line(o: JSONObject, withHref: Boolean, marker: Boolean): String {
            val sb = StringBuilder()
            sb.append('[').append(o.optInt("ref")).append("] ").append(o.optString("role"))
            val name = o.optString("name").take(80)
            if (name.isNotEmpty()) sb.append(" \"").append(name).append('"')
            if (o.has("value")) sb.append(" = \"").append(o.optString("value").take(60)).append('"')
            if (o.has("checked")) sb.append(if (o.optBoolean("checked")) " (checked)" else " (unchecked)")
            if (o.optBoolean("disabled")) sb.append(" (disabled)")
            if (withHref && o.has("href")) sb.append(" → ").append(o.optString("href").take(120))
            if (marker && !o.optBoolean("visible")) sb.append(" ↓")
            return sb.toString()
        }

        val objects = (0 until items.length()).map { items.getJSONObject(it) }
        val header = StringBuilder()
        header.append("Page: ").append(data.optString("title").ifBlank { "(untitled)" }).append('\n')
        header.append("URL: ").append(data.optString("url")).append('\n')
        header.append("Scrolled ").append(data.optInt("scrollY")).append(" of ").append(data.optInt("scrollHeight"))
            .append(" (view ").append(data.optInt("viewHeight")).append(")\n")

        // The complete list goes to a file; the answer below is the short version of it.
        val full = StringBuilder(header).append("Elements:\n")
        objects.forEach { full.append(line(it, withHref = true, marker = true)).append('\n') }
        val path = runCatching {
            val dir = File(ShellPaths.rootfsDir(context), SNAPSHOT_DIR.trimStart('/')).apply { mkdirs() }
            File(dir, "snapshot.txt").writeText(full.toString())
            "$SNAPSHOT_DIR/snapshot.txt"
        }.getOrNull()

        val visible = objects.filter { it.optBoolean("visible") }
        val visibleLines = visible.map { line(it, withHref = false, marker = false) }
        val previous = lastSnapshot?.takeIf { it.first == data.optString("url") }?.second
        lastSnapshot = data.optString("url") to visibleLines

        val sb = StringBuilder(header)
        var hidden = 0
        if (changed && previous != null) {
            val added = visibleLines.filter { it !in previous.toSet() }
            val removed = previous.filter { it !in visibleLines.toSet() }
            if (added.isEmpty() && removed.isEmpty()) {
                sb.append("No change in the ${visibleLines.size} elements on screen.\n")
            } else {
                sb.append("Changed since the last snapshot (").append(visibleLines.size).append(" on screen):\n")
                added.take(40).forEach { sb.append("+ ").append(it).append('\n') }
                removed.take(40).forEach { sb.append("- ").append(it).append('\n') }
            }
        } else {
            val shown: List<JSONObject> = when {
                find.isNotEmpty() -> objects.filter {
                    (it.optString("name") + " " + it.optString("role") + " " + it.optString("value") + " " + it.optString("href"))
                        .lowercase().contains(find)
                }.also { hidden = (it.size - 40).coerceAtLeast(0) }.take(40)
                all -> objects
                else -> visible.take(SNAPSHOT_LIMIT).also { hidden = objects.size - it.size }
            }
            if (find.isEmpty() && scope.isEmpty() && !all) {
                val heads = data.optJSONArray("headings") ?: JSONArray()
                if (heads.length() > 0) {
                    sb.append("Headings: ").append((0 until minOf(heads.length(), 6)).joinToString(" | ") { heads.getString(it).take(60) }).append('\n')
                }
            }
            if (find.isNotEmpty()) sb.append("Elements matching \"").append(find).append("\":\n")
            else sb.append("Elements (use the number with click, fill, select, check, hover, scroll):\n")
            shown.forEach { sb.append(line(it, withHref = links || find.isNotEmpty(), marker = all || find.isNotEmpty())).append('\n') }
            if (shown.isEmpty()) sb.append(if (find.isNotEmpty()) "  (nothing matches)\n" else "  (no buttons, links or fields)\n")
        }
        if (hidden > 0) {
            sb.append("+ $hidden more (off screen or over the limit): ask for all, find \"text\", scope a part of the page")
            if (path != null) sb.append(", or search the full list: grep -i word $path")
            sb.append('\n')
        }
        val errors = ui.consoleErrorsSince(0)
        if (errors > 0) sb.append("Console: $errors error(s). Run console to read them.\n")
        return ok(sb.toString().trimEnd())
    }

    private suspend fun text(args: JSONObject): JSONObject {
        val options = JSONObject().put("max", args.optInt("max", 8000).coerceIn(500, 30000))
        args.optString("selector").trim().takeIf { it.isNotEmpty() }?.let { options.put("selector", it) }
        val data = call("text($options)") ?: return err("The page could not be read.")
        if (data.has("error")) return err("Nothing on the page matches that selector.")
        return ok("Page: ${data.optString("title")}\nURL: ${data.optString("url")}\n\n${data.optString("text")}")
    }

    /** Just the headings: the shape of a long page for a few tokens. */
    private suspend fun outline(): JSONObject {
        val data = call("outline()") ?: return err("The page could not be read.")
        val heads = data.optJSONArray("headings") ?: JSONArray()
        val sb = StringBuilder("Page: ").append(data.optString("title")).append("\nURL: ").append(data.optString("url")).append('\n')
        for (i in 0 until heads.length()) sb.append(heads.getString(i)).append('\n')
        if (heads.length() == 0) sb.append("(no headings)\n")
        return ok(sb.toString().trimEnd())
    }

    // ---- Acting ------------------------------------------------------------------------------

    private suspend fun click(args: JSONObject): JSONObject {
        val before = ui.consoleSize()
        val (x, y, target) = locate(args) ?: return notFound(args)
        if (target?.optBoolean("disabled") == true) return err("${describe(target)} is disabled.")
        ui.moveCursor(x, y, "Click" + (target?.let { " · " + it.optString("name").take(24) } ?: ""))
        tap(x, y)
        ui.pulse()
        settle()
        return ok("Clicked ${target?.let(::describe) ?: "at $x, $y"}. " + pageLine() + consoleNote(before))
    }

    private suspend fun fill(args: JSONObject): JSONObject {
        val before = ui.consoleSize()
        val text = args.optString("text")
        // Here "text" is what to type; the field is found by its number or a selector.
        val where = JSONObject(args.toString()).apply { remove("text") }
        if (!where.has("ref") && !where.has("selector")) return err("Say which field: give ref (from snapshot) or selector.")
        val point = call("point(${spec(where)})") ?: return notFound(where)
        if (point.has("error")) return notFound(where)
        // A real site's password field: typed as asked, with a reminder the agent must pass on.
        val realPassword = point.optString("type").equals("password", true) && !Preview.isLocal(web.url.orEmpty())
        val (x, y) = toPx(point)
        ui.moveCursor(x, y, "Type · " + point.optString("name").take(20))
        ui.pulse()
        val result = call("fill(${spec(where, JSONObject().put("text", text))})") ?: return err("Could not type there.")
        if (result.has("error")) return err(result.optString("error"))
        if (args.optBoolean("submit")) {
            key(KeyEvent.KEYCODE_ENTER, 0)
            settle()
        } else {
            delay(120)
        }
        val shown = if (result.optBoolean("password")) "(hidden)" else "\"${result.optString("value")}\""
        val warning = if (realPassword) {
            " IMPORTANT: this was a password on a real website. Tell the user it passed through you (the AI) " +
                "and recommend they change it after this session."
        } else {
            ""
        }
        return ok("Filled ${describe(result)} with $shown" + (if (args.optBoolean("submit")) " and pressed Enter. " else ". ") + pageLine() + consoleNote(before) + warning)
    }

    private suspend fun type(args: JSONObject): JSONObject {
        val text = args.optString("text")
        web.requestFocus()
        val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
        if (events == null) {
            js("document.execCommand('insertText', false, ${JSONObject.quote(text)})")
        } else {
            events.forEach { event ->
                web.dispatchKeyEvent(KeyEvent(event))
                if (event.action == KeyEvent.ACTION_UP) delay(12)
            }
        }
        delay(120)
        return ok("Typed ${text.length} characters into the focused field.")
    }

    private suspend fun press(args: JSONObject): JSONObject {
        val before = ui.consoleSize()
        val spec = args.optString("key").trim()
        if (spec.isEmpty()) return err("Give a key, like Enter, Tab, Escape, ArrowDown or Control+A.")
        val parts = spec.split('+').map { it.trim() }
        var meta = 0
        parts.dropLast(1).forEach { m ->
            meta = meta or when (m.lowercase()) {
                "control", "ctrl" -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
                "shift" -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
                "alt" -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
                "meta", "cmd", "command" -> KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
                else -> return err("Unknown modifier \"$m\".")
            }
        }
        val code = keyCode(parts.last()) ?: return err("Unknown key \"${parts.last()}\".")
        BrowserBridge.setLabel("Pressing $spec")
        key(code, meta)
        settle()
        return ok("Pressed $spec. " + pageLine() + consoleNote(before))
    }

    private suspend fun select(args: JSONObject): JSONObject {
        val point = call("point(${spec(args)})")
        if (point == null || point.has("error")) return notFound(args)
        val (x, y) = toPx(point)
        ui.moveCursor(x, y, "Choose · " + args.optString("value").take(20))
        ui.pulse()
        val result = call("select(${spec(args, JSONObject().put("value", args.optString("value")))})") ?: return err("Could not choose.")
        if (result.has("error")) {
            val options = result.optJSONArray("options")
            return err(result.optString("error") + (options?.let { "\nOptions: " + (0 until it.length()).joinToString(", ") { i -> it.getString(i) } } ?: ""))
        }
        settle()
        return ok("Selected \"${result.optString("value")}\" in ${describe(result)}. " + pageLine())
    }

    private suspend fun check(args: JSONObject): JSONObject {
        val want = if (args.has("checked")) args.optBoolean("checked") else true
        val state = call("state(${spec(args)})")
        if (state == null || state.has("error")) return notFound(args)
        if (state.optBoolean("checked") == want) return ok("${describe(state)} is already ${if (want) "checked" else "unchecked"}.")
        return click(args).also {
            if (it.has("error")) return it
        }.let {
            val after = call("state(${spec(args)})")
            ok("${describe(state)} is now ${if (after?.optBoolean("checked") == true) "checked" else "unchecked"}. " + pageLine())
        }
    }

    private suspend fun hover(args: JSONObject): JSONObject {
        val (x, y, target) = locate(args) ?: return notFound(args)
        ui.moveCursor(x, y, "Hover")
        call("hover(${spec(args)})")
        delay(300)
        return ok("Hovering over ${target?.let(::describe) ?: "$x, $y"}.")
    }

    private suspend fun scroll(args: JSONObject): JSONObject {
        if (args.has("ref") || args.has("selector") || args.has("text")) {
            val (x, y, target) = locate(args) ?: return notFound(args)
            ui.moveCursor(x, y, "Scroll here")
            return ok("Scrolled ${target?.let(::describe) ?: "the element"} into view. " + position())
        }
        val direction = args.optString("direction", "down").lowercase()
        when (direction) {
            "top" -> js("(document.scrollingElement||document.documentElement).scrollTo({top:0,behavior:'smooth'})")
            "bottom" -> js("(document.scrollingElement||document.documentElement).scrollTo({top:1e9,behavior:'smooth'})")
            "up", "down" -> {
                val pages = args.optDouble("amount", 0.8).coerceIn(0.1, 5.0)
                var remaining = pages
                while (remaining > 0.01) {
                    val step = remaining.coerceAtMost(0.8)
                    swipe(if (direction == "down") step else -step)
                    remaining -= step
                }
            }
            else -> return err("Direction must be up, down, top or bottom.")
        }
        delay(450)
        return ok("Scrolled $direction. " + position())
    }

    private suspend fun waitFor(args: JSONObject): JSONObject {
        val timeout = (args.optDouble("timeout", 10.0) * 1000).toLong().coerceIn(500, 60_000)
        val until = SystemClock.uptimeMillis() + timeout
        val what = when {
            args.has("text") -> "the text \"${args.optString("text")}\""
            args.has("selector") -> "\"${args.optString("selector")}\""
            else -> "the page to load"
        }
        BrowserBridge.setLabel("Waiting for " + what.take(30))
        while (SystemClock.uptimeMillis() < until) {
            val has = call("has(${spec(args)})")
            if (has?.optBoolean("found") == true && ui.progress() >= 100) return ok("Found $what. " + pageLine())
            delay(300)
        }
        return err("Timed out after ${timeout / 1000}s waiting for $what. " + pageLine())
    }

    private suspend fun eval(args: JSONObject): JSONObject {
        val script = args.optString("script")
        if (script.isBlank()) return err("Give a script.")
        val wrapped = "(function(){try{var r=(0,eval)(${JSONObject.quote(script)});" +
            "if(r&&typeof r.then==='function')return JSON.stringify({note:'Returned a promise; await it with wait or read the result later.'});" +
            "return JSON.stringify(r===undefined?null:r);}catch(e){return JSON.stringify({error:String(e)});}})()"
        val raw = js(wrapped)
        val decoded = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: raw
        return ok(decoded.take(20_000))
    }

    private suspend fun upload(args: JSONObject): JSONObject {
        val path = args.optString("path")
        if (path.isBlank()) return err("Give the path of the file to upload.")
        val rootfs = ShellPaths.rootfsDir(context)
        val guest = if (path.startsWith("/")) path else workDir(args) + "/" + path
        val file = File(rootfs, guest.trimStart('/'))
        val inside = runCatching { file.canonicalPath.startsWith(rootfs.canonicalPath + File.separator) }.getOrDefault(false)
        if (!inside || !file.isFile || guest.split('/').any { it.startsWith(".") }) return err("No such file: $guest")
        ui.offerUpload(file)
        val result = click(args)
        if (result.has("error")) return result
        val until = SystemClock.uptimeMillis() + 3000
        while (!ui.uploadTaken() && SystemClock.uptimeMillis() < until) delay(100)
        return if (ui.uploadTaken()) ok("Chose ${file.name} for upload. " + pageLine()) else err("The page did not ask for a file after the click. Is that a file field?")
    }

    // ---- Screenshots -------------------------------------------------------------------------

    private suspend fun screenshot(args: JSONObject): JSONObject {
        BrowserBridge.setLabel("Taking a screenshot")
        delay(80)
        if (web.width == 0 || web.height == 0) return err("The browser is not on screen.")
        // A capture taken while the page is still drawing (right after a scroll, say) can come
        // out as one flat colour; give it a moment and take it again.
        // A picture of one element: bring it into view and remember where it is.
        val aim = JSONObject().also { t -> listOf("ref", "selector", "text").forEach { k -> if (args.has(k)) t.put(k, args.get(k)) } }
        var region: JSONObject? = null
        if (aim.length() > 0) {
            region = call("box($aim)")
            if (region == null || region.has("error")) return err("Could not find that element to take a picture of it.")
            delay(150)
        }
        var bitmap = Bitmap.createBitmap(web.width, web.height, Bitmap.Config.ARGB_8888)
        web.draw(Canvas(bitmap))
        var tries = 0
        while (isBlank(bitmap) && tries < 4) {
            tries++
            delay(250L * tries)
            bitmap.eraseColor(0)
            web.draw(Canvas(bitmap))
        }
        if (region != null) {
            val scale = bitmap.width / region.optDouble("vw", bitmap.width.toDouble()).coerceAtLeast(1.0)
            val x = (region.optDouble("x") * scale).toInt().coerceIn(0, bitmap.width - 8)
            val y = (region.optDouble("y") * scale).toInt().coerceIn(0, bitmap.height - 8)
            val w = (region.optDouble("w") * scale).toInt().coerceAtMost(bitmap.width - x)
            val h = (region.optDouble("h") * scale).toInt().coerceAtMost(bitmap.height - y)
            if (w >= 8 && h >= 8) {
                val cropped = Bitmap.createBitmap(bitmap, x, y, w, h)
                bitmap.recycle()
                bitmap = cropped
            }
        }
        val blank = isBlank(bitmap)
        val result = JSONObject()
        // The WebView may only be touched on the main thread; read what is needed first.
        val shown = web.url?.let(Preview::display).orEmpty()
        return withContext(Dispatchers.IO) {
            val summary = StringBuilder("Screenshot of ").append(shown)
            // Kept in the project only when asked; otherwise a temporary file the agent can open,
            // since checking work can mean dozens of screenshots.
            val keep = args.optBoolean("save", false)
            // Asked to skip a picture that would look the same as the last one.
            val hash = pictureHash(bitmap)
            if (args.optBoolean("if_changed", false) && !keep && !blank && hash == lastShotHash) {
                bitmap.recycle()
                return@withContext result.put(
                    "text",
                    "Unchanged since the last screenshot" + (lastShotPath?.let { " ($it)" } ?: "") + ".",
                )
            }
            lastShotHash = hash
            lastShotPath = null
            if (keep || !args.optBoolean("image", false)) {
                val rootfs = ShellPaths.rootfsDir(context)
                val guestDir = if (keep) workDir(args) + "/screenshots" else TEMP_SHOTS
                val dir = File(rootfs, guestDir.trimStart('/')).apply { mkdirs() }
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS", java.util.Locale.US).format(java.util.Date())
                // Kept screenshots stay full-quality PNG; the throwaway ones the agent opens to look
                // are a smaller JPEG, because the picture is read in tokens by its size.
                val file = File(dir, "browser-$stamp." + if (keep) "png" else "jpg")
                file.outputStream().use { out ->
                    if (keep) {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    } else {
                        val s = (1280f / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1f)
                        val small = if (s < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * s).toInt().coerceAtLeast(1), (bitmap.height * s).toInt().coerceAtLeast(1), true) else bitmap
                        small.compress(Bitmap.CompressFormat.JPEG, 80, out)
                        if (small !== bitmap) small.recycle()
                    }
                }
                if (!keep) pruneTemporary(dir)
                val guest = "$guestDir/${file.name}"
                result.put("path", guest)
                if (!keep) lastShotPath = guest
                summary.append(if (keep) " saved to " else " (temporary) at ").append(guest)
            }
            if (args.optBoolean("image", false)) {
                val scale = (1024f / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1f)
                val small = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
                val bytes = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
                if (small !== bitmap) small.recycle()
                result.put("image", Base64.encodeToString(bytes, Base64.NO_WRAP)).put("mime", "image/jpeg")
            }
            bitmap.recycle()
            if (blank) summary.append(". It is a single flat colour: the page may be blank or still drawing; scroll a little or wait, then take another")
            result.put("text", summary.append('.').toString())
        }
    }

    // ---- Input -------------------------------------------------------------------------------

    private suspend fun tap(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        touch(down, down, MotionEvent.ACTION_DOWN, x, y)
        delay(70)
        touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y)
    }

    /** A finger drag over [pages] of the view (positive scrolls down), ending still so it does not fling. */
    private suspend fun swipe(pages: Double) {
        val x = web.width / 2f
        val distance = (web.height * 0.6f * pages.toFloat() / 0.8f).coerceIn(-web.height * 0.7f, web.height * 0.7f)
        val startY = web.height / 2f + distance / 2f
        val endY = web.height / 2f - distance / 2f
        ui.moveCursor(x, startY, "Scroll")
        val down = SystemClock.uptimeMillis()
        touch(down, down, MotionEvent.ACTION_DOWN, x, startY)
        val steps = 14
        for (i in 1..steps) {
            delay(22)
            val y = startY + (endY - startY) * i / steps
            touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, y)
        }
        repeat(4) {
            delay(30)
            touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, endY)
        }
        touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, endY)
    }

    private fun touch(down: Long, time: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, time, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        web.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun key(code: Int, meta: Int) {
        web.requestFocus()
        val down = SystemClock.uptimeMillis()
        web.dispatchKeyEvent(KeyEvent(down, down, KeyEvent.ACTION_DOWN, code, 0, meta))
        web.dispatchKeyEvent(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, meta))
    }

    private fun keyCode(name: String): Int? = when (name.lowercase()) {
        "enter", "return" -> KeyEvent.KEYCODE_ENTER
        "tab" -> KeyEvent.KEYCODE_TAB
        "escape", "esc" -> KeyEvent.KEYCODE_ESCAPE
        "backspace" -> KeyEvent.KEYCODE_DEL
        "delete", "del" -> KeyEvent.KEYCODE_FORWARD_DEL
        "space", " " -> KeyEvent.KEYCODE_SPACE
        "arrowup", "up" -> KeyEvent.KEYCODE_DPAD_UP
        "arrowdown", "down" -> KeyEvent.KEYCODE_DPAD_DOWN
        "arrowleft", "left" -> KeyEvent.KEYCODE_DPAD_LEFT
        "arrowright", "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
        "home" -> KeyEvent.KEYCODE_MOVE_HOME
        "end" -> KeyEvent.KEYCODE_MOVE_END
        "pageup" -> KeyEvent.KEYCODE_PAGE_UP
        "pagedown" -> KeyEvent.KEYCODE_PAGE_DOWN
        else -> when {
            name.length == 1 && name[0].isLetter() -> KeyEvent.KEYCODE_A + (name.lowercase()[0] - 'a')
            name.length == 1 && name[0].isDigit() -> KeyEvent.KEYCODE_0 + (name[0] - '0')
            Regex("^f([1-9]|1[0-2])$", RegexOption.IGNORE_CASE).matches(name) -> KeyEvent.KEYCODE_F1 + name.drop(1).toInt() - 1
            else -> null
        }
    }

    // ---- Helpers -----------------------------------------------------------------------------

    private data class Located(val x: Float, val y: Float, val target: JSONObject?)

    /** An element (by ref, selector or text) scrolled into view, or a point given as x/y in CSS pixels. */
    private suspend fun locate(args: JSONObject): Located? {
        if (args.has("x") && args.has("y") && !args.has("ref") && !args.has("text") && !args.has("selector")) {
            val vp = call("viewport()") ?: return null
            val scale = web.width / vp.optDouble("vw", web.width.toDouble()).toFloat()
            return Located(args.optDouble("x").toFloat() * scale, args.optDouble("y").toFloat() * scale, null)
        }
        val point = call("point(${spec(args)})") ?: return null
        if (point.has("error")) return null
        delay(60)
        // Scrolling it into view may have moved it; measure again once it has settled.
        val settled = call("point(${spec(args)})") ?: point
        val (x, y) = toPx(settled)
        return Located(x, y, settled)
    }

    /** The agent's project (from the folder it runs in), else the preview's, else home. */
    private fun workDir(args: JSONObject): String {
        val cwd = args.optString("cwd").trimEnd('/')
        Regex("^/root/projects/[^/]+").find(cwd)?.let { return it.value }
        if (cwd.startsWith("/root/") || cwd == "/root") return ui.projectDir() ?: cwd
        return ui.projectDir() ?: "/root"
    }

    private fun toPx(point: JSONObject): Pair<Float, Float> {
        val vw = point.optDouble("vw", web.width.toDouble()).toFloat().takeIf { it > 0 } ?: web.width.toFloat()
        val scale = web.width / vw
        val x = (point.optDouble("x").toFloat() * scale).coerceIn(1f, web.width - 1f)
        val y = (point.optDouble("y").toFloat() * scale).coerceIn(1f, web.height - 1f)
        return x to y
    }

    private fun spec(args: JSONObject, extra: JSONObject = JSONObject()): String {
        val s = JSONObject()
        if (args.has("ref")) s.put("ref", args.optInt("ref"))
        if (args.has("selector")) s.put("selector", args.optString("selector"))
        if (args.has("text") && !extra.has("text")) s.put("text", args.optString("text"))
        extra.keys().forEach { s.put(it, extra.get(it)) }
        return s.toString()
    }

    private fun describe(o: JSONObject): String {
        val name = o.optString("name")
        return "[${o.optInt("ref")}] ${o.optString("role")}" + if (name.isNotEmpty()) " \"$name\"" else ""
    }

    private fun notFound(args: JSONObject): JSONObject = err(
        "No element matches " + when {
            args.has("ref") -> "[${args.optInt("ref")}]. The page may have changed; run snapshot again."
            args.has("selector") -> "the selector \"${args.optString("selector")}\"."
            args.has("text") -> "the text \"${args.optString("text")}\"."
            else -> "that. Give ref, text or selector."
        },
    )

    private suspend fun pageLine(): String {
        val title = runCatching { JSONTokener(js("document.title")).nextValue() as? String }.getOrNull().orEmpty()
        return "Page: " + title.ifBlank { "(untitled)" } + " · " + (web.url?.let(Preview::display) ?: "")
    }

    private suspend fun position(): String {
        val p = call("pos()") ?: return ""
        return "Now at ${p.optInt("scrollY")} of ${p.optInt("scrollHeight")} (view ${p.optInt("viewHeight")})."
    }

    private fun consoleNote(before: Int): String {
        val errors = ui.consoleErrorsSince(before)
        return if (errors > 0) " $errors new console error(s): " + ui.consoleText(true, before).lines().drop(1).take(3).joinToString(" | ") else ""
    }

    /** Waits for what a tap or key may have started: a navigation, a re-render. */
    private suspend fun settle() {
        delay(300)
        val until = SystemClock.uptimeMillis() + 10_000
        while (ui.progress() < 100 && SystemClock.uptimeMillis() < until) delay(100)
        delay(150)
    }

    private suspend fun awaitLoad() {
        delay(250)
        val until = SystemClock.uptimeMillis() + 25_000
        while (SystemClock.uptimeMillis() < until) {
            if (ui.loadError() != null) return
            if (ui.progress() >= 100) {
                val ready = runCatching { JSONTokener(js("document.readyState")).nextValue() as? String }.getOrNull()
                if (ready == "complete" || ready == "interactive") break
            }
            delay(150)
        }
        delay(200)
    }

    private suspend fun js(code: String): String = suspendCancellableCoroutine { cont ->
        web.evaluateJavascript(code) { value -> if (cont.isActive) cont.resume(value ?: "null") }
    }

    /** Calls a helper in the page (installing it first) and parses the JSON it returns. */
    private suspend fun call(expression: String): JSONObject? {
        val raw = js("(function(){$HELPER;try{return JSON.stringify(window.__tf.$expression);}catch(e){return JSON.stringify({error:String(e)});}})()")
        val text = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun ok(text: String) = JSONObject().put("text", text)

    private fun err(text: String) = JSONObject().put("error", text)

    /**
     * True when a capture is one colour all over. It is shrunk with filtering first, so even thin
     * text anywhere on a plain page shows up as a slightly different pixel.
     */
    /** A fingerprint of the picture, coarse enough to be cheap and fine enough to notice changed text. */
    private fun pictureHash(bitmap: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(bitmap, 96, 96, true)
        var h = 1125899906842597L
        for (y in 0 until 96) for (x in 0 until 96) h = 31 * h + small.getPixel(x, y)
        small.recycle()
        return h
    }

    private fun isBlank(bitmap: Bitmap): Boolean {
        val small = Bitmap.createScaledBitmap(bitmap, 96, 96, true)
        val pixels = IntArray(96 * 96)
        small.getPixels(pixels, 0, 96, 0, 0, 96, 96)
        if (small !== bitmap) small.recycle()
        val first = pixels[0]
        return pixels.all { it == first }
    }

    /** Keeps the newest few temporary screenshots and nothing older than a day. */
    private fun pruneTemporary(dir: File) {
        val shots = dir.listFiles { f -> f.isFile && f.name.startsWith("browser-") }?.sortedByDescending { it.lastModified() } ?: return
        val dayAgo = System.currentTimeMillis() - 24L * 3600 * 1000
        shots.forEachIndexed { i, f -> if (i >= KEEP_TEMP_SHOTS || f.lastModified() < dayAgo) f.delete() }
    }

    private companion object {
        const val TEMP_SHOTS = "/tmp/termfold-screenshots"
        const val SNAPSHOT_DIR = "/tmp/termfold-browser"
        const val SNAPSHOT_LIMIT = 60
        const val KEEP_TEMP_SHOTS = 100

        /** Installed once per page; finds, describes and numbers what a person could interact with. */
        val HELPER = """
if(!window.__tf)window.__tf=(function(){
var next=1;
function visible(el){var r=el.getBoundingClientRect();if(r.width<1||r.height<1)return false;var s=getComputedStyle(el);return s.visibility!=='hidden'&&s.display!=='none'&&parseFloat(s.opacity||'1')>0.02;}
function clean(t){return (t||'').replace(/\s+/g,' ').trim();}
function name(el){var n=el.getAttribute('aria-label')||'';
if(!n&&el.labels&&el.labels.length)n=el.labels[0].innerText;
if(!n&&el.getAttribute('aria-labelledby')){var l=document.getElementById(el.getAttribute('aria-labelledby'));if(l)n=l.innerText;}
if(!n)n=el.getAttribute('placeholder')||el.getAttribute('alt')||el.getAttribute('title')||'';
var t=el.tagName;
if(!n&&t!=='INPUT'&&t!=='TEXTAREA'&&t!=='SELECT')n=el.innerText||el.textContent;
if(!n&&t==='INPUT'&&/^(submit|button|reset)$/i.test(el.type))n=el.value;
if(!n&&el.querySelector){var im=el.querySelector('img[alt],svg[aria-label],[aria-label]');if(im)n=im.getAttribute('alt')||im.getAttribute('aria-label');}
if(!n&&el.name)n=el.name;
n=clean(n);return n.length>80?n.slice(0,77)+'...':n;}
function role(el){var r=el.getAttribute('role');if(r)return r;var t=el.tagName.toLowerCase();
if(t==='a')return 'link';if(t==='button'||t==='summary')return 'button';if(t==='select')return 'select';if(t==='textarea')return 'textbox';
if(t==='input'){var y=(el.type||'text').toLowerCase();if(y==='checkbox'||y==='radio')return y;if(/^(submit|button|reset|image)$/.test(y))return 'button';if(y==='range')return 'slider';if(y==='file')return 'file';return y==='text'?'textbox':'textbox:'+y;}
if(el.isContentEditable)return 'textbox';return 'clickable';}
var SEL='a[href],button,input:not([type=hidden]),textarea,select,summary,[role=button],[role=link],[role=checkbox],[role=radio],[role=tab],[role=menuitem],[role=switch],[role=option],[role=combobox],[role=textbox],[role=slider],[contenteditable=""],[contenteditable=true],[onclick],[tabindex]:not([tabindex="-1"])';
function ref(el){if(!el.dataset.tfRef){while(document.querySelector('[data-tf-ref="'+next+'"]'))next++;el.dataset.tfRef=String(next++);}return +el.dataset.tfRef;}
function describe(el){return {ref:ref(el),role:role(el),name:name(el)};}
function snapshot(o){o=o||{};var root=document;if(o.scope){root=find(o.scope);if(!root)return {error:'scope not found'};}var out=[];var els=root.querySelectorAll(SEL);
for(var i=0;i<els.length&&out.length<400;i++){var el=els[i];if(!visible(el)||el.closest('[aria-hidden=true]'))continue;
var r=el.getBoundingClientRect();var it=describe(el);
if(el.tagName==='SELECT'){var o=el.options[el.selectedIndex];it.value=o?clean(o.text):'';}
else if(el.tagName==='TEXTAREA'||(el.tagName==='INPUT'&&it.role.indexOf('textbox')===0)){it.value=el.type==='password'?(el.value?'****':''):String(el.value).slice(0,80);}
if(el.type==='checkbox'||el.type==='radio')it.checked=!!el.checked;
if(el.getAttribute('aria-checked'))it.checked=el.getAttribute('aria-checked')==='true';
if(el.disabled||el.getAttribute('aria-disabled')==='true')it.disabled=true;
if(el.tagName==='A')it.href=el.getAttribute('href');
it.visible=r.bottom>0&&r.top<innerHeight;out.push(it);}
var heads=[];document.querySelectorAll('h1,h2,h3').forEach(function(h){if(heads.length<25&&visible(h))heads.push(h.tagName.toLowerCase()+': '+clean(h.innerText).slice(0,100));});
var se=document.scrollingElement||document.documentElement;
return {title:document.title,url:location.href,scrollY:Math.round(se.scrollTop),scrollHeight:se.scrollHeight,viewHeight:innerHeight,headings:heads,items:out};}
function find(a){if(a.ref!=null){var e=document.querySelector('[data-tf-ref="'+a.ref+'"]');if(e)return e;}
if(a.selector){try{var e2=document.querySelector(a.selector);if(e2)return e2;}catch(x){}}
if(a.text){var q=String(a.text).toLowerCase(),els=document.querySelectorAll(SEL),best=null;
for(var i=0;i<els.length;i++){if(!visible(els[i]))continue;var n=name(els[i]).toLowerCase();if(n===q)return els[i];if(!best&&n.indexOf(q)>=0)best=els[i];}
if(best)return best;
var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT),nd;
while((nd=w.nextNode())){if(nd.nodeValue.toLowerCase().indexOf(q)>=0&&nd.parentElement&&visible(nd.parentElement))return nd.parentElement;}}
return null;}
function vv(){return window.visualViewport||{offsetLeft:0,offsetTop:0,width:innerWidth,height:innerHeight};}
function point(a){var el=find(a);if(!el)return {error:'not found'};
el.scrollIntoView({block:'center',inline:'center',behavior:'instant'});
var r=el.getBoundingClientRect(),v=vv(),d=describe(el);
d.x=r.left+r.width/2-v.offsetLeft;d.y=r.top+r.height/2-v.offsetTop;d.vw=v.width;d.vh=v.height;
d.type=el.type||'';d.disabled=!!el.disabled||el.getAttribute('aria-disabled')==='true';return d;}
function viewport(){var v=vv();return {vw:v.width,vh:v.height};}
function fill(a){var el=find(a);if(!el)return {error:'not found'};
el.focus();var f=el.tagName==='INPUT'||el.tagName==='TEXTAREA';
if(f){try{el.select();}catch(x){}}else if(el.isContentEditable){var s=getSelection(),g=document.createRange();g.selectNodeContents(el);s.removeAllRanges();s.addRange(g);}
var ok=false;try{ok=a.text===''?document.execCommand('delete'):document.execCommand('insertText',false,a.text);}catch(x){}
if(!ok||(f&&el.value!==a.text)){if(f){var p=el.tagName==='INPUT'?HTMLInputElement.prototype:HTMLTextAreaElement.prototype;
Object.getOwnPropertyDescriptor(p,'value').set.call(el,a.text);el.dispatchEvent(new Event('input',{bubbles:true}));}
else if(el.isContentEditable){el.textContent=a.text;el.dispatchEvent(new Event('input',{bubbles:true}));}}
el.dispatchEvent(new Event('change',{bubbles:true}));
var d=describe(el);d.password=el.type==='password';d.value=d.password?'':String(f?el.value:clean(el.innerText)).slice(0,120);return d;}
function select(a){var el=find(a);if(!el)return {error:'not found'};if(el.tagName!=='SELECT')return {error:'That element is not a dropdown (select). Click it and pick the option instead.'};
var v=String(a.value).toLowerCase(),hit=null,i;
for(i=0;i<el.options.length;i++){var o=el.options[i];if(o.value.toLowerCase()===v||clean(o.text).toLowerCase()===v){hit=o;break;}}
if(!hit)for(i=0;i<el.options.length;i++){if(clean(el.options[i].text).toLowerCase().indexOf(v)>=0){hit=el.options[i];break;}}
if(!hit)return {error:'No option matches "'+a.value+'".',options:[].map.call(el.options,function(o){return clean(o.text);}).slice(0,50)};
el.value=hit.value;el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));
var d=describe(el);d.value=clean(hit.text);return d;}
function state(a){var el=find(a);if(!el)return {error:'not found'};var d=describe(el);d.checked=(el.type==='checkbox'||el.type==='radio')?!!el.checked:el.getAttribute('aria-checked')==='true';return d;}
function hover(a){var el=find(a);if(!el)return {error:'not found'};['pointerover','pointerenter','mouseover','mouseenter','mousemove'].forEach(function(t){el.dispatchEvent(new MouseEvent(t,{bubbles:t.indexOf('enter')<0}));});return describe(el);}
function pos(){var se=document.scrollingElement||document.documentElement;return {scrollY:Math.round(se.scrollTop),scrollHeight:se.scrollHeight,viewHeight:innerHeight};}
function text(o){o=o||{};var root=o.selector?document.querySelector(o.selector):document.body;if(!root)return {error:'not found'};var t=root.innerText||'';var max=o.max||8000;return {title:document.title,url:location.href,text:t.length>max?t.slice(0,max)+'\n...(cut at '+max+' of '+t.length+' characters; pass a selector or a larger max)':t};}
function outline(){var heads=[];document.querySelectorAll('h1,h2,h3,h4').forEach(function(h){if(heads.length<60&&visible(h)){var l=+h.tagName.charAt(1);heads.push('  '.repeat(l-1)+h.tagName.toLowerCase()+': '+clean(h.innerText).slice(0,90));}});return {title:document.title,url:location.href,headings:heads};}
function box(a){var el=find(a);if(!el)return {error:'not found'};el.scrollIntoView({block:'center',inline:'center',behavior:'instant'});var r=el.getBoundingClientRect();return {x:r.left,y:r.top,w:r.width,h:r.height,vw:vv().width};}
function has(a){if(a.selector){try{var e=document.querySelector(a.selector);return {found:!!e&&visible(e)};}catch(x){return {found:false};}}
if(a.text)return {found:!!document.body&&document.body.innerText.toLowerCase().indexOf(String(a.text).toLowerCase())>=0};
return {found:document.readyState==='complete'};}
return {snapshot:snapshot,point:point,viewport:viewport,fill:fill,select:select,state:state,hover:hover,pos:pos,text:text,has:has,outline:outline,box:box};
})()
""".trimIndent()
    }
}
