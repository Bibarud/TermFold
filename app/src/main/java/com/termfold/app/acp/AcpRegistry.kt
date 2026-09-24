package com.termfold.app.acp

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for the ACP registry (https://agentclientprotocol.com/registry) — the same index Zed and
 * JetBrains consume. Fetched once, cached on disk, and refreshed at most every [MAX_AGE_MS].
 *
 * Only agents usable on this device survive parsing: binary distributions are resolved to the
 * linux archive matching the running ABI, npx distributions require nothing at parse time (Node
 * is installed on demand).
 */
object AcpRegistry {

    private const val TAG = "AcpRegistry"
    private const val REGISTRY_URL =
        "https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json"
    private const val MAX_AGE_MS = 6 * 60 * 60 * 1000L

    @Volatile
    private var memoryCache: List<AcpAgent>? = null

    private fun cacheFile(context: Context): File =
        File(context.filesDir, "acp/registry.json")

    /** The agents offered for new sessions: [OFFERED], in that order. */
    suspend fun agents(context: Context, forceRefresh: Boolean = false): List<AcpAgent> =
        all(context, forceRefresh)
            .filter { it.id in OFFERED }
            .sortedBy { OFFERED.indexOf(it.id) }

    /**
     * Every agent usable on this device, offered or not. Existing sessions are resolved against
     * this, so a session created for an agent that is no longer offered still opens.
     */
    suspend fun all(context: Context, forceRefresh: Boolean = false): List<AcpAgent> {
        memoryCache?.let { if (!forceRefresh) return it }

        val cache = cacheFile(context)
        val fresh = cache.isFile && System.currentTimeMillis() - cache.lastModified() < MAX_AGE_MS
        if (!forceRefresh && fresh) {
            parse(cache.readText())?.let { parsed ->
                memoryCache = parsed
                return parsed
            }
        }

        val body = withContext(Dispatchers.IO) {
            runCatching {
                val connection = URL(REGISTRY_URL).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.inputStream.bufferedReader().use { it.readText() }
            }.onFailure {
                Log.w(TAG, "Registry fetch failed", it)
            }.getOrNull()
        }
        if (body.isNullOrBlank()) {
            // Offline, or the CDN is unreachable: stale cache beats nothing.
            if (cache.isFile) parse(cache.readText())?.let { parsed ->
                memoryCache = parsed
                return parsed
            }
            return emptyList()
        }

        runCatching {
            cache.parentFile?.mkdirs()
            cache.writeText(body)
        }
        return parse(body)?.also { memoryCache = it } ?: emptyList()
    }

    fun cached(context: Context, agentId: String): AcpAgent? =
        memoryCache?.firstOrNull { it.id == agentId }

    /**
     * The agents offered, in display order. The registry lists dozens; only these are shown.
     * Ids not in the registry (omp) are supplied by [extraAgents].
     */
    private val OFFERED = listOf(
        "claude-acp", "codex-acp", "opencode", "cursor", "devin", "pi-acp", "omp", "antigravity-acp",
    )

    private fun parse(body: String): List<AcpAgent>? = runCatching {
        val root = JSONObject(body)
        val agents = root.optJSONArray("agents") ?: return emptyList()
        val fromRegistry = List(agents.length()) { index ->
            runCatching { fromJson(agents.getJSONObject(index)) }.getOrNull()
        }.filterNotNull()
        (fromRegistry + extraAgents())
            .distinctBy { it.id }
            .sortedBy { it.name.lowercase() }
    }.onFailure { Log.w(TAG, "Could not parse the registry", it) }.getOrNull()

    /**
     * Agents that speak ACP but are not in the registry.
     *
     * omp (oh-my-pi) ships standalone Bun-compiled executables, served as a raw ELF — which
     * [AcpArchives.extract] lands at [AcpAgent.cmd] — and runs as an ACP server via `omp acp`.
     * The npm package is not used because it needs a newer Bun than the guest has.
     */
    private fun extraAgents(): List<AcpAgent> {
        val arch = if (Build.SUPPORTED_ABIS.firstOrNull()?.contains("x86") == true) "x64" else "arm64"
        return listOf(
            AcpAgent(
                id = "omp",
                name = "omp (oh-my-pi)",
                // Pinned: the version doubles as the installed-manifest key, so bumping it here
                // is what makes existing installs fetch the new binary.
                version = OMP_VERSION,
                description = "Coding agent CLI with read, bash, edit and write tools",
                iconUrl = "",
                repository = "https://github.com/can1357/oh-my-pi",
                kind = AcpAgent.Kind.BINARY,
                archiveUrl = "https://github.com/can1357/oh-my-pi/releases/download/v$OMP_VERSION/omp-linux-$arch",
                archiveSha256 = null,
                cmd = "./omp",
                args = listOf("acp"),
                npxPackage = null,
                packageArgs = emptyList(),
            ),
        )
    }

    private const val OMP_VERSION = "18.3.0"

    private fun fromJson(agent: JSONObject): AcpAgent {
        val dist = agent.optJSONObject("distribution")
        val binary = dist?.optJSONObject("binary")
        val npx = dist?.optJSONObject("npx")
        val uvx = dist?.optJSONObject("uvx")

        if (binary != null) {
            val platform = when {
                Build.SUPPORTED_ABIS.firstOrNull()?.contains("x86") == true -> "linux-x86_64"
                else -> "linux-aarch64"
            }
            // Only the exact ABI is usable: the guest has no user-mode emulation, so an
            // x86_64 binary would die with a confusing exec failure on an arm64 phone and
            // vice versa. Agents without a build for this device are simply not offered.
            val entry = requireNotNull(binary.optJSONObject(platform)) { "no $platform distribution" }
            return AcpAgent(
                id = agent.getString("id"),
                name = agent.getString("name"),
                version = agent.optString("version"),
                description = agent.optString("description"),
                iconUrl = agent.optString("icon"),
                repository = agent.optString("repository"),
                kind = AcpAgent.Kind.BINARY,
                archiveUrl = entry.optString("archive"),
                archiveSha256 = entry.optString("sha256"),
                cmd = entry.optString("cmd"),
                args = entry.optJSONArray("args")?.let { args ->
                    List(args.length()) { args.getString(it) }
                } ?: emptyList(),
                npxPackage = null,
                packageArgs = emptyList(),
                env = entry.optJSONObject("env")?.toStringMap() ?: emptyMap(),
            )
        }

        if (npx != null) {
            return AcpAgent(
                id = agent.getString("id"),
                name = agent.getString("name"),
                version = agent.optString("version"),
                description = agent.optString("description"),
                iconUrl = agent.optString("icon"),
                repository = agent.optString("repository"),
                kind = AcpAgent.Kind.NPX,
                archiveUrl = null,
                archiveSha256 = null,
                cmd = null,
                args = emptyList(),
                npxPackage = npx.getString("package"),
                packageArgs = npx.optJSONArray("args")?.let { args ->
                    List(args.length()) { args.getString(it) }
                } ?: emptyList(),
                env = npx.optJSONObject("env")?.toStringMap() ?: emptyMap(),
            )
        }

        if (uvx != null) {
            return AcpAgent(
                id = agent.getString("id"),
                name = agent.getString("name"),
                version = agent.optString("version"),
                description = agent.optString("description"),
                iconUrl = agent.optString("icon"),
                repository = agent.optString("repository"),
                kind = AcpAgent.Kind.UVX,
                archiveUrl = null,
                archiveSha256 = null,
                cmd = null,
                args = emptyList(),
                npxPackage = null,
                uvxPackage = uvx.getString("package"),
                packageArgs = uvx.optJSONArray("args")?.let { args ->
                    List(args.length()) { args.getString(it) }
                } ?: emptyList(),
                env = uvx.optJSONObject("env")?.toStringMap() ?: emptyMap(),
            )
        }

        error("unsupported distribution type")
    }

    private fun JSONObject.toStringMap(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        keys().forEach { key -> result[key] = optString(key) }
        return result
    }
}
