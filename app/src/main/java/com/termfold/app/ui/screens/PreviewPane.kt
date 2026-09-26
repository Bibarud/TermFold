package com.termfold.app.ui.screens

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.animation.togetherWith
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termfold.app.R
import com.termfold.app.ui.components.LaunchedWhileVisible
import com.termfold.app.files.FileActions
import com.termfold.app.preview.BrowserBridge
import com.termfold.app.preview.BrowserDriver
import com.termfold.app.preview.DevServers
import com.termfold.app.preview.DriverUi
import com.termfold.app.preview.Preview
import com.termfold.app.preview.PreviewFiles
import com.termfold.app.shell.ShellPaths
import com.termfold.app.ui.components.BareIconButton
import com.termfold.app.ui.theme.Mono
import com.termfold.app.ui.theme.Palette
import com.termfold.app.ui.theme.TermFoldIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** How wide the page is laid out: the pane itself, a phone, or a desktop window. */
private enum class Viewport { FIT, PHONE, DESKTOP }

private data class ConsoleLine(val id: Long, val level: ConsoleMessage.MessageLevel, val text: String, val where: String)

private const val CONSOLE_LIMIT = 500

/** One request the page made, for an agent checking for failed API calls and missing files. */
private class NetEntry(val method: String, val url: String, val document: Boolean) {
    @Volatile var failure: String? = null
    val failed get() = failure != null
    fun line() = "$method ${url.take(200)}" + (failure?.let { "  -> $it" } ?: "") + if (document) "  (page)" else ""
}

private fun markFailed(network: MutableList<NetEntry>, url: String, why: String) {
    synchronized(network) {
        val entry = network.lastOrNull { it.url == url && it.failure == null }
        if (entry != null) entry.failure = why else network.add(NetEntry("GET", url, false).also { it.failure = why })
    }
}
private const val PHONE_WIDTH = 390
private const val DESKTOP_WIDTH = 1280

/**
 * The in-app browser: dev servers on localhost, the project's own pages (served from the Linux
 * environment, reloading as files change), or any site. It carries what building a web page
 * with an agent needs: phone and desktop widths, the page's console with a copy button, and a
 * screenshot straight to the clipboard or into the project for the agent to look at.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PreviewPane(
    state: Preview.State,
    wide: Boolean,
    maximized: Boolean,
    onToggleMaximize: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onMinimize: () -> Unit = {},
    parked: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    var webView by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(state.url) }
    var title by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(100) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var httpError by remember { mutableStateOf<String?>(null) }
    var viewportName by rememberSaveable { mutableStateOf(Viewport.FIT.name) }
    val viewport = Viewport.valueOf(viewportName)
    var liveReload by rememberSaveable { mutableStateOf(true) }
    var consoleOpen by remember { mutableStateOf(false) }
    val console = remember { mutableStateListOf<ConsoleLine>() }
    var consoleCounter by remember { mutableIntStateOf(0) }
    var servers by remember { mutableStateOf<List<Int>>(emptyList()) }
    var menuOpen by remember { mutableStateOf(false) }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val showStart = currentUrl == null

    // What an agent driving the browser needs, and what the user sees while it does.
    val network = remember { java.util.Collections.synchronizedList(ArrayList<NetEntry>()) }
    var pendingUpload by remember { mutableStateOf<File?>(null) }
    var uploadTaken by remember { mutableStateOf(false) }
    val agent by BrowserBridge.activity.collectAsState()
    val cursor = remember { androidx.compose.animation.core.Animatable(androidx.compose.ui.geometry.Offset.Unspecified, androidx.compose.ui.geometry.Offset.VectorConverter) }
    val ripple = remember { androidx.compose.animation.core.Animatable(1f) }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        fileCallback?.onReceiveValue(uris.toTypedArray())
        fileCallback = null
    }

    // Running dev servers, for the start page and the error page (the only places they show).
    LaunchedWhileVisible(showStart, loadError != null, parked) {
        if (parked || (!showStart && loadError == null)) return@LaunchedWhileVisible
        while (true) {
            servers = DevServers.scan()
            delay(4000)
        }
    }

    fun go(url: String) {
        if (url.isBlank()) return
        loadError = null
        currentUrl = url
        webView?.loadUrl(url)
    }

    // A new request from outside (a header button, a file's Preview, a link) loads its page.
    LaunchedEffect(state.nonce, webView) {
        val view = webView ?: return@LaunchedEffect
        val url = state.url
        currentUrl = url
        loadError = null
        if (url != null && view.url != url) view.loadUrl(url)
    }

    // A preview of the project's files reloads when anything in its folder changes. Dev servers
    // reload themselves.
    LaunchedWhileVisible(currentUrl, liveReload) {
        val guest = currentUrl?.let(Preview::guestPathOf) ?: return@LaunchedWhileVisible
        if (!liveReload) return@LaunchedWhileVisible
        val rootfs = ShellPaths.rootfsDir(context)
        val target = File(rootfs, guest.trimStart('/'))
        val dir = if (target.isDirectory) target else target.parentFile ?: return@LaunchedWhileVisible
        var last = withContext(Dispatchers.IO) { PreviewFiles.newest(dir) }
        while (true) {
            delay(1000)
            val now = withContext(Dispatchers.IO) { PreviewFiles.newest(dir) }
            if (now != last) {
                last = now
                webView?.reload()
            }
        }
    }

    fun applyViewport(view: WebView) {
        val viewport = Viewport.valueOf(viewportName)
        val settings = view.settings
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        val base = WebSettings.getDefaultUserAgent(context)
        settings.userAgentString = if (viewport == Viewport.DESKTOP) {
            base.replace(Regex("\\(Linux; Android [^)]*\\)"), "(X11; Linux x86_64)").replace(" Mobile", "").replace("; wv", "")
        } else {
            base
        }
    }

    fun desktopScript(view: WebView) {
        if (Viewport.valueOf(viewportName) != Viewport.DESKTOP) return
        view.evaluateJavascript(
            "(function(){var m=document.querySelector('meta[name=viewport]');" +
                "if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);}" +
                "m.content='width=$DESKTOP_WIDTH';})();",
            null,
        )
    }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    fun screenshot(saveToProject: Boolean) {
        val view = webView ?: return
        if (view.width == 0 || view.height == 0) return
        val bitmap = runCatching {
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }.getOrNull() ?: return
        scope.launch {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (saveToProject) {
                        val rootfs = ShellPaths.rootfsDir(context)
                        val projectGuest = state.projectDir ?: "/root"
                        val dir = File(File(rootfs, projectGuest.trimStart('/')), "screenshots").apply { mkdirs() }
                        val file = File(dir, "preview-$stamp.png")
                        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        "screenshots/${file.name}"
                    } else {
                        val dir = File(context.cacheDir, "share").apply { mkdirs() }
                        val file = File(dir, "preview-$stamp.png")
                        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        FileActions.copyImage(context, file)
                        ""
                    }
                }
            }
            bitmap.recycle()
            result.onSuccess { relative ->
                if (saveToProject) {
                    copyText(context, relative)
                    toast(context.getString(R.string.preview_screenshot_saved, relative))
                } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    toast(context.getString(R.string.preview_screenshot_copied))
                }
            }.onFailure { toast(it.message ?: "Screenshot failed") }
        }
    }

    fun openOutside(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // This pane's driver. Panes are recreated (maximize, restore, rotation) and the old one can
    // finish going away after the new one registered; it must not unregister the new one.
    var driver by remember { mutableStateOf<BrowserDriver?>(null) }
    LaunchedEffect(webView) {
        val view = webView ?: return@LaunchedEffect
        val density = context.resources.displayMetrics.density
        driver = BrowserDriver(
            context,
            view,
            object : DriverUi {
                override fun projectDir() = state.projectDir
                override fun navigate(url: String) = go(url)
                override fun progress() = progress
                override fun loadError() = loadError
                override fun httpError() = httpError
                override fun setViewport(mode: String): Boolean {
                    val target = Viewport.entries.firstOrNull { it.name.equals(mode, true) } ?: return false
                    viewportName = target.name
                    applyViewport(view)
                    view.reload()
                    return true
                }
                override fun viewportName() = viewportName.lowercase()
                override suspend fun moveCursor(x: Float, y: Float, label: String) {
                    BrowserBridge.setLabel(label)
                    val to = androidx.compose.ui.geometry.Offset(x, y)
                    // Animations need the screen's frame clock, which only the composition's
                    // scope has; the driver waits for the glide to finish before it taps.
                    scope.launch {
                        if (cursor.value == androidx.compose.ui.geometry.Offset.Unspecified) {
                            cursor.snapTo(androidx.compose.ui.geometry.Offset(view.width * 0.5f, view.height * 0.8f))
                        }
                        val distance = (cursor.value - to).getDistance() / density
                        cursor.animateTo(to, androidx.compose.animation.core.tween((220 + distance * 0.9f).toInt().coerceAtMost(650), easing = androidx.compose.animation.core.FastOutSlowInEasing))
                    }.join()
                }
                override fun pulse() {
                    scope.launch {
                        ripple.snapTo(0f)
                        ripple.animateTo(1f, androidx.compose.animation.core.tween(520))
                    }
                }
                override fun consoleSize() = consoleCounter
                override fun consoleText(errorsOnly: Boolean, since: Int): String {
                    val lines = console.filter { it.id >= since && (!errorsOnly || it.level == ConsoleMessage.MessageLevel.ERROR) }
                    if (lines.isEmpty()) return if (errorsOnly) "No console errors." else "The console is empty."
                    return "Console (${lines.size}):\n" + lines.joinToString("\n") { line ->
                        "[" + line.level.name.lowercase() + "] " + line.text + if (line.where.isNotEmpty()) "  (" + line.where + ")" else ""
                    }
                }
                override fun consoleErrorsSince(since: Int) = console.count { it.id >= since && it.level == ConsoleMessage.MessageLevel.ERROR }
                override fun networkText(failedOnly: Boolean): String {
                    val list = synchronized(network) { network.toList() }.filter { !failedOnly || it.failed }
                    if (list.isEmpty()) return if (failedOnly) "No failed requests." else "No requests recorded yet."
                    return "Requests (${list.size}, newest last):\n" + list.takeLast(120).joinToString("\n") { it.line() }
                }
                override fun offerUpload(file: File) {
                    uploadTaken = false
                    pendingUpload = file
                }
                override fun uploadTaken() = uploadTaken
            },
        )
        BrowserBridge.controller = driver
    }
    // Minimized with no agent at work, or the app in the background: pause the page, so its
    // animations and video stop drawing. An agent's next command wakes it (BrowserDriver).
    val lifecycleState by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val visible = lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    LaunchedEffect(webView, parked, agent.active, visible) {
        val view = webView ?: return@LaunchedEffect
        if (!agent.active && (parked || !visible)) view.onPause() else view.onResume()
    }

    DisposableEffect(Unit) {
        onDispose { if (BrowserBridge.controller === driver) BrowserBridge.controller = null }
    }

    // Back steps through the page's history first when the preview has the screen to itself.
    BackHandler(enabled = !parked && canBack && (!wide || maximized)) { webView?.goBack() }

    Column(modifier.background(Palette.Bg)) {
        // ---- Toolbar
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolIcon(TermFoldIcons.Back, stringResource(R.string.cd_back), enabled = canBack) { webView?.goBack() }
            if (wide) ToolIcon(TermFoldIcons.Forward, stringResource(R.string.preview_forward), enabled = canForward) { webView?.goForward() }
            ToolIcon(
                if (progress < 100 && !showStart) TermFoldIcons.Close else TermFoldIcons.Refresh,
                stringResource(if (progress < 100) R.string.acp_stop else R.string.preview_reload),
                enabled = !showStart,
            ) { if (progress < 100) webView?.stopLoading() else { loadError = null; webView?.reload() } }
            AddressBar(
                url = currentUrl,
                local = currentUrl?.let(Preview::isLocal) == true,
                onGo = { typed ->
                    focus.clearFocus()
                    go(Preview.normalize(typed, state.projectDir))
                },
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            if (wide) ViewportButton(viewport) { viewportName = it.name; webView?.let { v -> applyViewport(v); v.reload() } }
            Box {
                ToolIcon(TermFoldIcons.Console, stringResource(R.string.preview_console), active = consoleOpen) { consoleOpen = !consoleOpen }
                val errors = console.count { it.level == ConsoleMessage.MessageLevel.ERROR }
                if (errors > 0) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 4.dp).size(16.dp).clip(CircleShape).background(Palette.Pink),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (errors > 99) "99" else errors.toString(), fontSize = 9.sp, color = Palette.OnAccent, maxLines = 1)
                    }
                }
            }
            Box {
                ToolIcon(TermFoldIcons.More, stringResource(R.string.cd_more)) { menuOpen = true }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = Palette.Card,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    fun act(block: () -> Unit) { menuOpen = false; block() }
                    if (!wide) {
                        MenuEntry(TermFoldIcons.Forward, stringResource(R.string.preview_forward)) { act { webView?.goForward() } }
                        Viewport.entries.forEach { mode ->
                            CheckItem(viewportIcon(mode), viewportLabel(mode), viewport == mode) {
                                act { viewportName = mode.name; webView?.let { v -> applyViewport(v); v.reload() } }
                            }
                        }
                        HorizontalDivider(color = Palette.Border, modifier = Modifier.padding(vertical = 4.dp))
                    }
                    MenuEntry(TermFoldIcons.Camera, stringResource(R.string.preview_screenshot_copy)) { act { screenshot(saveToProject = false) } }
                    MenuEntry(TermFoldIcons.SaveToDevice, stringResource(R.string.preview_screenshot_save)) { act { screenshot(saveToProject = true) } }
                    HorizontalDivider(color = Palette.Border, modifier = Modifier.padding(vertical = 4.dp))
                    if (currentUrl?.let(Preview::guestPathOf) != null) {
                        CheckItem(TermFoldIcons.Refresh, stringResource(R.string.preview_live_reload), liveReload) { act { liveReload = !liveReload } }
                    }
                    MenuEntry(TermFoldIcons.Refresh, stringResource(R.string.preview_hard_reload)) {
                        act { webView?.clearCache(true); loadError = null; webView?.reload() }
                    }
                    currentUrl?.takeIf { Preview.guestPathOf(it) == null }?.let { url ->
                        MenuEntry(TermFoldIcons.OpenExternal, stringResource(R.string.preview_open_outside)) { act { openOutside(url) } }
                    }
                    currentUrl?.let { url ->
                        MenuEntry(TermFoldIcons.Copy, stringResource(R.string.preview_copy_link)) {
                            act { copyText(context, Preview.guestPathOf(url) ?: url) }
                        }
                    }
                    MenuEntry(TermFoldIcons.Home, stringResource(R.string.preview_start_page)) {
                        act { currentUrl = null; loadError = null; webView?.loadUrl("about:blank") }
                    }
                }
            }
            if (onToggleMaximize != null) {
                ToolIcon(
                    if (maximized) TermFoldIcons.Minimize else TermFoldIcons.Maximize,
                    stringResource(if (maximized) R.string.preview_side_by_side else R.string.preview_full_screen),
                    onClick = onToggleMaximize,
                )
            }
            ToolIcon(TermFoldIcons.WindowMinimize, stringResource(R.string.preview_minimize), onClick = onMinimize)
            ToolIcon(TermFoldIcons.Close, stringResource(R.string.action_close), onClick = onClose)
        }
        // Loading progress, a hairline under the toolbar.
        Box(Modifier.fillMaxWidth().height(2.dp).background(Palette.BorderSoft)) {
            val shown by animateFloatAsState(if (progress >= 100 || showStart) 0f else progress / 100f, label = "progress")
            if (shown > 0f) Box(Modifier.fillMaxWidth(shown).fillMaxHeight().background(Palette.Accent))
        }

        // ---- Page
        // Clipped: the WebView is a native view and must never draw over the toolbar.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(if (viewport == Viewport.PHONE) Palette.Card else Palette.Bg)) {
            val phoneFrame = viewport == Viewport.PHONE && maxWidth > (PHONE_WIDTH + 40).dp
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .then(if (phoneFrame) Modifier.padding(vertical = 12.dp).width(PHONE_WIDTH.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, Palette.Border, RoundedCornerShape(12.dp)) else Modifier.fillMaxWidth())
                    .fillMaxHeight()
                    .clipToBounds(),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    // No page yet: nothing to show, and no white flash behind the start page.
                    update = { view -> view.visibility = if (showStart) android.view.View.INVISIBLE else android.view.View.VISIBLE },
                    factory = { ctx ->
                        WebView(ctx).apply {
                            setBackgroundColor(android.graphics.Color.parseColor("#08080A"))
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.setSupportMultipleWindows(false)
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            // Pages reach files only through the project server, never file://.
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            applyViewport(this)
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                    val url = request.url
                                    synchronized(network) {
                                        network.add(NetEntry(request.method, url.toString(), request.isForMainFrame))
                                        if (network.size > 400) network.removeAt(0)
                                    }
                                    if (url.host != Preview.FILES_HOST) return null
                                    val guest = Preview.guestPathOf(url.toString()) ?: return null
                                    return PreviewFiles.respond(ctx, guest)
                                }

                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val scheme = request.url.scheme?.lowercase()
                                    if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "data") return false
                                    // mailto:, tel:, app links: hand them to the system.
                                    runCatching {
                                        val intent = if (scheme == "intent") {
                                            Intent.parseUri(request.url.toString(), Intent.URI_INTENT_SCHEME).apply {
                                                addCategory(Intent.CATEGORY_BROWSABLE)
                                                component = null
                                                selector = null
                                            }
                                        } else {
                                            Intent(Intent.ACTION_VIEW, request.url)
                                        }
                                        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    }
                                    return true
                                }

                                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                    if (url == "about:blank") return
                                    loadError = null
                                    httpError = null
                                    console.clear()
                                }

                                override fun onPageCommitVisible(view: WebView, url: String) = desktopScript(view)

                                override fun onPageFinished(view: WebView, url: String) {
                                    progress = 100
                                    desktopScript(view)
                                }

                                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                                    canBack = view.canGoBack()
                                    canForward = view.canGoForward()
                                    if (url == "about:blank") return
                                    currentUrl = url
                                    Preview.visited(url)
                                }

                                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                    if (request.isForMainFrame) loadError = error.description?.toString() ?: "Error ${error.errorCode}"
                                    markFailed(network, request.url.toString(), error.description?.toString() ?: "failed")
                                }

                                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                    if (request.isForMainFrame) httpError = "HTTP ${response.statusCode}"
                                    markFailed(network, request.url.toString(), "HTTP ${response.statusCode}")
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    progress = newProgress
                                }

                                override fun onReceivedTitle(view: WebView, t: String?) {
                                    title = t.orEmpty()
                                }

                                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                                    val source = message.sourceId().substringAfterLast('/').substringBefore('?')
                                    console.add(ConsoleLine(consoleCounter++.toLong(), message.messageLevel(), message.message(), if (source.isBlank()) "" else "$source:${message.lineNumber()}"))
                                    if (console.size > CONSOLE_LIMIT) console.removeAt(0)
                                    return true
                                }

                                // While an agent drives, page dialogs are answered for it (and noted in
                                // the console) instead of waiting for a tap nobody will make.
                                override fun onJsAlert(view: WebView, url: String, message: String, result: android.webkit.JsResult): Boolean {
                                    if (!BrowserBridge.activity.value.active) return false
                                    console.add(ConsoleLine(consoleCounter++.toLong(), ConsoleMessage.MessageLevel.WARNING, "alert(\"$message\") was dismissed", ""))
                                    result.confirm()
                                    return true
                                }

                                override fun onJsConfirm(view: WebView, url: String, message: String, result: android.webkit.JsResult): Boolean {
                                    if (!BrowserBridge.activity.value.active) return false
                                    console.add(ConsoleLine(consoleCounter++.toLong(), ConsoleMessage.MessageLevel.WARNING, "confirm(\"$message\") was answered OK", ""))
                                    result.confirm()
                                    return true
                                }

                                override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String?, result: android.webkit.JsPromptResult): Boolean {
                                    if (!BrowserBridge.activity.value.active) return false
                                    console.add(ConsoleLine(consoleCounter++.toLong(), ConsoleMessage.MessageLevel.WARNING, "prompt(\"$message\") was answered with its default", ""))
                                    result.confirm(defaultValue.orEmpty())
                                    return true
                                }

                                override fun onShowFileChooser(
                                    view: WebView,
                                    callback: ValueCallback<Array<Uri>>,
                                    params: FileChooserParams,
                                ): Boolean {
                                    // A file an agent chose goes straight in; no picker.
                                    pendingUpload?.let { file ->
                                        pendingUpload = null
                                        uploadTaken = true
                                        callback.onReceiveValue(arrayOf(FileActions.uriFor(ctx, file)))
                                        return true
                                    }
                                    fileCallback?.onReceiveValue(null)
                                    fileCallback = callback
                                    val types = params.acceptTypes.filter { it.isNotBlank() }.ifEmpty { listOf("*/*") }
                                    runCatching { pickFiles.launch(types.toTypedArray()) }.onFailure {
                                        fileCallback = null
                                        callback.onReceiveValue(null)
                                    }
                                    return true
                                }
                            }
                            setDownloadListener { url, _, _, _, _ -> openOutside(url) }
                            webView = this
                        }
                    },
                )
                AgentPointer(cursor.value, ripple.value, agent.label, visible = agent.active)
            }

            if (showStart) {
                StartPage(state.projectDir, servers, onOpen = ::go)
            }
            loadError?.let { error ->
                ErrorPage(
                    url = currentUrl.orEmpty(),
                    error = error,
                    servers = servers,
                    onRetry = { loadError = null; webView?.reload() },
                    onOpen = ::go,
                )
            }

            AgentFrame(active = agent.active, paused = agent.paused, label = agent.label)

            // ---- Console, over the bottom of the page
            androidx.compose.animation.AnimatedVisibility(
                visible = consoleOpen,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                ConsolePanel(
                    lines = console,
                    height = maxHeight * 0.42f,
                    onCopy = {
                        val text = buildString {
                            append("Browser console for ").append(currentUrl?.let(Preview::display).orEmpty()).append(":\n")
                            console.forEach { line ->
                                append('[').append(line.level.name.lowercase()).append("] ").append(line.text)
                                if (line.where.isNotEmpty()) append("  (").append(line.where).append(')')
                                append('\n')
                            }
                        }
                        copyText(context, text)
                    },
                    onClear = { console.clear() },
                    onClose = { consoleOpen = false },
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            fileCallback?.onReceiveValue(null)
            webView?.apply {
                stopLoading()
                destroy()
            }
            webView = null
        }
    }
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("TermFold", text))
}

@Composable
private fun AddressBar(url: String?, local: Boolean, onGo: (String) -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf(TextFieldValue("")) }
    val shown = url?.let(Preview::display).orEmpty()
    LaunchedEffect(shown, focused) {
        // Editing starts from the whole address, selected, like any browser.
        if (!focused) value = TextFieldValue(shown) else value = TextFieldValue(shown, TextRange(0, shown.length))
    }
    Row(
        modifier
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.Field)
            .padding(start = 10.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (local) TermFoldIcons.Terminal else TermFoldIcons.Globe,
            null,
            tint = if (local) Palette.Green else Palette.TextFaint,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.text.isEmpty()) {
                Text(stringResource(R.string.preview_address_hint), style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = Palette.Text, fontSize = 13.5.sp),
                cursorBrush = SolidColor(Palette.Accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onGo(value.text) }),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            )
        }
    }
}

@Composable
private fun ToolIcon(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = when {
                !enabled -> Palette.Border
                active -> Palette.Accent
                else -> Palette.TextDim
            },
            modifier = Modifier.size(19.dp),
        )
    }
}

private fun viewportIcon(mode: Viewport) = when (mode) {
    Viewport.FIT -> TermFoldIcons.DeviceFit
    Viewport.PHONE -> TermFoldIcons.DevicePhone
    Viewport.DESKTOP -> TermFoldIcons.DeviceDesktop
}

@Composable
private fun viewportLabel(mode: Viewport) = stringResource(
    when (mode) {
        Viewport.FIT -> R.string.preview_fit
        Viewport.PHONE -> R.string.preview_phone
        Viewport.DESKTOP -> R.string.preview_desktop
    },
)

@Composable
private fun ViewportButton(current: Viewport, onPick: (Viewport) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolIcon(viewportIcon(current), viewportLabel(current), active = current != Viewport.FIT) { open = true }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = Palette.Card,
            shape = RoundedCornerShape(14.dp),
        ) {
            Viewport.entries.forEach { mode ->
                CheckItem(viewportIcon(mode), viewportLabel(mode), current == mode) { open = false; onPick(mode) }
            }
        }
    }
}

@Composable
private fun CheckItem(icon: ImageVector, label: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = if (checked) Palette.Text else Palette.TextDim) },
        leadingIcon = { Icon(icon, null, tint = if (checked) Palette.Text else Palette.TextFaint, modifier = Modifier.size(18.dp)) },
        trailingIcon = { if (checked) Icon(TermFoldIcons.Check, null, tint = Palette.Accent, modifier = Modifier.size(16.dp)) },
        onClick = onClick,
    )
}

/** What to open: running dev servers and the project's own pages. */
@Composable
private fun StartPage(projectDir: String?, servers: List<Int>, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    var pages by remember(projectDir) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(projectDir) {
        val dir = projectDir ?: return@LaunchedEffect
        pages = withContext(Dispatchers.IO) { htmlPages(File(ShellPaths.rootfsDir(context), dir.trimStart('/'))) }
    }
    Box(Modifier.fillMaxSize().background(Palette.Bg), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 28.dp),
        ) {
            Text(stringResource(R.string.preview_start_title), style = MaterialTheme.typography.titleMedium, color = Palette.Text)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.preview_start_body), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)

            Spacer(Modifier.height(24.dp))
            SectionLabel(stringResource(R.string.preview_running))
            if (servers.isEmpty()) {
                Text(stringResource(R.string.preview_none_running), style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint)
            }
            servers.forEach { port ->
                StartRow(TermFoldIcons.Terminal, "localhost:$port", live = true) { onOpen("http://localhost:$port") }
            }

            if (projectDir != null) {
                Spacer(Modifier.height(24.dp))
                SectionLabel(stringResource(R.string.preview_pages, projectDir.substringAfterLast('/')))
                if (pages.isEmpty()) {
                    Text(stringResource(R.string.preview_no_pages), style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint)
                }
                pages.forEach { relative ->
                    StartRow(TermFoldIcons.FileText, relative, live = false) {
                        onOpen(Preview.fileUrl(projectDir.trimEnd('/') + "/" + relative))
                    }
                }
            }
        }
    }
}

/** HTML pages in a project, nearest first, skipping dependencies and hidden folders. */
private fun htmlPages(root: File, limit: Int = 12): List<String> {
    val out = ArrayList<String>()
    val queue = ArrayDeque<Pair<File, Int>>()
    queue.add(root to 0)
    while (queue.isNotEmpty() && out.size < limit) {
        val (dir, depth) = queue.removeFirst()
        val kids = dir.listFiles()?.sortedBy { it.name.lowercase() } ?: continue
        kids.filter { it.isFile && (it.name.endsWith(".html") || it.name.endsWith(".htm")) }
            .forEach { if (out.size < limit) out += it.relativeTo(root).path.replace('\\', '/') }
        if (depth < 4) {
            kids.filter { it.isDirectory && !it.name.startsWith(".") && it.name !in setOf("node_modules", "venv", "__pycache__", "target") }
                .forEach { queue.add(it to depth + 1) }
        }
    }
    return out
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Palette.TextFaint,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun StartRow(icon: ImageVector, label: String, live: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Card)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Palette.TextDim, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono, fontSize = 13.sp),
            color = Palette.Text,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f),
        )
        if (live) Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.Green))
    }
}

/** A page that did not load: most often a dev server that is not running yet. */
@Composable
private fun ErrorPage(url: String, error: String, servers: List<Int>, onRetry: () -> Unit, onOpen: (String) -> Unit) {
    val port = Regex("^https?://(localhost|127\\.0\\.0\\.1|\\[::1])(?::(\\d+))?").find(url)?.groupValues?.getOrNull(2)?.toIntOrNull()
    Box(Modifier.fillMaxSize().background(Palette.Bg), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 32.dp),
        ) {
            Text(stringResource(R.string.preview_cant_open, Preview.display(url)), style = MaterialTheme.typography.titleMedium, color = Palette.Text)
            Spacer(Modifier.height(6.dp))
            Text(
                if (port != null) stringResource(R.string.preview_nothing_on_port, port) else error,
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextDim,
            )
            if (port != null) {
                Spacer(Modifier.height(4.dp))
                Text(error, style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono, fontSize = 11.sp), color = Palette.TextFaint)
            }
            Spacer(Modifier.height(18.dp))
            Text(
                stringResource(R.string.action_retry),
                style = MaterialTheme.typography.labelLarge,
                color = Palette.OnAccent,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.Accent)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            )
            if (servers.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                SectionLabel(stringResource(R.string.preview_running))
                servers.forEach { p -> StartRow(TermFoldIcons.Terminal, "localhost:$p", live = true) { onOpen("http://localhost:$p") } }
            }
        }
    }
}

@Composable
private fun ConsolePanel(
    lines: List<ConsoleLine>,
    height: androidx.compose.ui.unit.Dp,
    onCopy: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex) }
    Column(
        Modifier
            .fillMaxWidth()
            .height(height)
            .background(Palette.Card)
            .border(1.dp, Palette.Border),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.preview_console), style = MaterialTheme.typography.labelLarge, color = Palette.Text)
            Spacer(Modifier.width(10.dp))
            val errors = lines.count { it.level == ConsoleMessage.MessageLevel.ERROR }
            val warnings = lines.count { it.level == ConsoleMessage.MessageLevel.WARNING }
            if (errors > 0) CountChip(errors, Palette.Pink)
            if (warnings > 0) CountChip(warnings, Palette.Yellow)
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.action_copy),
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                color = if (lines.isEmpty()) Palette.TextFaint else Palette.Accent,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = lines.isNotEmpty(), onClick = onCopy).padding(horizontal = 10.dp, vertical = 8.dp),
            )
            Text(
                stringResource(R.string.preview_clear),
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                color = Palette.TextDim,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClear).padding(horizontal = 10.dp, vertical = 8.dp),
            )
            BareIconButton(icon = TermFoldIcons.Close, contentDescription = stringResource(R.string.action_close), onClick = onClose, tint = Palette.TextFaint, size = 34)
        }
        HorizontalDivider(color = Palette.Border)
        if (lines.isEmpty()) {
            Text(
                stringResource(R.string.preview_console_empty),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextFaint,
                modifier = Modifier.padding(14.dp),
            )
        } else {
            SelectionContainer {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(lines, key = { it.id }) { line ->
                        val color = when (line.level) {
                            ConsoleMessage.MessageLevel.ERROR -> Palette.Pink
                            ConsoleMessage.MessageLevel.WARNING -> Palette.Yellow
                            ConsoleMessage.MessageLevel.DEBUG, ConsoleMessage.MessageLevel.TIP -> Palette.TextFaint
                            else -> Palette.TermOut
                        }
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(if (line.level == ConsoleMessage.MessageLevel.ERROR) Palette.Pink.copy(alpha = 0.06f) else Color.Transparent)
                                .padding(horizontal = 14.dp, vertical = 5.dp),
                        ) {
                            Text(line.text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono, fontSize = 11.5.sp, lineHeight = 15.sp), color = color)
                            if (line.where.isNotEmpty()) {
                                Text(line.where, style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono, fontSize = 10.sp), color = Palette.TextFaint)
                            }
                        }
                        HorizontalDivider(color = Palette.BorderSoft)
                    }
                }
            }
        }
    }
}

@Composable
private fun CountChip(count: Int, color: Color) {
    Text(
        count.toString(),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .padding(end = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * The header button that opens the preview for a project; a green dot says a dev server is
 * running, so a server an agent just started is one tap away.
 */
@Composable
fun PreviewButton(projectDir: String?) {
    val state by Preview.state.collectAsState()
    var running by remember { mutableStateOf(false) }
    LaunchedWhileVisible(Unit) {
        while (true) {
            running = DevServers.scan().isNotEmpty()
            delay(6000)
        }
    }
    val open = state.open && !state.minimized
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (open) Palette.AccentSoft else Color.Transparent)
            .clickable {
                when {
                    open -> Preview.close()
                    state.open -> Preview.restore()
                    else -> Preview.open(projectDir = projectDir)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            TermFoldIcons.Globe,
            contentDescription = stringResource(R.string.preview_title),
            tint = if (open) Palette.Accent else Palette.TextDim,
            modifier = Modifier.size(20.dp),
        )
        androidx.compose.animation.AnimatedVisibility(
            visible = running && !open,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp),
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(Palette.Green))
        }
    }
}


/**
 * The agent's pointer: an arrow that glides to each target, a ring that spreads on a click, and
 * a label saying what it is doing. Drawn over the page, so screenshots never include it.
 */
@Composable
private fun AgentPointer(at: androidx.compose.ui.geometry.Offset, ripple: Float, label: String, visible: Boolean) {
    val alpha by animateFloatAsState(if (visible && at != androidx.compose.ui.geometry.Offset.Unspecified) 1f else 0f, androidx.compose.animation.core.tween(300), label = "pointer")
    if (alpha == 0f || at == androidx.compose.ui.geometry.Offset.Unspecified) return
    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val u = density
            if (ripple < 1f) {
                drawCircle(
                    color = Palette.Accent.copy(alpha = (1f - ripple) * 0.75f),
                    radius = (7f + 24f * ripple) * u,
                    center = at,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.2f * u),
                )
                drawCircle(Palette.Accent.copy(alpha = (1f - ripple) * 0.22f), radius = (7f + 18f * ripple) * u, center = at)
            }
            val arrow = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, 0f)
                lineTo(0f, 18.5f * u)
                lineTo(4.8f * u, 14.2f * u)
                lineTo(8f * u, 21.2f * u)
                lineTo(11.2f * u, 19.8f * u)
                lineTo(8.1f * u, 13.1f * u)
                lineTo(14.3f * u, 13.1f * u)
                close()
            }
            translate(at.x + 1.6f * u, at.y + 2.4f * u) { drawPath(arrow, Color.Black.copy(alpha = 0.32f)) }
            translate(at.x, at.y) {
                drawPath(arrow, Palette.Accent)
                drawPath(arrow, Color.White, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6f * u, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
        }
        if (label.isNotBlank()) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.sp),
                color = Palette.OnAccent,
                maxLines = 1,
                modifier = Modifier
                    .offset { androidx.compose.ui.unit.IntOffset((at.x + 18.dp.toPx()).toInt(), (at.y + 20.dp.toPx()).toInt()) }
                    .clip(RoundedCornerShape(8.dp))
                    .background(Palette.Accent)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** While an agent drives: a glowing orange frame and a bar saying so, with Stop. */
@Composable
private fun AgentFrame(active: Boolean, paused: Boolean, label: String) {
    val shown by animateFloatAsState(if (active) 1f else 0f, androidx.compose.animation.core.tween(350), label = "frameShown")
    if (shown > 0f) {
        // Created only while shown: an infinite animation ticks every frame (144 a second on some
        // screens) for as long as it exists, even when nothing reads it.
        val glow by rememberGlow()
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val u = density
                    for (i in 0 until 6) {
                        val inset = i * 3.2f * u
                        drawRect(
                            color = Palette.Accent.copy(alpha = shown * glow * (0.34f - i * 0.055f).coerceAtLeast(0f)),
                            topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                            size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.2f * u),
                        )
                    }
                    drawRect(Palette.Accent.copy(alpha = shown), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.4f * u))
                },
        )
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = active || paused,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (paused) Palette.Card else Palette.Accent)
                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!paused) {
                val glow by rememberGlow()
                Icon(
                    TermFoldIcons.Sparkle,
                    contentDescription = null,
                    tint = Palette.OnAccent,
                    modifier = Modifier.size(16.dp).graphicsLayer {
                        this.alpha = 0.55f + 0.45f * glow
                        scaleX = 0.88f + 0.12f * glow
                        scaleY = 0.88f + 0.12f * glow
                    },
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                if (paused) stringResource(R.string.agent_browser_paused) else stringResource(R.string.agent_browser_working) + if (label.isNotBlank()) " · $label" else "",
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                color = if (paused) Palette.Text else Palette.OnAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(if (paused) R.string.agent_browser_resume else R.string.acp_stop),
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                color = if (paused) Palette.Accent else Palette.OnAccent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (paused) Color.Transparent else Palette.OnAccent.copy(alpha = 0.12f))
                    .clickable { if (paused) BrowserBridge.resume() else BrowserBridge.pause() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}


/**
 * The minimized browser: a small pill that can be dragged anywhere. While an agent works it
 * spins and says what it is doing; otherwise it names the page. Tap to bring the browser back.
 * Always the same width, so it never grows over the work.
 */
@Composable
fun PreviewPill(modifier: Modifier = Modifier) {
    val state by Preview.state.collectAsState()
    val agent by BrowserBridge.activity.collectAsState()
    val working = agent.active
    val text = when {
        working && agent.label.isNotBlank() -> agent.label
        working -> stringResource(R.string.agent_browser_working)
        else -> state.url?.let(::pillName) ?: stringResource(R.string.preview_title)
    }
    val border by androidx.compose.animation.animateColorAsState(if (working) Palette.Accent else Palette.Border, label = "pillBorder")
    Row(
        modifier
            .width(236.dp)
            .height(46.dp)
            .shadow(14.dp, RoundedCornerShape(23.dp))
            .clip(RoundedCornerShape(23.dp))
            .background(Palette.Card)
            .border(1.dp, border, RoundedCornerShape(23.dp))
            .clickable { Preview.restore() }
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (working) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Palette.Accent)
            } else {
                Icon(TermFoldIcons.Globe, null, tint = Palette.TextDim, modifier = Modifier.size(17.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        androidx.compose.animation.AnimatedContent(
            targetState = text,
            transitionSpec = { (fadeIn(androidx.compose.animation.core.tween(180)) + slideInVertically { it / 3 }) togetherWith fadeOut(androidx.compose.animation.core.tween(120)) },
            label = "pillText",
            modifier = Modifier.weight(1f),
        ) { t ->
            Text(
                t,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                color = Palette.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        BareIconButton(
            icon = TermFoldIcons.Close,
            contentDescription = stringResource(R.string.action_close),
            onClick = { Preview.close() },
            tint = Palette.TextFaint,
            size = 38,
        )
    }
}

/** Short name for the pill: the file for a project page, host and port for anything else. */
private fun pillName(url: String): String {
    Preview.guestPathOf(url)?.let { return it.trimEnd('/').substringAfterLast('/').ifEmpty { "/" } }
    return url.substringAfter("://").substringBefore('/').removePrefix("www.")
}


/** The slow pulse of the agent frame and sparkle; exists only while they are on screen. */
@Composable
private fun rememberGlow(): androidx.compose.runtime.State<Float> =
    androidx.compose.animation.core.rememberInfiniteTransition(label = "frame").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1100), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "glow",
    )
