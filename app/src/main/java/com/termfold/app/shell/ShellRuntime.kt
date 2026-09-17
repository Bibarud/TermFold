package com.termfold.app.shell

import android.content.Context
import android.content.res.AssetManager
import android.net.ConnectivityManager
import android.util.Log
import java.io.File
/**
 * Owns the bundled Linux environment: unpacking the Ubuntu root filesystem and writing the few
 * files the guest cannot work out for itself.
 *
 * Everything here happens once. After the first successful run the rootfs lives in the app's
 * private storage and survives restarts and app updates, so anything installed inside it with apt
 * stays installed.
 */
object ShellRuntime {

    private const val TAG = "ShellRuntime"

    /** Bumped when the bundled image changes, so a future release can tell it must re-unpack. */
    private const val MARKER = "ubuntu-24.04.5"

    /**
     * The `ca-certificates` package this app unpacks into the guest.
     *
     * The Ubuntu base image ships the archive keyring but no CA bundle, so every https:// request
     * from inside the guest fails with "No system certificates available" and apt cannot reach
     * archive.ubuntu.com over TLS. Installing the package through apt is not an option, because
     * that is the very thing that needs working TLS.
     *
     * So the bundle is placed directly: it is a set of PEM files plus the hash-named symlinks
     * `openssl` looks up, and the keyring is left exactly as the image shipped it.
     */
    private const val CA_BUNDLE = "ca-certificates.crt"

    /** Thrown when the host CPU has no matching Ubuntu image. */
    class UnsupportedCpu(abi: String) :
        IllegalStateException("No bundled Linux image for CPU architecture \"$abi\".")

    /**
     * Makes the environment ready to run, doing the expensive work at most once.
     *
     * [onProgress] is called from the calling thread with a short step name and a 0..1 fraction.
     */
    fun provision(context: Context, onProgress: (String, Float) -> Unit = { _, _ -> }) {
        // The architecture of the running process, which is the one the rootfs must match.
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val supported = ShellConfig.rootfsAbi(abi) ?: throw UnsupportedCpu(abi)

        if (isReady(context)) return

        onProgress("Writing network config", 0.05f)
        writeHostFiles(context)

        val rootfs = ShellPaths.rootfsDir(context)
        if (!File(rootfs, "usr/bin/bash").isFile) {
            onProgress("Unpacking Ubuntu", 0.10f)
            extractRootfs(context, supported, rootfs)
        }

        onProgress("Finishing", 0.96f)
        postInstall(context, rootfs)
        fixGroupDatabase(rootfs)

        onProgress("Ready", 1f)
        Log.i(TAG, "Linux ready at ${rootfs.absolutePath}")
    }

    fun isReady(context: Context): Boolean = ShellPaths.isReady(context)

    /**
     * Unpacks the Ubuntu image into the app's private storage.
     *
     * The extraction is deliberately done in-process ([TarExtractor]) rather than by shelling out
     * to the bundled busybox tar. Android's seccomp policy makes the raw `fork` syscall fail with
     * `ENOSYS` for app processes — apps are expected to use `clone` — and BusyBox's tar forks.
     * A JVM implementation has no such problem, and it also means the rootfs is in place before
     * any bundled executable has to prove it runs.
     */
    private fun extractRootfs(context: Context, abi: String, rootfs: File) {
        // A scratch tree beside the final rootfs, so publishing it afterwards is a rename rather
        // than a copy of the whole tree.
        val staging = File(rootfs.parentFile, "${ShellConfig.ROOTFS_DIR}-staging")
        staging.deleteRecursively()
        if (!staging.mkdirs()) {
            error("Cannot create ${staging.absolutePath} to unpack Linux into.")
        }

        try {
            // ACCESS_STREAMING keeps the 30 MB image out of memory and streams it as it is read.
            context.assets.open(ShellConfig.rootfsAsset(abi), AssetManager.ACCESS_STREAMING)
                .use { archive ->
                    TarExtractor.extract(archive, staging) { warning -> Log.w(TAG, warning) }
                }
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }

        if (!File(staging, "usr/bin/bash").isFile) {
            staging.deleteRecursively()
            error("Unpacked image has no /usr/bin/bash, so the archive is not a usable rootfs.")
        }

        rootfs.deleteRecursively()
        if (!staging.renameTo(rootfs)) {
            error("Could not move the unpacked rootfs into ${rootfs.absolutePath}")
        }
        Log.i(TAG, "Unpacked ${ShellConfig.rootfsAsset(abi)} into ${rootfs.absolutePath}")
    }

    /**
     * Writes the files that must come from the host.
     *
     * The guest has no resolver of its own, and PRoot binds these over the rootfs copies, so the
     * archive's own versions are never used.
     */
    private fun writeHostFiles(context: Context) {
        val servers = mutableListOf<String>()
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm?.activeNetwork?.let { network ->
                cm.getLinkProperties(network)?.dnsServers?.forEach { address ->
                    address.hostAddress?.let { servers += it }
                }
            }
        }
        if (servers.isEmpty()) servers += listOf("8.8.8.8", "1.1.1.1")

        ShellPaths.resolvConf(context).writeText(
            servers.joinToString("\n") { "nameserver $it" } + "\n"
        )
        ShellPaths.hostsFile(context).writeText(
            "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n"
        )
    }

    /**
     * Small fixes applied after extraction.
     *
     * Every one of these is something a normal Ubuntu install gets from its packaging or from a
     * boot, and which a base image that was never booted does not have.
     */
    private fun postInstall(context: Context, rootfs: File) {
        // dpkg and apt expect these before either runs, and /dev/shm is where the bind mounts.
        listOf(
            "var/lib/apt/lists/partial",
            "var/cache/apt/archives/partial",
            "var/log/apt",
            "dev/shm",
            "run/lock",
        ).forEach { File(rootfs, it).mkdirs() }

        // The image ships an http:// mirror and no CA bundle. That combination is deliberate here:
        // apt over http is still integrity- and origin-checked through the archive keyring the
        // image does carry, so it works on first boot, and the CA bundle this app adds separately
        // is what lets pip, npm, git and the agents use https.
        //
        // The mirror is left exactly as shipped for that reason; switching it to https before the
        // bundle is installed is what makes apt fail with "No system certificates available".
        runCatching {
            val sources = File(rootfs, "etc/apt/sources.list.d/ubuntu.sources")
            if (sources.isFile) {
                val text = sources.readText()
                if (text.contains("https://archive.ubuntu.com")) {
                    Log.w(TAG, "apt sources unexpectedly use https; leaving them alone")
                }
            }
        }.onFailure { Log.w(TAG, "Could not inspect apt sources", it) }

        installCaCertificates(context, rootfs)

        ShellPaths.rootfsMarker(context).writeText(MARKER)
    }

    /**
     * Installs a CA bundle into the guest.
     *
     * The Ubuntu base image ships the archive keyring but no certificate authorities, so every
     * https:// request from inside the guest fails with "No system certificates available" — which
     * breaks pip, npm, git and curl, and therefore every agent this app exists to run.
     *
     * `ca-certificates` cannot be installed with apt, because that is exactly the tool that needs
     * working TLS. The bundle is unpacked from the asset directly instead.
     *
     * The bundle alone is enough: the tools that matter read `SSL_CERT_FILE` and its equivalents,
     * which [ProotCommand.guestEnvironment] points here. The hash-named symlinks OpenSSL uses when
     * it is pointed at a whole directory are deliberately not generated, because they depend on
     * the exact certificate set and would silently go stale when the bundle is refreshed.
     */
    private fun installCaCertificates(context: Context, rootfs: File) {
        val certsDir = File(rootfs, "etc/ssl/certs").apply { mkdirs() }
        val bundle = File(certsDir, CA_BUNDLE)

        runCatching {
            context.assets.open(CA_BUNDLE).use { input ->
                bundle.outputStream().use { output -> input.copyTo(output) }
            }
            Log.i(TAG, "Installed CA bundle (${bundle.length()} bytes)")
        }.onFailure {
            // Not fatal: apt works over http regardless, only https-dependent tools are affected.
            Log.w(TAG, "Could not install the CA bundle", it)
        }
    }

    /**
     * Adds the host's supplementary group IDs to the guest's group database.
     *
     * The app process carries Android's own group IDs — `inet`, `sdcard_rw`, plus a per-install
     * range derived from the app's UID. PRoot passes them through to the guest, and every command
     * that resolves group names (`id`, `ls -l`, and so every shell prompt) prints
     * "groups: cannot find name for group ID 3003" on the guest's stderr.
     *
     * The IDs are read from this process rather than hardcoded: the high ones are derived from the
     * app's UID, so they differ per install and per device. This runs at provisioning time, which
     * is exactly once per install, so the list stays correct.
     */
    private fun fixGroupDatabase(rootfs: File) {
        runCatching {
            val ids = hostGroupIds()
            if (ids.isEmpty()) return

            listOf("etc/group", "etc/group-").forEach { name ->
                val file = File(rootfs, name)
                if (!file.isFile) return@forEach

                // Drop any entries a previous attempt added, then re-add the current set. That
                // keeps the file correct when provisioning is retried, and when the app's UID —
                // and therefore its supplementary group range — changes between installs.
                val lines = file.readLines()
                    .filterNot { it.startsWith(GROUP_PREFIX) }
                    .toMutableList()
                // Format is `name:password:gid:members`; the password field must be present even
                // though it is unused, or the gid lands in the wrong column and never resolves.
                ids.forEach { id: Int -> lines += "$GROUP_PREFIX$id:x:$id:" }
                file.writeText(lines.joinToString("\n") + "\n")
            }
            Log.i(TAG, "Added ${ids.size} host group IDs to the guest")
        }.onFailure { Log.w(TAG, "Could not extend the group database", it) }
    }

    /**
     * The supplementary group IDs of this process, read from `/proc/self/status`.
     *
     * There is no API for these: `Os.getgroups` exists but is not exposed, and the IDs cannot be
     * hardcoded because the high ones are derived from the app's UID, so they change on every
     * install. The kernel reports them in the `Groups:` line of the process status file.
     */
    private fun hostGroupIds(): Set<Int> = runCatching {
        val status = File("/proc/self/status")
        if (!status.isFile) return emptySet()
        val line = status.readLines().firstOrNull { it.startsWith("Groups:") }
            ?: return emptySet()
        line.removePrefix("Groups:").trim()
            .split(' ')
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()
    }.getOrDefault(emptySet())

    /** Marks the placeholder group entries this app adds, so they can be replaced wholesale. */
    private const val GROUP_PREFIX = "termfold"

    private fun nativeDir(context: Context): String = context.applicationInfo.nativeLibraryDir

    /** The distribution this app bundles. */
    const val FLAVOUR = "Ubuntu 24.04 LTS"
}
