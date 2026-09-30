package com.termfold.app.shell

import android.content.Context
import android.util.Log
import com.termfold.app.acp.AcpSessions
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Moves an install that was made with Ubuntu (every version before 2.2) over to Debian, keeping
 * what belongs to the user and removing Ubuntu afterwards.
 *
 * What is kept is moved, not copied: directories are renamed from the old tree into the new one,
 * which is instant, needs no extra space, and cannot leave two diverging copies. That covers the
 * home directory (projects, agent sign-ins, shell history, git config), `/opt` (Node.js and every
 * agent CLI installed with npm, which are plain glibc programs and run the same on Debian), `/home`,
 * `/srv` and the scripts in `/usr/local/bin`. What is not kept is the operating system itself: the
 * packages installed with apt belong to Ubuntu. Their names are recorded, and the first shell
 * session installs them again from Debian ([ShellSetup], `termfold-setup.sh`).
 *
 * The order is chosen so that a crash or a killed app at any point can be finished later from what
 * is on disk, and so that nothing the user owns is ever only in a place that gets deleted:
 *
 *  1. Debian is unpacked and prepared in a staging directory. The running system is untouched, so
 *     a failure here (no space, a bad image) just discards the staging directory.
 *  2. The Ubuntu tree is renamed out of the way. From here on there is no `rootfs`.
 *  3. The user's directories are moved from the old tree into the staging tree, entry by entry;
 *     each rename is atomic and repeating the step skips what has already moved.
 *  4. The staging tree is renamed to `rootfs`. This is the moment Debian takes over.
 *  5. The old Ubuntu tree, now holding only what was not worth keeping, is deleted.
 *
 * [resumeInterrupted] recognises each of these states by which directories exist.
 */
object DistroMigration {

    private const val TAG = "DistroMigration"

    /** Directories of the old system whose contents are moved into the new one. */
    private val DATA_DIRS = mapOf(
        // Caches are rebuilt on demand, and pip's or npm's can be hundreds of MB.
        "root" to setOf(".cache"),
        // The script installed here is rewritten by the app, not carried over.
        "opt" to setOf("termfold"),
        "home" to emptySet(),
        "srv" to emptySet(),
        "usr/local/bin" to emptySet(),
    )

    /** Free space the migration needs: the unpacked image plus room to work. */
    const val REQUIRED_BYTES = 400L * 1024 * 1024

    /** The apt packages the user installed on Ubuntu, for the first Debian session to restore. */
    const val PACKAGE_LIST = "var/lib/termfold/migrated-packages"

    fun freeBytes(context: Context): Long = runCatching { context.filesDir.usableSpace }.getOrDefault(Long.MAX_VALUE)

    /**
     * Migrates the current Ubuntu install to Debian. Everything is reported through [onProgress]
     * as a short step name and a 0..1 fraction. Throws on failure; the old system is then either
     * untouched or recoverable by [resumeInterrupted], never half-deleted.
     */
    fun migrate(context: Context, onProgress: (String, Float) -> Unit) =
        // The same lock as [ShellRuntime.provision], so the background refresh of an existing
        // install cannot run while the tree is being moved.
        synchronized(ShellRuntime) { migrateLocked(context, onProgress) }

    private fun migrateLocked(context: Context, onProgress: (String, Float) -> Unit) {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val supported = ShellConfig.rootfsAbi(abi) ?: throw ShellRuntime.UnsupportedCpu(abi)
        check(ShellRuntime.isLegacyUbuntu(context)) { "This environment is not an Ubuntu install." }

        val rootfs = ShellPaths.rootfsDir(context)
        val staging = ShellPaths.stagingDir(context)
        val old = ShellPaths.oldRootfsDir(context)

        val free = freeBytes(context)
        if (free < REQUIRED_BYTES) {
            throw IOException("Not enough free space: ${free / (1024 * 1024)} MB free, ${REQUIRED_BYTES / (1024 * 1024)} MB needed.")
        }

        onProgress("Stopping running sessions", 0.02f)
        stopGuestProcesses()

        // Hidden fake hard links from old versions become real files first, so moving directories
        // around cannot separate a name from its data.
        onProgress("Checking files", 0.04f)
        GuestCompat.migrateFakeLinks(rootfs)

        // 1. Debian, prepared next to the running system.
        onProgress("Unpacking Debian", 0.08f)
        deleteTree(staging)
        deleteTree(old)
        check(staging.mkdirs()) { "Cannot create ${staging.absolutePath}" }
        try {
            ShellRuntime.unpackImage(context, supported, staging) { onProgress("Unpacking Debian", 0.08f + 0.40f * it) }
            check(File(staging, "usr/bin/bash").isFile) { "The bundled Debian image is not usable." }
            onProgress("Preparing Debian", 0.50f)
            ShellRuntime.finishImage(context, staging, supported)
        } catch (error: Throwable) {
            deleteTree(staging)
            throw error
        }

        // 2. Ubuntu steps aside.
        onProgress("Moving Ubuntu aside", 0.58f)
        if (!rootfs.renameTo(old)) {
            deleteTree(staging)
            throw IOException("Could not move the Ubuntu system aside.")
        }

        // 3 to 5. A failure from here on leaves the old tree in place for [resumeInterrupted].
        try {
            finish(context, staging, old, rootfs, onProgress, from = 0.60f)
        } catch (error: Throwable) {
            Log.e(TAG, "Migration did not finish; it will be resumed", error)
            throw error
        }
        Log.i(TAG, "Migrated to Debian")
    }

    /**
     * Finishes a migration that was cut off, based on which directories exist. Does nothing when
     * there is nothing to finish, which is the case on almost every launch. Called with the
     * [ShellRuntime] lock held, from [ShellRuntime.provision].
     */
    fun resumeInterrupted(context: Context, abi: String, onProgress: (String, Float) -> Unit) {
        val rootfs = ShellPaths.rootfsDir(context)
        val staging = ShellPaths.stagingDir(context)
        val old = ShellPaths.oldRootfsDir(context)
        if (!exists(old)) {
            // A staging tree next to a working system is left over from an unpacking that was
            // cut off; it is useless, and Debian is unpacked again when needed.
            if (exists(staging) && exists(rootfs) && ShellRuntime.isReady(context)) deleteTree(staging)
            return
        }
        when {
            // Debian already took over; only Ubuntu's leftovers remain.
            exists(rootfs) && !ShellRuntime.isLegacyUbuntu(context) && ShellRuntime.isReady(context) -> {
                onProgress("Removing Ubuntu", 0.90f)
                deleteTree(old) { onProgress("Removing Ubuntu", removalFraction(it)) }
            }
            // Between stepping Ubuntu aside and Debian taking over.
            !exists(rootfs) && exists(staging) -> {
                onProgress("Finishing the switch to Debian", 0.60f)
                finish(context, staging, old, rootfs, onProgress, from = 0.60f)
            }
            // Ubuntu was moved aside but Debian never got prepared: put it back.
            !exists(rootfs) -> {
                if (old.renameTo(rootfs)) Log.w(TAG, "Restored the Ubuntu system") else Log.e(TAG, "Could not restore Ubuntu")
            }
            // Both trees and a working Ubuntu: not something this code produces. Keep everything.
            else -> Log.w(TAG, "Unexpected state: leaving ${old.name} in place")
        }
    }

    private fun finish(
        context: Context,
        staging: File,
        old: File,
        rootfs: File,
        onProgress: (String, Float) -> Unit,
        from: Float,
    ) {
        // 3. The user's own files.
        onProgress("Moving your projects and files", from)
        val entries = DATA_DIRS.entries.toList()
        entries.forEachIndexed { index, (dir, skip) ->
            moveEntries(File(old, dir), File(staging, dir), skip)
            onProgress("Moving your projects and files", from + 0.15f * (index + 1) / entries.size)
        }
        runCatching { recordPackages(old, staging) }
            .onFailure { Log.w(TAG, "Could not record the installed packages", it) }

        // 4. Debian takes over.
        onProgress("Switching to Debian", 0.78f)
        check(staging.renameTo(rootfs)) { "Could not put Debian in place." }

        // 5. Ubuntu goes.
        onProgress("Removing Ubuntu", 0.80f)
        deleteTree(old) { onProgress("Removing Ubuntu", removalFraction(it)) }
        onProgress("Ready", 1f)
    }

    /** Progress while deleting: the size of the old tree is unknown, so ease towards the end. */
    private fun removalFraction(filesRemoved: Int): Float =
        0.80f + 0.19f * (1f - kotlin.math.exp(-filesRemoved / 60_000f))

    /** Ends every process running in the guest, so nothing holds files that are about to move. */
    private fun stopGuestProcesses() {
        runCatching { AcpSessions.all().keys.forEach { AcpSessions.close(it) } }
        runCatching { TerminalHost.liveSessions().forEach { TerminalHost.closeSession(it.key) } }
        // --kill-on-exit takes the process trees down; give it a moment.
        Thread.sleep(600)
    }

    /**
     * Moves everything in [from] into [to], except the names in [skip]. A name that already
     * exists in [to] is replaced: what the user had wins over what the image ships. Safe to
     * repeat: names that have moved are no longer in [from].
     */
    private fun moveEntries(from: File, to: File, skip: Set<String>) {
        if (!exists(from)) return
        to.mkdirs()
        from.listFiles()?.forEach { entry ->
            if (entry.name in skip) return@forEach
            val target = File(to, entry.name)
            if (exists(target)) deleteTree(target)
            if (!entry.renameTo(target)) {
                // A rename only fails across filesystems or on a permission problem; copying is
                // slower but equivalent.
                copyTree(entry.toPath(), target.toPath())
                deleteTree(entry)
            }
        }
    }

    /**
     * Writes the names of the apt packages the user asked for on Ubuntu, from apt's own history.
     * Packages pulled in as dependencies ("automatic") are left out, and so are ones later
     * removed. The setup script installs what Debian has and lists what it does not.
     */
    internal fun recordPackages(old: File, staging: File) {
        val history = File(old, "var/log/apt/history.log")
        if (!history.isFile) return
        val wanted = LinkedHashSet<String>()
        // "Install: curl:arm64 (8.5.0-2, automatic), git:arm64 (1:2.43.0)" — one line per action.
        val entry = Regex("""([a-z0-9][a-z0-9+.\-]*)(?::[a-z0-9]+)?\s+\(([^)]*)\)""")
        history.useLines { lines ->
            for (line in lines) {
                val kind = line.substringBefore(':')
                if (kind != "Install" && kind != "Remove" && kind != "Purge") continue
                for (match in entry.findAll(line.substringAfter(':'))) {
                    val name = match.groupValues[1]
                    if (kind == "Install") {
                        if (!match.groupValues[2].contains("automatic")) wanted += name
                    } else {
                        wanted -= name
                    }
                }
            }
        }
        val names = wanted.filterNot { isUbuntuSpecific(it) }.take(400)
        if (names.isEmpty()) return
        val out = File(staging, PACKAGE_LIST)
        out.parentFile?.mkdirs()
        out.writeText(names.joinToString("\n", postfix = "\n"))
    }

    private fun isUbuntuSpecific(name: String): Boolean =
        name.contains("ubuntu") || name == "snapd" || name.startsWith("language-pack") ||
            name.startsWith("linux-") || name.startsWith("update-") || name == "unattended-upgrades"

    private fun exists(file: File): Boolean = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    /** Copies a file or directory tree, keeping symbolic links as links. Used only as a fallback. */
    private fun copyTree(from: Path, to: Path) {
        Files.walkFileTree(from, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val dest = to.resolve(from.relativize(file).toString())
                if (attrs.isSymbolicLink) {
                    Files.createSymbolicLink(dest, Files.readSymbolicLink(file))
                } else {
                    Files.copy(file, dest, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES)
                    if (file.toFile().canExecute()) dest.toFile().setExecutable(true, false)
                }
                return FileVisitResult.CONTINUE
            }
        })
    }

    /**
     * Deletes [root] and everything under it. Symbolic links are removed, never followed — an
     * absolute link in the guest must not lead this into the rest of the app's storage. A
     * directory PRoot created without any permissions is opened up first.
     */
    fun deleteTree(root: File, onProgress: ((Int) -> Unit)? = null) {
        if (!exists(root)) return
        var removed = 0
        fun walk(path: Path, retry: Boolean) {
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    runCatching { Files.delete(file) }
                    if (++removed % 500 == 0) onProgress?.invoke(removed)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    // A directory that could not be opened: give the owner access and try again.
                    val f = file.toFile()
                    if (retry && !Files.isSymbolicLink(file) && f.isDirectory) {
                        f.setReadable(true, true)
                        f.setWritable(true, true)
                        f.setExecutable(true, true)
                        walk(file, retry = false)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    runCatching { Files.delete(dir) }
                        .onFailure {
                            // Usually a read-only directory; open it and try once more.
                            dir.toFile().setWritable(true, true)
                            runCatching { Files.delete(dir) }
                        }
                    return FileVisitResult.CONTINUE
                }
            })
        }
        walk(root.toPath(), retry = true)
        onProgress?.invoke(removed)
    }
}
