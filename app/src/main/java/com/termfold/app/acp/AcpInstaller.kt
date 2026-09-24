package com.termfold.app.acp

import android.content.Context
import android.util.Log
import com.termfold.app.shell.ProotCommand
import com.termfold.app.shell.ShellConfig
import com.termfold.app.shell.ShellPaths
import com.termfold.app.shell.TarExtractor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Installs registry agents into the bundled Ubuntu environment so they can be executed by PRoot.
 *
 * Binary agents are downloaded from the registry's archive URL, verified, and unpacked under
 * `/opt/acp/<id>` in the guest. Registry `cmd` entries are paths relative to the archive root
 * ("./bin/devin", "./dist-package/cursor-agent"), so the exact path is honoured — with fallbacks
 * for archives that wrap everything in one versioned directory. Some registries ship a raw ELF
 * binary instead of an archive (sigit), and goose ships bzip2; all are handled here.
 *
 * npx agents need Node.js, which is provided from the official nodejs.org tarball rather than
 * apt — carrier networks and transient mirror failures made apt the least reliable step. uvx
 * agents run through uv, a single static binary, whose managed Python and package cache live
 * under /opt/uv as well. A manifest records what was installed so an unchanged agent is never
 * re-fetched.
 */
object AcpInstaller {

    private const val TAG = "AcpInstaller"

    /** Identifies the app to the CDNs that serve agent archives; some refuse UA-less requests. */
    private const val USER_AGENT = "TermFold-ACP"

    /** Node 22: pi's CLI requires >= 22.19. Bumping this upgrades existing installs. */
    internal const val NODE_VERSION = "v22.23.3"

    /**
     * npm packages an agent's ACP adapter needs installed globally beside it. pi-acp is only a
     * bridge: it launches the real `pi` CLI and fails session/new with "executable not found
     * (command: pi)" when it is missing.
     */
    private val COMPANION_PACKAGES = mapOf(
        "pi-acp" to "@earendil-works/pi-coding-agent",
    )

    private const val NODE_ROOT = "/opt/node"
    private const val NODE_BIN = "$NODE_ROOT/bin"

    private const val UV_ROOT = "/opt/uv"

    /** Where one agent's files live inside the guest. */
    fun installDir(context: Context, agentId: String): File =
        File(ShellPaths.rootfsDir(context), "opt/acp/$agentId")

    /**
     * Agents that dlopen their embedded libraries through /proc/self/fd, which PRoot breaks
     * ("file too short"). Only these get the shim: preloading a glibc library into every process
     * would break any musl-linked tool.
     */
    private val NEEDS_PROCFD_SHIM = setOf("antigravity-acp")

    /**
     * Environment added at every spawn. Kept out of the install manifest, so an agent installed
     * before an entry existed here still gets it.
     */
    fun runtimeEnv(agent: AcpAgent): Map<String, String> =
        if (agent.id in NEEDS_PROCFD_SHIM) mapOf("LD_PRELOAD" to ShellConfig.PROCFD_SHIM) else emptyMap()

    /** Extra PATH the agent process needs (Node for npx agents), or null. */
    fun pathPrefix(context: Context, agent: AcpAgent): String? =
        if (agent.kind == AcpAgent.Kind.NPX) NODE_BIN else null

    fun isInstalled(context: Context, agent: AcpAgent): Boolean =
        manifestFile(context, agent.id).takeIf { it.isFile }
            ?.let { it.readText().contains("\"version\":\"${agent.version}\"") } == true

    private fun manifestFile(context: Context, agentId: String): File =
        File(installDir(context, agentId), "manifest.json")

    /** How a spawned agent is launched inside the guest. */
    data class Launch(
        val command: String,
        val args: List<String>,
        /** Additional guest environment variables (uv caches, registry-required settings). */
        val env: Map<String, String> = emptyMap(),
    )

    /**
     * Makes sure [agent] is runnable, reporting human-readable progress steps along the way.
     * Returns how to spawn it.
     */
    suspend fun ensureInstalled(
        context: Context,
        agent: AcpAgent,
        onProgress: (String) -> Unit,
    ): Launch {
        // Before the installed check, so existing installs also get a Node upgrade or a
        // companion added after they were first set up. Both are no-ops once in place.
        if (agent.kind == AcpAgent.Kind.NPX) {
            ensureNode(context, onProgress)
            ensureCompanion(context, agent, onProgress)
        }

        if (isInstalled(context, agent)) {
            onProgress("Already installed")
            return launch(context, agent)
        }

        when (agent.kind) {
            AcpAgent.Kind.BINARY -> {
                onProgress("Downloading ${agent.name} ${agent.version}")
                val archive = download(agent.archiveUrl!!, context.cacheDir, agent.id)
                verify(archive, agent.archiveSha256)
                onProgress("Unpacking")
                val dir = installDir(context, agent.id)
                dir.deleteRecursively()
                dir.mkdirs()
                AcpArchives.extract(archive, dir, agent.cmd)
                archive.delete()

                // The registry's cmd must exist in what we unpacked — a silent miss here would
                // only surface as a confusing spawn failure later.
                val cmd = AcpArchives.resolveCommand(dir, agent.cmd ?: error("Registry entry has no command"))
                    ?: error(
                        "Downloaded archive did not contain '${agent.cmd}'. Top level: " +
                            dir.listFiles()?.joinToString(", ") { it.name }.orEmpty(),
                    )
                cmd.setExecutable(true, false)
                val launch = Launch(
                    "/opt/acp/${agent.id}/${cmd.relativeTo(dir).invariantSeparatorsPath}",
                    agent.args,
                    agent.env,
                )
                writeManifest(dir, agent, launch)
                Log.i(TAG, "Installed ${agent.id} ${agent.version} -> ${launch.command}")
                return launch
            }
            AcpAgent.Kind.NPX -> {
                // npm's cache installs through POSIX renames that do not survive PRoot's
                // syscall rewriting on worker threads. Probe for that; when renames are broken,
                // fall back to Bun — a single static binary whose package runner needs none.
                val renameWorks = checkNodeRename(context, onProgress)
                val launch = if (renameWorks) {
                    // Fetch the package into the npm cache without running it: agents ignore
                    // --version and start their ACP server, which then waits on stdin.
                    //
                    // This must succeed. Marking the agent installed over a failed fetch leaves
                    // the spawn to download it cold, which can outlast the initialize timeout or
                    // die with "sh: <bin>: not found" on a half-linked cache. So it is retried
                    // (mobile networks drop), and a final failure is reported for the user to
                    // retry by reopening the session.
                    var prewarm = 0 to ""
                    for (attempt in 1..3) {
                        onProgress(
                            if (attempt == 1) "Fetching ${agent.npxPackage}"
                            else "Fetching ${agent.npxPackage} (attempt $attempt)",
                        )
                        prewarm = runCapture(
                            context,
                            "npx -y -p ${agent.npxPackage} true",
                            pathPrefix = NODE_BIN,
                            timeoutSeconds = 600,
                        )
                        if (prewarm.first == 0) break
                        Log.w(TAG, "npx prewarm attempt $attempt exited ${prewarm.first}: ${prewarm.second.takeLast(1_000)}")
                    }
                    check(prewarm.first == 0) {
                        "Could not download ${agent.npxPackage}. " +
                            prewarm.second.lines().lastOrNull { it.isNotBlank() }.orEmpty()
                    }
                    ensureNativeOptionals(context, agent, onProgress)
                    Launch("npx", listOf("-y", agent.npxPackage!!) + agent.packageArgs, agent.env)
                } else {
                    Log.w(TAG, "guest rename is broken; installing Bun for ${agent.id}")
                    ensureBun(context, onProgress)
                    ensureBunConfig(context)
                    // First `bun x` run downloads and installs the package, which can easily
                    // outlast the session's initialize timeout — resolve it now, while the
                    // user only sees a progress step. Non-fatal: a failed prewarm just means
                    // the real spawn does the work.
                    onProgress("Fetching ${agent.npxPackage}")
                    val prewarm = runCapture(
                        context,
                        "/opt/bun/bun x ${agent.npxPackage} --version",
                        timeoutSeconds = 900,
                        extraEnv = bunEnvironment(),
                    )
                    if (prewarm.first != 0) {
                        Log.w(TAG, "bun prewarm failed: ${prewarm.second.takeLast(1_000)}")
                    }
                    Launch(
                        "/opt/bun/bun",
                        listOf("x", agent.npxPackage!!) + agent.packageArgs,
                        agent.env + bunEnvironment(),
                    )
                }

                onProgress("Preparing ${agent.name}")
                val packageDir = installDir(context, agent.id)
                packageDir.mkdirs()
                writeManifest(packageDir, agent, launch)
                Log.i(TAG, "Installed ${agent.id} ${agent.version}")
                return launch
            }
            AcpAgent.Kind.UVX -> {
                ensureUv(context, onProgress)

                // Warm uv's caches so the first session does not pay for the Python download
                // and the package resolution. Both steps are best-effort: a prewarm failure
                // only means the real spawn does the work instead.
                onProgress("Preparing Python (one-time)")
                val python = runCapture(
                    context,
                    "$UV_ROOT/uv python install 3.12",
                    timeoutSeconds = 600,
                    extraEnv = uvEnvironment(agent),
                )
                if (python.first != 0) {
                    Log.w(TAG, "uv python install failed: ${python.second.takeLast(1_000)}")
                }
                onProgress("Fetching ${agent.uvxPackage}")
                val prewarm = runCapture(
                    context,
                    "$UV_ROOT/uvx ${agent.uvxPackage} --help",
                    timeoutSeconds = 900,
                    extraEnv = uvEnvironment(agent),
                )
                if (prewarm.first != 0) {
                    Log.w(TAG, "uvx prewarm failed: ${prewarm.second.takeLast(1_000)}")
                }

                onProgress("Preparing ${agent.name}")
                val packageDir = installDir(context, agent.id)
                packageDir.mkdirs()
                val launch = Launch(
                    "$UV_ROOT/uvx",
                    listOf(agent.uvxPackage!!) + agent.packageArgs,
                    uvEnvironment(agent),
                )
                writeManifest(packageDir, agent, launch)
                Log.i(TAG, "Installed ${agent.id} ${agent.version}")
                return launch
            }
        }
    }

    /** The guest environment every uv invocation needs, layered under the registry's own. */
    private fun uvEnvironment(agent: AcpAgent): Map<String, String> = mapOf(
        // uv's default hardlink-and-reflink cache strategy breaks on this filesystem; copies
        // always work.
        "UV_LINK_MODE" to "copy",
        "UV_CACHE_DIR" to "$UV_ROOT/cache",
        "UV_PYTHON_INSTALL_DIR" to "$UV_ROOT/python",
    ) + agent.env

    /** Resolves how to spawn an already-installed [agent] from its install manifest. */
    private fun launch(context: Context, agent: AcpAgent): Launch {
        val manifest = JSONObject(manifestFile(context, agent.id).readText())
        val args = manifest.optJSONArray("args")?.let { array ->
            List(array.length()) { array.getString(it) }
        } ?: emptyList()
        val env = manifest.optJSONObject("env")?.let { obj ->
            buildMap {
                obj.keys().forEach { key -> put(key, obj.optString(key)) }
            }
        } ?: emptyMap()
        return Launch(manifest.getString("command"), args, env)
    }

    /**
     * Probes whether Node can actually rename — npm dies with ENOSYS in the guest when PRoot's
     * syscall rewriting misses calls from worker threads.
     */
    private fun checkNodeRename(context: Context, onProgress: (String) -> Unit): Boolean {
        onProgress("Checking environment")
        val probe = File(ShellPaths.rootfsDir(context), "root/.termfold-rename-probe.js")
        probe.parentFile?.mkdirs()
        probe.writeText(
            """
            const fs = require('fs');
            try {
              fs.writeFileSync('/tmp/.termfold-a', 'x');
              fs.renameSync('/tmp/.termfold-a', '/tmp/.termfold-b');
              console.log('RENAME_OK');
            } catch (e) {
              console.log('RENAME_FAIL ' + (e.code || e.message));
            }
            """.trimIndent(),
        )
        val result = runCapture(
            context,
            "/opt/node/bin/node /root/.termfold-rename-probe.js",
            pathPrefix = NODE_BIN,
            timeoutSeconds = 60,
        )
        probe.delete()
        return result.second.contains("RENAME_OK")
    }

    /** Installs the Bun runtime — one static binary — for npm-free package running. */
    private fun ensureBun(context: Context, onProgress: (String) -> Unit) {
        val bunDir = File(ShellPaths.rootfsDir(context), "opt/bun")
        if (File(bunDir, "bun").isFile) return

        val arch = when {
            android.os.Build.SUPPORTED_ABIS.firstOrNull()?.contains("x86") == true -> "x64"
            else -> "aarch64"
        }
        val url = "https://github.com/oven-sh/bun/releases/download/bun-v1.1.34/bun-linux-$arch.zip"
        onProgress("Downloading Bun runtime")
        val archive = download(url, context.cacheDir, "bun")
        onProgress("Unpacking Bun")
        bunDir.deleteRecursively()
        AcpArchives.extract(archive, bunDir, null)
        archive.delete()
        val bun = File(bunDir, "bun-linux-$arch/bun").takeIf { it.isFile }
            ?: File(bunDir, "bun").takeIf { it.isFile }
            ?: error("Bun archive did not contain the bun binary")
        bun.setExecutable(true, false)
        if (bun.parentFile != bunDir) {
            bun.renameTo(File(bunDir, "bun"))
        }
        ensureBunConfig(context)
    }

    /**
     * Bun's default package install uses hardlinks into a cache under $HOME, and both fail on
     * this filesystem (link(2) is denied; half-written cache entries then break every later
     * install with FileNotFound). Plain copies into a cache under /opt/bun sidestep both.
     */
    private fun ensureBunConfig(context: Context) {
        val bunEnv = bunEnvironment()
        File(ShellPaths.rootfsDir(context), "root/bunfig.toml").let { fig ->
            if (!fig.isFile) {
                fig.parentFile?.mkdirs()
                fig.writeText(
                    """
                    [install]
                    backend = "copyfile"
                    """.trimIndent(),
                )
            }
        }
        File(ShellPaths.rootfsDir(context), bunEnv[BUN_CACHE_ENV]!!.removePrefix("/")).mkdirs()
    }

    private fun bunEnvironment(): Map<String, String> = mapOf(BUN_CACHE_ENV to "$BUN_ROOT/cache")

    private const val BUN_CACHE_ENV = "BUN_INSTALL_CACHE_DIR"
    private const val BUN_ROOT = "/opt/bun"

    /**
     * Installs uv — one static binary that manages Python environments — for uvx agents. The
     * managed interpreters and package caches it writes land under /opt/uv so they persist in
     * the rootfs like everything else.
     */
    private fun ensureUv(context: Context, onProgress: (String) -> Unit) {
        val uvDir = File(ShellPaths.rootfsDir(context), UV_ROOT.trimStart('/'))
        if (File(uvDir, "uvx").isFile) return

        onProgress("Downloading uv runtime")
        val arch = when {
            android.os.Build.SUPPORTED_ABIS.firstOrNull()?.contains("x86") == true -> "x86_64"
            else -> "aarch64"
        }
        val url = "https://github.com/astral-sh/uv/releases/latest/download/uv-$arch-unknown-linux-musl.tar.gz"
        val archive = download(url, context.cacheDir, "uv")
        onProgress("Unpacking uv")
        uvDir.deleteRecursively()
        val staging = File(ShellPaths.rootfsDir(context), "opt/.uv-staging")
        staging.deleteRecursively()
        AcpArchives.extract(archive, staging, null)
        archive.delete()

        // The tarball unpacks as uv-<arch>-unknown-linux-musl/{uv,uvx} — normalise to /opt/uv.
        val unpackedBin = staging.walkTopDown().firstOrNull { it.isFile && it.name == "uvx" }
            ?: error("uv archive did not contain the uvx binary")
        uvDir.mkdirs()
        for (name in listOf("uv", "uvx")) {
            val source = File(unpackedBin.parentFile, name)
            if (source.isFile) {
                val target = File(uvDir, name)
                target.delete()
                if (!source.renameTo(target)) {
                    source.copyTo(target, overwrite = true)
                    source.delete()
                }
                target.setExecutable(true, false)
            }
        }
        staging.deleteRecursively()
    }

    private fun writeManifest(dir: File, agent: AcpAgent, launch: Launch) {
        dir.mkdirs()
        dir.resolve("manifest.json").writeText(
            JSONObject()
                .put("id", agent.id)
                .put("version", agent.version)
                .put("kind", agent.kind.name)
                .put("command", launch.command)
                .put("args", JSONArray(launch.args))
                .put("env", JSONObject(launch.env))
                .toString()
        )
    }

    /**
     * Downloads with up to three attempts, validating each attempt against the server's
     * Content-Length — a truncated or HTML-error-page download used to surface only much later
     * as a cryptic inflate failure. Archive-type validation happens by magic bytes in
     * [AcpArchives.extract], not by file-name heuristics.
     */
    private fun download(url: String, cacheDir: File, name: String, attempts: Int = 3): File {
        val target = File(cacheDir, "acp/$name.archive")
        target.parentFile?.mkdirs()
        var lastError: Throwable? = null

        repeat(attempts) { attempt ->
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 20_000
                connection.readTimeout = 60_000
                connection.instanceFollowRedirects = true
                // Some CDNs (Fastly in particular) refuse requests without one.
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.inputStream.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                val expected = connection.contentLengthLong
                if (expected > 0 && target.length() != expected) {
                    error("Incomplete download: ${target.length()} of $expected bytes")
                }
                if (target.length() == 0L) {
                    error("Empty download")
                }
                return target
            } catch (failure: Throwable) {
                lastError = failure
                Log.w(TAG, "Download attempt ${attempt + 1} failed", failure)
                target.delete()
            }
        }
        throw IllegalStateException(
            "Download failed after $attempts attempts: ${lastError?.message}",
            lastError,
        )
    }

    /** Registry-published checksums are honoured whenever the entry ships one. */
    private fun verify(archive: File, expectedSha256: String?) {
        if (expectedSha256.isNullOrBlank()) return
        val digest = MessageDigest.getInstance("SHA-256").digest(archive.readBytes())
        val actual = digest.joinToString("") { "%02x".format(it) }
        check(actual == expectedSha256.lowercase()) {
            "Download failed verification: expected $expectedSha256, got $actual"
        }
    }

    /**
     * npx agents need Node. Installed from the official nodejs.org tarball — no apt, whose
     * mirrors and locks were the least reliable link on mobile networks. The tarball unpacks as
     * node-<version>-linux-<arch>/, which is renamed to a stable /opt/node.
     */
    private fun ensureNode(context: Context, onProgress: (String) -> Unit) {
        val nodeDir = File(ShellPaths.rootfsDir(context), NODE_ROOT.trimStart('/'))
        // Installs made before the version marker existed have no marker, so they upgrade too.
        val marker = File(nodeDir, ".termfold-version")
        if (File(nodeDir, "bin/npx").isFile && marker.isFile && marker.readText() == NODE_VERSION) return

        onProgress("Installing Node.js (one-time)")
        val arch = when {
            android.os.Build.SUPPORTED_ABIS.firstOrNull()?.contains("x86") == true -> "x64"
            else -> "arm64"
        }
        val url = "https://nodejs.org/dist/$NODE_VERSION/node-$NODE_VERSION-linux-$arch.tar.gz"
        val archive = download(url, context.cacheDir, "node")
        onProgress("Unpacking Node.js")
        val parent = File(ShellPaths.rootfsDir(context), "opt")
        parent.mkdirs()
        // TarExtractor gunzips internally — the raw stream goes in.
        archive.inputStream().buffered().use { input ->
            TarExtractor.extract(input, parent)
        }
        archive.delete()

        val unpacked = parent.listFiles { file -> file.name.startsWith("node-") }
            ?.firstOrNull { it.isDirectory }
            ?: error("Node tarball did not unpack as expected")
        nodeDir.deleteRecursively()
        check(unpacked.renameTo(nodeDir)) { "Could not finalise the Node.js install" }
        File(nodeDir, "bin/npx").setExecutable(true, false)

        // npm caches written before this Node was installed are not trusted: installs from before
        // the rename(2) fix left packages half-extracted (npx then fails with ERR_MODULE_NOT_FOUND
        // on every launch and never repairs itself), and a new Node major can invalidate native
        // modules. Fetching again is the only cost.
        listOf("root/.npm/_npx", "root/.npm/_cacache").forEach {
            File(ShellPaths.rootfsDir(context), it).deleteRecursively()
        }
        // npx agents recorded as installed now have nothing cached, and a cold `npx` download
        // inside the spawn can outlast the initialize timeout. Dropping their manifests sends
        // them back through the install path, whose prewarm fetches the package first.
        File(ShellPaths.rootfsDir(context), "opt/acp").listFiles()?.forEach { dir ->
            val manifest = File(dir, "manifest.json")
            if (manifest.isFile && manifest.readText().contains("\"kind\":\"NPX\"")) manifest.delete()
        }
        marker.writeText(NODE_VERSION)
    }

    /**
     * Makes sure the platform-native optional dependencies of an npx agent actually installed.
     *
     * Agents such as Claude and Codex ship their engine as per-platform optionalDependencies
     * (`@anthropic-ai/claude-agent-sdk-linux-arm64`, ...). Those downloads are large, and npm
     * skips an optional dependency that fails to download without reporting anything, so a
     * dropped connection leaves an agent that dies with "Claude native binary not found". The
     * probe lists the linux/glibc packages for this CPU that are declared but absent; they are
     * then installed as ordinary dependencies, where a failure is an error that gets retried.
     */
    private fun ensureNativeOptionals(context: Context, agent: AcpAgent, onProgress: (String) -> Unit) {
        // "@scope/pkg@1.2.3" -> "@scope/pkg"; an '@' at index 0 is the scope, not a version.
        val spec = agent.npxPackage ?: return
        val name = spec.lastIndexOf('@').let { at -> if (at > 0) spec.substring(0, at) else spec }
        val rootfs = ShellPaths.rootfsDir(context)
        val npxDir = File(rootfs, "root/.npm/_npx").listFiles()
            ?.firstOrNull { File(it, "package.json").takeIf(File::isFile)?.readText()?.contains("\"$name\"") == true }
            ?: return
        val guestDir = "/root/.npm/_npx/${npxDir.name}"

        val probe = File(rootfs, "root/.termfold-native-probe.js")
        probe.writeText(NATIVE_PROBE)
        try {
            repeat(3) { attempt ->
                val missing = runCapture(
                    context,
                    "/opt/node/bin/node /root/.termfold-native-probe.js $guestDir",
                    pathPrefix = NODE_BIN,
                    timeoutSeconds = 60,
                ).second.lines().map { it.trim() }.filter { it.contains('@') }
                if (missing.isEmpty()) return
                Log.w(TAG, "${agent.id}: missing native packages $missing (attempt ${attempt + 1})")
                onProgress("Downloading ${agent.name} engine")
                runCapture(
                    context,
                    "npm install --no-save --prefix $guestDir ${missing.joinToString(" ")}",
                    pathPrefix = NODE_BIN,
                    timeoutSeconds = 900,
                )
            }
            error("Could not download the ${agent.name} engine for this device. Check the connection and reopen the session.")
        } finally {
            probe.delete()
        }
    }

    /**
     * Prints, one per line as `name@version`, the optionalDependencies anywhere under the given
     * npx directory that target linux on this CPU with glibc and are not installed.
     */
    private val NATIVE_PROBE = """
        const fs = require('fs'), path = require('path');
        const nm = path.join(process.argv[2], 'node_modules');
        const arch = process.arch;
        const wanted = new Map(), installed = new Set();
        function scan(dir, scope) {
          let entries; try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return; }
          for (const e of entries) {
            if (!e.isDirectory() || e.name === '.bin') continue;
            const p = path.join(dir, e.name);
            if (e.name.startsWith('@')) { scan(p, e.name); continue; }
            const id = scope ? scope + '/' + e.name : e.name;
            try {
              const pkg = JSON.parse(fs.readFileSync(path.join(p, 'package.json'), 'utf8'));
              installed.add(id);
              for (const [n, v] of Object.entries(pkg.optionalDependencies || {})) {
                const l = n.toLowerCase();
                if (l.includes('linux') && l.includes(arch) && !l.includes('musl')) wanted.set(n, v);
              }
            } catch {}
            scan(path.join(p, 'node_modules'), null);
          }
        }
        scan(nm, null);
        for (const [n, v] of wanted) if (!installed.has(n)) console.log(n + '@' + v);
    """.trimIndent()

    /** Installs [agent]'s entry in [COMPANION_PACKAGES] globally, unless it is already there. */
    private fun ensureCompanion(context: Context, agent: AcpAgent, onProgress: (String) -> Unit) {
        val companion = COMPANION_PACKAGES[agent.id] ?: return
        val installed = File(
            ShellPaths.rootfsDir(context),
            "${NODE_ROOT.trimStart('/')}/lib/node_modules/$companion/package.json",
        )
        if (installed.isFile) return
        onProgress("Installing $companion")
        val result = runCapture(
            context,
            "npm install -g $companion",
            pathPrefix = NODE_BIN,
            timeoutSeconds = 600,
        )
        check(result.first == 0) { "Could not install $companion: ${result.second.takeLast(500)}" }
    }

    /** Runs one shell command inside the guest, capturing exit code and combined output. */
    fun runCapture(
        context: Context,
        shellCommand: String,
        pathPrefix: String? = null,
        timeoutSeconds: Long = 600,
        extraEnv: Map<String, String> = emptyMap(),
    ): Pair<Int, String> {
        val command = ProotCommand.buildCommand(
            context = context,
            workspace = null,
            shellCommand = shellCommand,
            pathPrefix = pathPrefix,
            extraEnv = extraEnv,
        )
        // Output goes to a file rather than a pipe. Android closes a child's pipe as soon as the
        // child exits, discarding whatever a reader thread had not consumed yet, so a failing
        // npm run used to be logged with no output at all. apt and npm report errors on stderr,
        // hence the merge. The environment must carry LD_LIBRARY_PATH etc., or the linker cannot
        // even load libproot.so.
        val log = File.createTempFile("run-", ".log", context.cacheDir)
        try {
            val process = ProcessBuilder(command)
                .apply {
                    environment().clear()
                    environment().putAll(ProotCommand.hostEnvironment(context))
                }
                .redirectErrorStream(true)
                .redirectOutput(log)
                .start()
            // Nothing is ever typed into these commands. Closing stdin makes anything that reads
            // it (an agent that ignores `--version` and starts serving ACP) see EOF instead of
            // hanging until the timeout.
            runCatching { process.outputStream.close() }

            val finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                return 124 to log.readText()
            }
            return process.exitValue() to log.readText()
        } finally {
            log.delete()
        }
    }
}
