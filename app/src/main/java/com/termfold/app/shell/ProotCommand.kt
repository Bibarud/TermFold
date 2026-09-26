package com.termfold.app.shell

import android.content.Context
import java.io.File

/**
 * Builds the PRoot command line that starts a process inside the bundled Ubuntu rootfs.
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

            // Ubuntu's apt uses System V shared memory for its lockless download methods.
            "--sysvipc",

            // Kill the whole guest process tree when the session ends, so an interrupted agent
            // does not leave orphaned processes behind on a phone.
            "--kill-on-exit",

            // The rootfs archive carries empty device directories, so these come from the host.
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",

            // Ubuntu's apt refuses to run without a writable /dev/shm, and Android's is owned by
            // the system. /dev/shm does not exist in the rootfs, so this bind also creates it.
            "-b", ShellPaths.shmDir(context).absolutePath + ":/dev/shm",

            // DNS and the hosts table have to come from the host: the guest has no resolver of its
            // own, and /etc/hosts is what makes `localhost` resolve for local servers.
            "-b", ShellPaths.resolvConf(context).absolutePath + ":/etc/resolv.conf",
            "-b", ShellPaths.hostsFile(context).absolutePath + ":/etc/hosts",
        )

        // Android refuses hard links in app storage, which Ubuntu's tools need. They are now
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

        // Ubuntu's native locale is C.UTF-8, which exists in the image. Leaving LANG unset makes
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
            // Android still reports some zones by their legacy names (Asia/Calcutta), which Ubuntu
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
