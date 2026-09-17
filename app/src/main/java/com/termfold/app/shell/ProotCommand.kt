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
 * The guest keeps its own filesystem: a picked project folder is bind-mounted *into* it at
 * [ShellConfig.WORKSPACE] rather than being used as the root.
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

            // PRoot's seccomp-accelerated syscall path: this app cannot use PRoot's *own* seccomp
            // filter, because the tracee is already constrained by Android's.
            //
            // But PRoot must still be allowed to observe `PTRACE_EVENT_SECCOMP` stops, because
            // that is how it recognises that a SIGSYS came from Android's outer filter rather than
            // from the traced program. Android blocks `rename(2)` outright, and PRoot's whole
            // remedy for that is a handler that rewrites it into `renameat(2)` — which only runs
            // when PRoot knows the trap is its to interpret. Setting this to 1 disables that
            // detection and every `rename` in the guest then fails with ENOSYS, which breaks apt:
            // "Problem renaming the file /var/cache/apt/pkgcache.bin...".
            put("PROOT_NO_SECCOMP", "0")
        }
    }

    /**
     * The argv handed to the PTY child.
     *
     * [argv] is the program to run *inside* the guest, so callers pass guest paths such as
     * `/bin/bash`. [workspace] is a host path and is bind-mounted into the guest at
     * [guestMountPath], which callers normally derive from the folder's own name.
     */
    fun build(
        context: Context,
        workspace: String?,
        argv: List<String>,
        guestCwd: String? = null,
        guestMountPath: String = ShellConfig.WORKSPACE,
    ): List<String> {
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

            // Make `link(2)` succeed by faking hard links as symlinks.
            //
            // Android does not let an untrusted app create hard links inside its own data
            // directory, so the kernel answers `link()` with EPERM. That breaks dpkg outright:
            // installing anything fails with
            // "error creating new backup file '/var/lib/dpkg/status-old': Permission denied".
            // PRoot's extension is the documented fix and is what `proot-distro` enables.
            "--link2symlink",

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

        if (workspace != null && File(workspace).isDirectory) {
            command += listOf("-b", "$workspace:$guestMountPath")
        }

        command += listOf(
            // A clean environment, then exactly the variables the guest needs. Inheriting the
            // app's environment would leak Android paths into the guest.
            "/usr/bin/env", "-i",
        )
        guestEnvironment().forEach { (key, value) -> command += "$key=$value" }
        command += argv
        return command
    }

    /** The argv for running a one-shot command in the guest, used for maintenance actions. */
    fun buildCommand(
        context: Context,
        workspace: String?,
        shellCommand: String,
        guestCwd: String? = null,
    ): List<String> = build(
        context = context,
        workspace = workspace,
        argv = listOf(ShellConfig.GUEST_SHELL, "--login", "-c", shellCommand),
        guestCwd = guestCwd,
    )

    /**
     * The environment the guest sees.
     *
     * `TERMUX_*` variables are deliberately absent: Termux's own binaries read them to locate
     * their prefix, and the guest must not be told it is running under Termux.
     */
    fun guestEnvironment(): Map<String, String> = buildMap {
        put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
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
        put("TZ", java.util.TimeZone.getDefault().id)

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

    /** Where the CA bundle is installed inside the guest. */
    const val CA_BUNDLE_PATH = "/etc/ssl/certs/ca-certificates.crt"
}
