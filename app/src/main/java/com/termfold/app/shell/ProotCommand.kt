package com.termfold.app.shell

import android.content.Context
import java.io.File

/**
 * Builds the PRoot command line that starts a process inside the bundled Debian rootfs.
 *
 * PRoot has to be told several things it cannot work out for itself:
 *
 *  - where its loader is (`PROOT_LOADER`), because the loader is what lets a program be executed
 *    out of `nativeLibraryDir` at all;
 *  - where its own shared libraries are (`LD_LIBRARY_PATH`), since the platform only puts them
 *    there and does not add that directory to the search path itself;
 *  - which host paths to expose inside the guest, because the rootfs ships empty `/dev`, `/proc`
 *    and `/sys` directories.
 *
 * Projects live inside the guest (~/projects), so a session only needs its working directory:
 * nothing from Android's storage is bound in.
 *
 * Every executable referenced here lives in `nativeLibraryDir` and is invoked under its `lib*.so`
 * name. Nothing is copied into app storage, because nothing there may be executed.
 */
object ProotCommand {

    /** Absolute path of a bundled executable. */
    private fun tool(context: Context, name: String): String =
        File(context.applicationInfo.nativeLibraryDir, name).absolutePath

    /**
     * Host environment for the PRoot process itself.
     *
     * Not to be confused with the guest environment, which [guestEnvironment] builds and which is
     * passed as separate `env` arguments.
     */
    fun hostEnvironment(context: Context): Map<String, String> {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        return buildMap {
            put("PROOT_LOADER", tool(context, ShellConfig.NATIVE_LOADER))
            put("PROOT_TMP_DIR", ShellPaths.tempDir(context).absolutePath)

            // The platform stages its libraries in nativeLibraryDir but does not add it to the
            // linker's search path, so PRoot's own dependencies are found only if told about it.
            put("LD_LIBRARY_PATH", nativeDir)

            // PRoot probes /data/local/tests for an f2fs case-sensitivity bug. An app cannot read
            // that path; the failed probe sends PRoot down a slow path where forked children can
            // die with "can't fork". App-private storage is not f2fs, so the probe is pointless.
            put("PROOT_F2FS_WORKAROUND", "0")

            // PROOT_NO_SECCOMP is deliberately NOT set, not even to "0": PRoot only tests
            // `getenv("PROOT_NO_SECCOMP") == NULL`, so any value disables its seccomp mode.
            // That mode is what rewrites syscalls Android's filter blocks (notably `rename(2)`)
            // into allowed ones; without it every `rename` in the guest fails with ENOSYS and
            // apt breaks with "Problem renaming the file /var/cache/apt/pkgcache.bin...".
        }
    }

    /**
     * The argv handed to the PTY child.
     *
     * [argv] is the program to run *inside* the guest, so callers pass guest paths such as
     * `/bin/bash`, and [guestCwd] is a guest directory (a project's, or home by default).
     */
    fun build(
        context: Context,
        argv: List<String>,
        guestCwd: String? = null,
        pathPrefix: String? = null,
        extraEnv: Map<String, String> = emptyMap(),
    ): List<String> {
        // The resolver is bound into the guest, so refresh it for whichever network is current.
        runCatching { ShellRuntime.writeHostFiles(context) }

        val command = mutableListOf(
            tool(context, ShellConfig.NATIVE_PROOT),
            "-r", ShellPaths.rootfsDir(context).absolutePath,

            // Report the host UID/GID as root so apt and dpkg work without sudo, and so package
            // maintainer scripts that chown or chmod behave. This is PRoot faking identity, not a
            // privilege escalation.
            "-0",

            // PRoot's scratch space; its compiled-in default is a Termux path.
            "-w", guestCwd ?: ShellConfig.GUEST_HOME,

            // Debian's apt uses System V shared memory for its lockless download methods.
            "--sysvipc",

            // Kill the whole guest process tree when the session ends, so an interrupted agent
            // does not leave orphaned processes behind on a phone.
            "--kill-on-exit",

            // The rootfs archive carries empty device directories, so these come from the host.
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",

            // Debian's apt refuses to run without a writable /dev/shm, and Android's is owned by
            // the system. /dev/shm does not exist in the rootfs, so this bind also creates it.
            "-b", ShellPaths.shmDir(context).absolutePath + ":/dev/shm",

            // DNS and the hosts table have to come from the host: the guest has no resolver of its
            // own, and /etc/hosts is what makes `localhost` resolve for local servers.
            "-b", ShellPaths.resolvConf(context).absolutePath + ":/etc/resolv.conf",
            "-b", ShellPaths.hostsFile(context).absolutePath + ":/etc/hosts",
        )

        // Stand-ins for the /proc files Android hides from apps. Programs that work out when a
        // process started (the native Codex CLI records its background server that way) read the
        // boot time from /proc/stat; others need /proc/version, /proc/uptime or the watch limit.
        fakeProcFiles(context).forEach { (fake, path) -> command += listOf("-b", "${fake.absolutePath}:$path") }

        // Android refuses hard links in app storage, which Debian's tools need. They are now
        // handled by the compatibility library preloaded into every guest program (GuestCompat):
        // a refused link becomes a real copy. PRoot's own emulation (--link2symlink) is only
        // kept until the one-time migration has turned its fragile fake links into real files.
        if (!GuestCompat.migrated(ShellPaths.rootfsDir(context))) {
            command.add(command.indexOf("--sysvipc") + 1, "--link2symlink")
        }

        command += listOf(
            // A clean environment, then exactly the variables the guest needs. Inheriting the
            // app's environment would leak Android paths into the guest.
            "/usr/bin/env", "-i",
        )
        guestEnvironment(ShellPaths.rootfsDir(context)).forEach { (key, value) ->
            // Some guests (Node-based npx agents) bring their own toolchain directory that has
            // to sit ahead of the system one.
            if (key == "PATH" && !pathPrefix.isNullOrBlank()) {
                command += "PATH=$pathPrefix:$value"
            } else {
                command += "$key=$value"
            }
        }
        // Agent-specific variables (uv's cache locations, registry-required settings) come
        // after the base set so they win on duplicate keys; a PATH override still gets the
        // toolchain prefix so `npx` etc. stay reachable.
        extraEnv.forEach { (key, value) ->
            command += if (key == "PATH" && !pathPrefix.isNullOrBlank()) {
                "PATH=$pathPrefix:$value"
            } else {
                "$key=$value"
            }
        }
        command += argv
        return command
    }

    /** Which of the files below this device lets an app read, worked out once. */
    private val hiddenProc: List<String> by lazy {
        FAKE_PROC.filter { path -> runCatching { File(path).readBytes() }.isFailure }
    }

    private val FAKE_PROC = listOf(
        "/proc/version",
        "/proc/loadavg",
        "/proc/stat",
        "/proc/uptime",
        "/proc/vmstat",
        "/proc/sys/kernel/cap_last_cap",
        "/proc/sys/fs/inotify/max_user_watches",
    )

    /**
     * Plausible contents for the /proc files this device hides, written to the scratch folder,
     * paired with the guest path each one stands in for. Only hidden files are replaced; the
     * uptime is refreshed on every start.
     *
     * The boot time in /proc/stat is kept steady between sessions: a process's start time is that
     * boot time plus a per-process offset, and a program that stored it earlier compares it with
     * what it reads later, so a second of drift would make it treat its own process as gone.
     */
    private fun fakeProcFiles(context: Context): List<Pair<File, String>> {
        if (hiddenProc.isEmpty()) return emptyList()
        val dir = File(ShellPaths.tempDir(context), "proc").apply { mkdirs() }
        val cpus = Runtime.getRuntime().availableProcessors()
        val uptime = android.os.SystemClock.elapsedRealtime() / 1000.0
        return hiddenProc.mapNotNull { path ->
            val file = File(dir, path.removePrefix("/proc/").replace('/', '_'))
            val content = when (path) {
                "/proc/version" -> "Linux version ${System.getProperty("os.version")} (termfold@android) (gcc) #1 SMP PREEMPT\n"
                "/proc/loadavg" -> "0.40 0.35 0.30 1/400 1000\n"
                "/proc/stat" -> buildString {
                    append("cpu  1000 0 1000 100000 100 0 10 0 0 0\n")
                    for (i in 0 until cpus) append("cpu$i 100 0 100 10000 10 0 1 0 0 0\n")
                    val boot = System.currentTimeMillis() / 1000 - uptime.toLong()
                    val earlier = runCatching {
                        file.readLines().firstOrNull { it.startsWith("btime ") }?.removePrefix("btime ")?.trim()?.toLong()
                    }.getOrNull()
                    // Same boot (within a couple of seconds of rounding): keep the stored value.
                    append("intr 0\nctxt 0\nbtime ${if (earlier != null && kotlin.math.abs(earlier - boot) <= 2) earlier else boot}\n")
                    append("processes 1000\nprocs_running 1\nprocs_blocked 0\nsoftirq 0\n")
                }
                "/proc/uptime" -> "%.2f %.2f\n".format(java.util.Locale.ROOT, uptime, uptime * cpus * 0.8)
                "/proc/vmstat" -> "nr_free_pages 100000\nnr_inactive_anon 10000\nnr_active_anon 10000\npgpgin 0\npgpgout 0\npswpin 0\npswpout 0\npgfault 0\npgmajfault 0\n"
                "/proc/sys/kernel/cap_last_cap" -> "40\n"
                "/proc/sys/fs/inotify/max_user_watches" -> "524288\n"
                else -> return@mapNotNull null
            }
            runCatching {
                if (!file.isFile || file.readText() != content) file.writeText(content)
            }.getOrNull() ?: return@mapNotNull null
            file to path
        }
    }

    /** The argv for running a one-shot command in the guest, used for maintenance actions. */
    fun buildCommand(
        context: Context,
        shellCommand: String,
        guestCwd: String? = null,
        pathPrefix: String? = null,
        extraEnv: Map<String, String> = emptyMap(),
    ): List<String> = build(
        context = context,
        argv = listOf(ShellConfig.GUEST_SHELL, "--login", "-c", shellCommand),
        guestCwd = guestCwd,
        pathPrefix = pathPrefix,
        extraEnv = extraEnv,
    )

    /**
     * The environment the guest sees.
     *
     * `TERMUX_*` variables are deliberately absent: Termux's own binaries read them to locate
     * their prefix, and the guest must not be told it is running under Termux.
     */
    fun guestEnvironment(rootfs: File? = null): Map<String, String> = buildMap {
        // /opt/node/bin first: Node and every `npm install -g` CLI (claude, codex, ...) live there,
        // ahead of the on-demand installer shims in /usr/local/bin that stand in until then.
        put("PATH", "/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        put("HOME", ShellConfig.GUEST_HOME)
        put("PWD", ShellConfig.GUEST_HOME)

        // xterm-256color matches what TerminalView renders. TUIs such as OpenCode and Pi probe
        // for it; without it they fall back to a dumb terminal and lose colour and cursor keys.
        put("TERM", "xterm-256color")
        put("COLORTERM", "truecolor")

        // Debian's native locale is C.UTF-8, which exists in the image. Leaving LANG unset makes
        // apt and Python emit warnings and mangle non-ASCII output.
        put("LANG", "C.UTF-8")
        put("LC_ALL", "C.UTF-8")
        put("TZ", guestTimeZone(rootfs))

        // The certificate authorities the guest uses for https.
        //
        // The base image ships the archive keyring but no CA bundle, so without these every
        // https:// request fails with "No system certificates available" — which is most of what
        // pip, npm, git and the agents need. Each variable is set because the tools disagree about
        // which one to read: OpenSSL and Python use SSL_CERT_FILE, curl uses CURL_CA_BUNDLE, and
        // Node additionally honours NODE_EXTRA_CA_CERTS.
        put("SSL_CERT_FILE", CA_BUNDLE_PATH)
        put("SSL_CERT_DIR", "/etc/ssl/certs")
        put("CURL_CA_BUNDLE", CA_BUNDLE_PATH)
        put("REQUESTS_CA_BUNDLE", CA_BUNDLE_PATH)
        put("NODE_EXTRA_CA_CERTS", CA_BUNDLE_PATH)
        put("GIT_SSL_CAINFO", CA_BUNDLE_PATH)

        // Debian front ends must never stop to ask a question: there is no tty prompt to answer.
        put("DEBIAN_FRONTEND", "noninteractive")

        // npm, pip and cargo install under $HOME, which is inside the persistent rootfs.
        put("TMPDIR", "/tmp")
        put("USER", "root")
        put("SHELL", ShellConfig.GUEST_SHELL)

        // PRoot needs its own scratch directory inside the guest too.
        put("PROOT_TMP_DIR", "/tmp")
    }

    /**
     * The host's time zone in a form the guest can resolve.
     *
     * The base image ships no tzdata, and glibc silently falls back to UTC for an unknown zone
     * name, so file times and `date` would be off by the local offset. The zone name is used when
     * the guest has it (after `apt install tzdata`, which also keeps DST rules); otherwise a POSIX
     * fixed-offset string is used, whose sign convention is the inverse of UTC offsets.
     */
    internal fun guestTimeZone(rootfs: File?, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
        if (rootfs != null) {
            // Android still reports some zones by their legacy names (Asia/Calcutta), which Debian
            // 24.04 only ships in tzdata-legacy. An equivalent current name (Asia/Kolkata) is
            // there, and a real zone name is what Node, Python and the rest actually understand;
            // the POSIX fallback below leaves Node-based tools such as Claude Code on UTC.
            val candidates = buildList {
                add(zone.id)
                runCatching {
                    val count = android.icu.util.TimeZone.countEquivalentIDs(zone.id)
                    for (i in 0 until count) add(android.icu.util.TimeZone.getEquivalentID(zone.id, i))
                }
            }
            candidates.firstOrNull { File(rootfs, "usr/share/zoneinfo/$it").isFile }?.let { return it }
        }
        val totalMinutes = zone.getOffset(System.currentTimeMillis()) / 60_000
        val sign = if (totalMinutes >= 0) "-" else "+"
        val abs = kotlin.math.abs(totalMinutes)
        val hhmm = "%02d:%02d".format(abs / 60, abs % 60)
        val name = (if (totalMinutes >= 0) "+" else "-") + "%02d%02d".format(abs / 60, abs % 60)
        return "<$name>$sign$hhmm"
    }

    /** Where the CA bundle is installed inside the guest. */
    const val CA_BUNDLE_PATH = "/etc/ssl/certs/ca-certificates.crt"
}
