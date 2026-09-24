package com.termfold.app.acp

import android.content.Context
import android.util.Log
import com.termfold.app.shell.ProotCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** One content block inside a prompt or an agent update. */
sealed interface AcpBlock {
    data class Text(val text: String) : AcpBlock
    data class Image(val base64: String, val mimeType: String) : AcpBlock

    /**
     * A long paste sent as a file rather than inline prose. [name] is what the chip and the
     * agent see (`pasted-1.txt`).
     */
    data class TextFile(val name: String, val text: String) : AcpBlock
}

/** The visible timeline of an agent session. */
sealed interface AcpItem {
    abstract val id: Long

    data class UserMessage(
        override val id: Long,
        val text: String,
        val images: List<AcpBlock.Image>,
        val files: List<AcpBlock.TextFile> = emptyList(),
    ) : AcpItem

    /** Something that went wrong with a turn, shown inline where it happened. */
    data class Notice(override val id: Long, val text: String) : AcpItem

    /** Accumulated agent output; grows one chunk at a time. */
    data class AgentText(override val id: Long, val text: String) : AcpItem

    /**
     * The agent's thinking. Rendered collapsed — the newest line as a ticker — and expanded on
     * tap. Consecutive thought chunks merge into one block until a different kind of update
     * arrives, so one reasoning phase reads as one card.
     */
    data class Thought(override val id: Long, val text: String) : AcpItem

    data class ToolCall(
        override val id: Long,
        val toolCallId: String,
        val title: String,
        val kind: String,
        val status: String,
        val detail: String,
        /**
         * The files the tool touches, as the agent reports them: its `locations`, or failing that
         * the path-like fields of its `rawInput`. Titles alone are often just "Read" or "Write".
         */
        val paths: List<String> = emptyList(),
    ) : AcpItem

    /** The agent's plan (todo list), replaced wholesale on every update. */
    data class Plan(
        override val id: Long,
        val entries: List<PlanEntry>,
    ) : AcpItem

    data class PlanEntry(val text: String, val status: String)
}

data class AcpPermissionOption(val id: String, val name: String, val kind: String)

/**
 * A slash command the agent offers (its skills, /init, /review, ...). Sent back as ordinary
 * prompt text starting with "/name"; [hint] describes the argument, when it takes one.
 */
data class AcpCommand(val name: String, val description: String, val hint: String)

/**
 * A session setting the agent lets the user change: its model, reasoning level ("thought_level"),
 * mode, or anything else it publishes. Agents describe these either as `configOptions` (the
 * current protocol) or through the older `models` / `modes` lists; [source] records which, since
 * each is changed with its own method.
 */
data class AcpSetting(
    val id: String,
    val name: String,
    /** "model", "thought_level", "mode", "model_config", or an agent-specific "_..." category. */
    val category: String,
    /** A boolean option has no [choices]; its value is "true" or "false". */
    val isToggle: Boolean,
    val current: String,
    val choices: List<AcpChoice>,
    val source: Source,
) {
    enum class Source { CONFIG_OPTION, MODEL, MODE }

    val currentLabel: String get() = choices.firstOrNull { it.value == current }?.name ?: current
}

data class AcpChoice(val value: String, val name: String, val description: String = "")

data class AcpPendingPermission(
    /** The agent's JSON-RPC id, echoed back verbatim: a number or a string. */
    val requestId: Any,
    val toolCallId: String?,
    val options: List<AcpPermissionOption>,
)

enum class AcpPhase { CONNECTING, INSTALLING, READY, AUTH_REQUIRED, ERROR, CLOSED }

data class AcpUiState(
    val phase: AcpPhase = AcpPhase.CONNECTING,
    val statusText: String = "",
    val items: List<AcpItem> = emptyList(),
    /** True while a prompt is being worked on — drives the thinking indicators. */
    val agentBusy: Boolean = false,
    val pendingPermission: AcpPendingPermission? = null,
    val authMethods: List<String> = emptyList(),
    val errorDetail: String = "",
    /** What the agent said it accepts in prompts; unsupported content is sent as file links. */
    val acceptsImages: Boolean = false,
    val acceptsEmbeddedContext: Boolean = false,
    /** The agent's slash commands, from its latest available_commands_update. */
    val commands: List<AcpCommand> = emptyList(),
    /** Model, reasoning level, mode and other options the agent offers for this session. */
    val settings: List<AcpSetting> = emptyList(),
)

/**
 * Drives one ACP agent over the Agent Client Protocol: spawns the agent inside the bundled
 * Ubuntu environment, speaks JSON-RPC over its stdio, and exposes the resulting timeline as
 * [state] for a Compose surface.
 *
 * The client is deliberately small — initialize, session/new, session/prompt, session/cancel,
 * authenticate, plus the session/update stream and request_permission — which is the whole
 * surface a GUI needs to render an agent natively.
 */
class AcpClient(
    private val context: Context,
    private val sessionKey: String,
    private val agent: AcpAgent,
    private val workspacePath: String?,
    private val workspaceGuestDir: String,
) {

    class Factory(
        private val context: Context,
        private val workspacePath: String?,
        private val workspaceGuestDir: String,
    ) {
        fun create(sessionKey: String, agent: AcpAgent): AcpClient = AcpClient(
            context = context.applicationContext,
            sessionKey = sessionKey,
            agent = agent,
            workspacePath = workspacePath,
            workspaceGuestDir = workspaceGuestDir,
        )
    }

    private val _state = MutableStateFlow(AcpUiState())
    val state: StateFlow<AcpUiState> = _state.asStateFlow()

    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    private val writeLock = Any()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var writer: java.io.BufferedWriter? = null

    @Volatile
    private var agentSessionId: String? = null

    @Volatile
    private var closedByUser = false

    /** True from [start] until the attempt ends in an error, so re-entering the screen is a no-op. */
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    private val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    /**
     * Starts installation (if needed) and the agent process.
     *
     * The screen calls this every time it is entered. While an attempt is in flight or the
     * session is live it does nothing, so returning to a session keeps the same agent and
     * conversation. After an error or a refused sign-in it starts a fresh attempt, which is how
     * "log in from a Shell, then reopen this session" works.
     */
    fun start() {
        val retryable = _state.value.phase in setOf(AcpPhase.ERROR, AcpPhase.AUTH_REQUIRED, AcpPhase.CLOSED)
        if (running.get() && !retryable) return
        running.set(true)
        closedByUser = false
        runCatching { process?.destroy() }
        process = null
        agentSessionId = null
        failPending("Agent restarted")
        _state.update { it.copy(phase = AcpPhase.CONNECTING, errorDetail = "", pendingPermission = null) }
        scope.launch {
            try {
                begin()
            } catch (failure: Throwable) {
                Log.e(TAG, "ACP startup failed", failure)
                _state.update {
                    it.copy(
                        phase = AcpPhase.ERROR,
                        errorDetail = failure.message ?: failure.javaClass.simpleName,
                    )
                }
            }
        }
    }

    /** Kills the agent and starts it again, keeping the visible conversation. */
    fun restart() {
        _state.update { it.copy(phase = AcpPhase.CLOSED, agentBusy = false) }
        start()
    }

    private fun failPending(reason: String) {
        pending.keys.toList().forEach { id ->
            pending.remove(id)?.completeExceptionally(IllegalStateException(reason))
        }
    }

    private suspend fun begin() {
        _state.update { it.copy(phase = AcpPhase.INSTALLING, statusText = "Preparing ${agent.name}") }
        // The installer decides how to launch: a native binary, Node's npx, or Bun's runner for
        // npx packages — whichever survives this particular guest.
        val launch = withContext(Dispatchers.IO) {
            AcpInstaller.ensureInstalled(context, agent) { step ->
                _state.update { it.copy(statusText = step) }
            }
        }

        _state.update { it.copy(statusText = "Starting ${agent.name}") }
        val guestArgv = listOf(launch.command) + launch.args
        val command = ProotCommand.build(
            context = context,
            workspace = workspacePath,
            argv = guestArgv,
            guestCwd = workspaceGuestDir,
            guestMountPath = workspaceGuestDir,
            pathPrefix = AcpInstaller.pathPrefix(context, agent),
            extraEnv = launch.env + AcpInstaller.runtimeEnv(agent),
        )

        val process = ProcessBuilder(command).apply {
            environment().clear()
            environment().putAll(ProotCommand.hostEnvironment(context))
            redirectErrorStream(false)
        }.start()
        this.process = process
        writer = process.outputStream.bufferedWriter()

        // stderr carries agent diagnostics; stdout must stay pure JSON-RPC.
        // Both readers swallow IOExceptions: when the process exits Android closes its pipes
        // under a blocked read (InterruptedIOException), and an uncaught exception on these
        // threads would take the whole app down. Either way the stream has simply ended.
        Thread {
            runCatching {
                process.errorStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        Log.d("AcpAgent", line.take(500))
                    }
                }
            }
        }.start()

        val reader = BufferedReader(InputStreamReader(process.inputStream))
        Thread {
            runCatching {
                reader.forEachLine { line ->
                    runCatching { handleLine(line) }
                        .onFailure { Log.w(TAG, "Bad message", it) }
                }
            }
            // A retry replaced this process; its end says nothing about the current attempt.
            if (this.process !== process) return@Thread
            // The stream ending means the agent process is gone, and nothing will answer the
            // requests still waiting.
            failPending("Agent process exited")
            _state.update {
                if (!closedByUser && it.phase != AcpPhase.ERROR && it.phase != AcpPhase.AUTH_REQUIRED) {
                    it.copy(phase = AcpPhase.ERROR, errorDetail = "Agent process exited")
                } else {
                    it
                }
            }
        }.start()

        // Handshake. The generous timeout absorbs a first spawn that is still finishing
        // package-environment setup inside the guest (uvx resolving an interpreter, bun x
        // finishing an install the installer's prewarm could not complete in time).
        val initResponse = request(
            "initialize",
            JSONObject()
                .put("protocolVersion", 1)
                .put(
                    "clientCapabilities",
                    JSONObject()
                        .put("fs", JSONObject().put("readTextFile", false).put("writeTextFile", false))
                        .put("terminal", false),
                ),
            timeoutMs = 300_000,
        )
        val result = initResponse.optJSONObject("result")
        val authMethods = result
            ?.optJSONArray("authMethods")
            ?.let { methods -> List(methods.length()) { methods.getJSONObject(it).optString("name") } }
            ?: emptyList()
        val prompt = result?.optJSONObject("agentCapabilities")?.optJSONObject("promptCapabilities")
        _state.update {
            it.copy(
                authMethods = authMethods,
                acceptsImages = prompt?.optBoolean("image") == true,
                acceptsEmbeddedContext = prompt?.optBoolean("embeddedContext") == true,
            )
        }

        openSession()
    }

    private suspend fun openSession() {
        val cwd = workspaceGuestDir
        val response = try {
            request(
                "session/new",
                JSONObject().put("cwd", cwd).put("mcpServers", JSONArray()),
            )
        } catch (failure: Throwable) {
            // The stream is gone or the agent stopped answering — a transport failure, not a
            // sign-in problem, unless the process is still alive and says so.
            reportSessionFailure(
                failure.message ?: "session/new failed",
                agentSpeaking = process?.isAlive == true,
            )
            return
        }
        val error = response.optJSONObject("error")
        if (error != null) {
            // Agents refuse session/new when they need login credentials; that is the one
            // failure the user can act on, so it gets the auth screen rather than an error card.
            reportSessionFailure(
                error.optString("message").ifBlank { "session/new was refused" },
                agentSpeaking = true,
            )
            return
        }
        val sessionId = response.optJSONObject("result")?.optString("sessionId")
        if (sessionId.isNullOrBlank()) {
            _state.update { it.copy(phase = AcpPhase.ERROR, errorDetail = "No session id returned") }
            return
        }
        agentSessionId = sessionId
        val settings = parseSettings(response.optJSONObject("result"))
        _state.update { it.copy(phase = AcpPhase.READY, statusText = "", settings = settings) }
    }

    /** Routes a session/new failure to either the sign-in screen or the error card. */
    private fun reportSessionFailure(message: String, agentSpeaking: Boolean) {
        val looksAuthRelated = AUTH_HINTS.any { message.contains(it, ignoreCase = true) }
        val phase = if (agentSpeaking && looksAuthRelated) AcpPhase.AUTH_REQUIRED else AcpPhase.ERROR
        Log.w(TAG, "session/new failed ($phase): $message")
        _state.update { it.copy(phase = phase, errorDetail = message) }
    }

    /**
     * Sends a user prompt: plain text plus optional images. Returns immediately; the reply
     * arrives as timeline updates.
     */
    fun send(
        text: String,
        images: List<AcpBlock.Image>,
        files: List<AcpBlock.TextFile> = emptyList(),
    ) {
        val sessionId = agentSessionId ?: return
        val caps = _state.value
        val content = JSONArray()
        if (text.isNotBlank()) content.put(JSONObject().put("type", "text").put("text", text))

        // Pasted text goes as a file. Every paste is also written into the guest, so an agent
        // that only gets a link (no embeddedContext) can read it with its own tools, and one that
        // gets it embedded can still refer back to the file.
        files.forEach { file ->
            val guestPath = saveToGuest(file.name, file.text.toByteArray())
            if (caps.acceptsEmbeddedContext) {
                content.put(
                    JSONObject().put("type", "resource").put(
                        "resource",
                        JSONObject()
                            .put("uri", "file://$guestPath")
                            .put("mimeType", "text/plain")
                            .put("text", file.text),
                    ),
                )
            } else {
                content.put(resourceLink(file.name, guestPath, "text/plain", file.text.length.toLong()))
            }
        }

        images.forEachIndexed { index, image ->
            if (caps.acceptsImages) {
                content.put(
                    JSONObject()
                        .put("type", "image")
                        .put("data", image.base64)
                        .put("mimeType", image.mimeType),
                )
            } else {
                // Agents without image input still get the picture as a file they can open.
                val bytes = android.util.Base64.decode(image.base64, android.util.Base64.DEFAULT)
                val name = "image-${System.currentTimeMillis()}-${index + 1}.${extensionFor(image.mimeType)}"
                content.put(resourceLink(name, saveToGuest(name, bytes), image.mimeType, bytes.size.toLong()))
            }
        }
        if (content.length() == 0) return

        _state.update {
            it.copy(
                agentBusy = true,
                items = it.items + AcpItem.UserMessage(nextItemId(), text, images, files),
            )
        }
        scope.launch {
            try {
                // A coding turn routinely runs for many minutes; the reply to session/prompt
                // arrives only when the whole turn ends, so it must not be on a short timeout.
                val response = request(
                    "session/prompt",
                    JSONObject().put("sessionId", sessionId).put("prompt", content),
                    timeoutMs = PROMPT_TIMEOUT_MS,
                )
                response.optJSONObject("error")?.let { error ->
                    appendSystemError(
                        error.optJSONObject("data")?.optString("message")?.ifBlank { null }
                            ?: error.optString("message").ifBlank { "The agent could not answer." },
                    )
                }
                when (response.optJSONObject("result")?.optString("stopReason")) {
                    "max_tokens" -> appendSystemError("The reply was cut off at the model's length limit.")
                    "max_turn_requests" -> appendSystemError("The agent stopped after too many steps.")
                    "refusal" -> appendSystemError("The agent declined to continue.")
                }
            } catch (failure: Throwable) {
                Log.w(TAG, "prompt failed", failure)
                appendSystemError(failure.message ?: "prompt failed")
            } finally {
                _state.update { it.copy(agentBusy = false) }
            }
        }
    }

    private fun resourceLink(name: String, guestPath: String, mime: String, size: Long): JSONObject =
        JSONObject()
            .put("type", "resource_link")
            .put("uri", "file://$guestPath")
            .put("name", name)
            .put("mimeType", mime)
            .put("size", size)

    /** Writes an attachment into the guest's /tmp and returns its guest path. */
    private fun saveToGuest(name: String, bytes: ByteArray): String {
        val dir = java.io.File(com.termfold.app.shell.ShellPaths.rootfsDir(context), "tmp/termfold")
        dir.mkdirs()
        java.io.File(dir, name).writeBytes(bytes)
        return "/tmp/termfold/$name"
    }

    private fun extensionFor(mime: String): String = when (mime.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "png"
    }

    /**
     * Changes a session setting. The new value shows immediately; if the agent refuses, the old
     * one is put back and the reason is shown in the conversation.
     */
    fun changeSetting(setting: AcpSetting, value: String) {
        val sessionId = agentSessionId ?: return
        if (value == setting.current) return
        val previous = setting.current
        replaceSetting(setting.copy(current = value))
        scope.launch {
            val (method, params) = when (setting.source) {
                AcpSetting.Source.CONFIG_OPTION -> "session/set_config_option" to JSONObject()
                    .put("sessionId", sessionId)
                    .put("configId", setting.id)
                    .put("value", if (setting.isToggle) value.toBoolean() else value)
                AcpSetting.Source.MODEL -> "session/set_model" to JSONObject()
                    .put("sessionId", sessionId)
                    .put("modelId", value)
                AcpSetting.Source.MODE -> "session/set_mode" to JSONObject()
                    .put("sessionId", sessionId)
                    .put("modeId", value)
            }
            val response = runCatching { request(method, params) }.getOrElse { failure ->
                JSONObject().put("error", JSONObject().put("message", failure.message ?: "failed"))
            }
            val error = response.optJSONObject("error")
            if (error != null) {
                replaceSetting(setting.copy(current = previous))
                appendSystemError("Could not change ${setting.name}: ${error.optString("message")}")
                return@launch
            }
            // set_config_option answers with the complete, possibly re-shaped option set (a new
            // model can bring different reasoning levels), which replaces ours.
            response.optJSONObject("result")?.optJSONArray("configOptions")?.let { array ->
                val options = parseConfigOptions(array)
                _state.update { state ->
                    state.copy(settings = options + state.settings.filter { it.source != AcpSetting.Source.CONFIG_OPTION })
                }
            }
        }
    }

    private fun replaceSetting(updated: AcpSetting) {
        _state.update { state ->
            state.copy(
                settings = state.settings.map {
                    if (it.id == updated.id && it.source == updated.source) updated else it
                },
            )
        }
    }

    /**
     * The settings published in a session/new result. `configOptions` wins where present; the
     * older `models` / `modes` lists are used only for what it does not already cover.
     */
    private fun parseSettings(result: JSONObject?): List<AcpSetting> {
        result ?: return emptyList()
        val config = parseConfigOptions(result.optJSONArray("configOptions"))
        val covered = config.map { it.category }.toSet()
        val legacy = buildList {
            result.optJSONObject("models")?.takeIf { "model" !in covered }?.let { models ->
                val list = models.optJSONArray("availableModels") ?: JSONArray()
                val choices = List(list.length()) { list.optJSONObject(it) }.filterNotNull().map {
                    AcpChoice(it.optString("modelId"), it.optString("name").ifBlank { it.optString("modelId") }, it.optString("description"))
                }
                if (choices.isNotEmpty()) {
                    add(AcpSetting("model", "Model", "model", false, models.optString("currentModelId"), choices, AcpSetting.Source.MODEL))
                }
            }
            result.optJSONObject("modes")?.takeIf { "mode" !in covered }?.let { modes ->
                val list = modes.optJSONArray("availableModes") ?: JSONArray()
                val choices = List(list.length()) { list.optJSONObject(it) }.filterNotNull().map {
                    AcpChoice(it.optString("id"), it.optString("name").ifBlank { it.optString("id") }, it.optString("description"))
                }
                if (choices.isNotEmpty()) {
                    add(AcpSetting("mode", "Mode", "mode", false, modes.optString("currentModeId"), choices, AcpSetting.Source.MODE))
                }
            }
        }
        return config + legacy
    }

    private fun parseConfigOptions(array: JSONArray?): List<AcpSetting> {
        array ?: return emptyList()
        return List(array.length()) { array.optJSONObject(it) }.filterNotNull().mapNotNull { option ->
            val id = option.optString("id").ifBlank { return@mapNotNull null }
            val toggle = option.optString("type") == "boolean"
            // Select values may be flat or grouped ({group, name, options:[...]}); groups are
            // flattened, which is all a phone-sized picker needs.
            val choices = mutableListOf<AcpChoice>()
            fun collect(values: JSONArray?) {
                values ?: return
                for (i in 0 until values.length()) {
                    val entry = values.optJSONObject(i) ?: continue
                    if (entry.has("options")) {
                        collect(entry.optJSONArray("options"))
                    } else {
                        val value = entry.optString("value").ifBlank { entry.optString("id") }
                        if (value.isNotBlank()) {
                            choices += AcpChoice(value, entry.optString("name").ifBlank { value }, entry.optString("description"))
                        }
                    }
                }
            }
            collect(option.optJSONArray("options"))
            AcpSetting(
                id = id,
                name = option.optString("name").ifBlank { id },
                category = option.optString("category"),
                isToggle = toggle,
                current = option.opt("currentValue")?.toString().orEmpty(),
                choices = choices,
                source = AcpSetting.Source.CONFIG_OPTION,
            )
        }
    }

    fun cancel() {
        val sessionId = agentSessionId ?: return
        scope.launch { notify("session/cancel", JSONObject().put("sessionId", sessionId)) }
        _state.update { it.copy(agentBusy = false) }
    }

    /**
     * Answers the agent's `session/request_permission`. That is a request *from* the agent, so
     * the answer is a JSON-RPC response carrying the agent's own id; it has nothing to do with
     * [pending], which holds this client's outgoing requests.
     */
    fun respondPermission(requestId: Any, optionId: String?) {
        val outcome = if (optionId == null) {
            JSONObject().put("outcome", JSONObject().put("outcome", "cancelled"))
        } else {
            JSONObject().put("outcome", JSONObject().put("outcome", "selected").put("optionId", optionId))
        }
        writeLine(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", requestId)
                .put("result", outcome)
                .toString(),
        )
        _state.update { it.copy(pendingPermission = null) }
    }

    /** Ends the agent process. The stored agent install survives; only the process tree dies. */
    fun close() {
        closedByUser = true
        running.set(false)
        failPending("Session closed")
        runCatching { process?.destroy() }
        process = null
        scope.launch {
            _state.update { it.copy(phase = AcpPhase.CLOSED, agentBusy = false) }
        }
    }

    // --- Wire protocol --------------------------------------------------------------------------

    private fun request(method: String, params: JSONObject, timeoutMs: Long = 120_000): JSONObject {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        writeLine(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", method)
                .put("params", params)
                .toString(),
        )
        return kotlinx.coroutines.runBlocking {
            try {
                withTimeout(timeoutMs) { deferred.await() }
            } catch (failure: Throwable) {
                pending.remove(id)
                throw failure
            }
        }
    }

    private fun notify(method: String, params: JSONObject) {
        writeLine(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("method", method)
                .put("params", params)
                .toString(),
        )
    }

    private fun writeLine(line: String) {
        synchronized(writeLock) {
            writer?.let { writer ->
                writer.write(line)
                writer.write("\n")
                writer.flush()
            }
        }
    }

    private fun handleLine(line: String) {
        if (line.isBlank()) return
        val message = JSONObject(line)
        val method = message.optString("method").ifBlank { null }

        when {
            // A request from the agent: it expects a response.
            method != null && message.has("id") -> {
                // JSON-RPC ids may be numbers or strings; either is echoed back unchanged.
                val id = message.get("id")
                when (method) {
                    "session/request_permission" -> handlePermissionRequest(id, message.optJSONObject("params"))
                    "fs/read_text_file", "fs/write_text_file" -> respondError(
                        id,
                        "TermFold does not expose the host filesystem",
                    )

                    else -> respondError(id, "Unknown method")
                }
            }

            method != null -> when (method) {
                "session/update" -> handleUpdate(message.optJSONObject("params"))
                else -> Log.d(TAG, "Ignored notification: $method")
            }

            // A response to one of our requests.
            message.has("id") -> {
                val id = message.optLong("id", -1)
                pending.remove(id)?.complete(message)
            }
        }
    }

    private fun respondError(id: Any, message: String) {
        writeLine(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("error", JSONObject().put("code", -32_000).put("message", message))
                .toString(),
        )
    }

    private fun handlePermissionRequest(id: Any, params: JSONObject?) {
        val options = params?.optJSONArray("options") ?: JSONArray()
        val parsed = List(options.length()) { index ->
            val option = options.getJSONObject(index)
            AcpPermissionOption(
                id = option.optString("optionId"),
                name = option.optString("name"),
                kind = option.optString("kind"),
            )
        }
        val toolCallId = params?.optJSONObject("toolCall")?.optString("toolCallId")
        _state.update {
            it.copy(
                pendingPermission = AcpPendingPermission(
                    requestId = id,
                    toolCallId = toolCallId,
                    options = parsed,
                ),
            )
        }
    }

    private fun handleUpdate(params: JSONObject?) {
        val update = params?.optJSONObject("update") ?: return
        when (update.optString("sessionUpdate")) {
            "agent_message_chunk" -> blocks(update.opt("content")).forEach { block ->
                when (block) {
                    is AcpBlock.Text -> appendText(block.text, asThought = false)
                    is AcpBlock.Image -> appendImageRow(block)
                    is AcpBlock.TextFile -> Unit // only ever produced by the user's composer
                }
            }

            "agent_thought_chunk" -> blocks(update.opt("content")).forEach { block ->
                if (block is AcpBlock.Text) appendText(block.text, asThought = true)
            }

            "tool_call" -> {
                val item = AcpItem.ToolCall(
                    id = nextItemId(),
                    toolCallId = update.optString("toolCallId"),
                    title = update.optString("title").ifBlank { "Tool call" },
                    kind = update.optString("kind").ifBlank { "other" },
                    status = update.optString("status").ifBlank { "in_progress" },
                    detail = toolContentText(update.optJSONArray("content")),
                    paths = toolPaths(update),
                )
                _state.update { it.copy(items = it.items + item) }
            }

            "tool_call_update" -> {
                val toolCallId = update.optString("toolCallId")
                _state.update { state ->
                    state.copy(
                        items = state.items.map { item ->
                            if (item !is AcpItem.ToolCall || item.toolCallId != toolCallId) {
                                item
                            } else {
                                item.copy(
                                    title = update.optString("title").ifBlank { item.title },
                                    status = update.optString("status").ifBlank { item.status },
                                    // Updates carry the tool's content so far, so it replaces
                                    // rather than appends; an update without content keeps it.
                                    detail = toolContentText(update.optJSONArray("content"))
                                        .ifBlank { item.detail },
                                    paths = toolPaths(update).ifEmpty { item.paths },
                                )
                            }
                        },
                    )
                }
            }

            "config_option_update" -> {
                val options = parseConfigOptions(update.optJSONArray("configOptions"))
                _state.update { state ->
                    state.copy(settings = options + state.settings.filter { it.source != AcpSetting.Source.CONFIG_OPTION })
                }
            }

            "current_mode_update" -> {
                val modeId = update.optString("currentModeId")
                if (modeId.isNotBlank()) {
                    _state.update { state ->
                        state.copy(
                            settings = state.settings.map {
                                if (it.source == AcpSetting.Source.MODE) it.copy(current = modeId) else it
                            },
                        )
                    }
                }
            }

            "available_commands_update" -> {
                val list = update.optJSONArray("availableCommands") ?: JSONArray()
                val commands = List(list.length()) { list.optJSONObject(it) }.filterNotNull().mapNotNull { cmd ->
                    val name = cmd.optString("name").trim().removePrefix("/")
                    if (name.isBlank()) return@mapNotNull null
                    AcpCommand(
                        name = name,
                        description = cmd.optString("description"),
                        hint = cmd.optJSONObject("input")?.optString("hint").orEmpty(),
                    )
                }
                _state.update { it.copy(commands = commands) }
            }

            "plan" -> {
                val entries = update.optJSONArray("entries") ?: JSONArray()
                val item = AcpItem.Plan(
                    id = nextItemId(),
                    entries = List(entries.length()) { index ->
                        val entry = entries.getJSONObject(index)
                        AcpItem.PlanEntry(
                            text = entry.optString("content"),
                            status = entry.optString("status"),
                        )
                    },
                )
                _state.update { state ->
                    val withoutOld = state.items.filterNot { it is AcpItem.Plan }
                    state.copy(items = withoutOld + item)
                }
            }
        }
    }

    /**
     * Merges a text chunk into the timeline. Consecutive chunks of the same kind — message into
     * message, thought into thought — append to the trailing item, and a chunk of a different
     * kind starts a new one, which is what keeps one reasoning phase one card.
     */
    private fun appendText(text: String, asThought: Boolean) {
        if (text.isEmpty()) return
        _state.update { state ->
            val items = state.items.toMutableList()
            val last = items.lastOrNull()
            val continues = last is AcpItem.Thought && asThought ||
                last is AcpItem.AgentText && !asThought
            if (continues && last != null) {
                items[items.size - 1] = if (last is AcpItem.Thought) {
                    last.copy(text = last.text + text)
                } else {
                    (last as AcpItem.AgentText).copy(text = last.text + text)
                }
            } else if (asThought) {
                items.add(AcpItem.Thought(nextItemId(), text))
            } else {
                items.add(AcpItem.AgentText(nextItemId(), text))
            }
            state.copy(items = items)
        }
    }

    private fun appendImageRow(block: AcpBlock.Image) {
        _state.update { state ->
            state.copy(items = state.items + AcpItem.UserMessage(nextItemId(), "", listOf(block)))
        }
    }

    /**
     * Content blocks from an update. ACP sends a single block for message and thought chunks;
     * an array is accepted too, since some agents batch them.
     */
    private fun blocks(content: Any?): List<AcpBlock> {
        val list = when (content) {
            is JSONObject -> listOf(content)
            is JSONArray -> List(content.length()) { content.optJSONObject(it) }.filterNotNull()
            else -> return emptyList()
        }
        return list.map { block ->
            when (block.optString("type")) {
                "image" -> AcpBlock.Image(
                    base64 = block.optString("data"),
                    mimeType = block.optString("mimeType", "image/png"),
                )

                else -> AcpBlock.Text(block.optString("text"))
            }
        }.filterNot { it is AcpBlock.Text && it.text.isEmpty() }
    }

    /**
     * The files a tool call touches. `locations` is the protocol's field for it; agents that
     * leave it out usually still name the file in `rawInput`, under one of a few common keys.
     * Diff content also names its file.
     */
    private fun toolPaths(update: JSONObject): List<String> {
        val paths = linkedSetOf<String>()
        update.optJSONArray("locations")?.let { locations ->
            for (i in 0 until locations.length()) {
                locations.optJSONObject(i)?.optString("path")?.takeIf { it.isNotBlank() }?.let(paths::add)
            }
        }
        update.optJSONObject("rawInput")?.let { input ->
            listOf("path", "file_path", "filePath", "file", "filename", "target_file", "notebook_path")
                .mapNotNull { key -> input.optString(key).takeIf { it.isNotBlank() } }
                .forEach(paths::add)
            input.optJSONArray("paths")?.let { list ->
                for (i in 0 until list.length()) list.optString(i).takeIf { it.isNotBlank() }?.let(paths::add)
            }
        }
        update.optJSONArray("content")?.let { content ->
            for (i in 0 until content.length()) {
                val entry = content.optJSONObject(i) ?: continue
                if (entry.optString("type") == "diff") entry.optString("path").takeIf { it.isNotBlank() }?.let(paths::add)
            }
        }
        return paths.toList()
    }

    /**
     * Readable text of a tool call's content. Each entry is wrapped: `{type:"content",
     * content:{...}}` for ordinary output, `{type:"diff", path, ...}` for edits, and
     * `{type:"terminal"}` for terminals this client does not host.
     */
    private fun toolContentText(array: JSONArray?): String {
        array ?: return ""
        return List(array.length()) { array.optJSONObject(it) }.filterNotNull().mapNotNull { entry ->
            when (entry.optString("type")) {
                "content" -> blocks(entry.opt("content"))
                    .filterIsInstance<AcpBlock.Text>().joinToString("\n") { it.text }
                "diff" -> "Edited " + entry.optString("path")
                "text" -> entry.optString("text")
                else -> null
            }
        }.filter { it.isNotBlank() }.joinToString("\n").trim()
    }

    private fun appendSystemError(message: String) {
        _state.update { state ->
            state.copy(
                items = state.items + AcpItem.Notice(nextItemId(), message.take(500)),
            )
        }
    }

    private fun nextItemId(): Long = nextId.getAndIncrement() + 1_000_000

    private companion object {
        const val TAG = "AcpClient"

        /** session/prompt answers only when the turn ends, which can take a long while. */
        const val PROMPT_TIMEOUT_MS = 6L * 60 * 60 * 1000

        /** Message fragments that mean "credentials missing", the one error the auth screen fits. */
        val AUTH_HINTS = listOf(
            "auth", "login", "log in", "log-in", "sign in", "sign-in", "credential",
            "api key", "apikey", "api-key", "token", "unauthorised", "unauthorized",
        )
    }
}
