package com.termfold.app.shell

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * What makes the Ubuntu guest behave like Ubuntu on a PC where Android differs.
 *
 * Android does not let an app create hard links in its own storage, and Ubuntu's tools assume
 * they can (dpkg, npm, git, pip, tar). This used to be papered over with PRoot's
 * `--link2symlink`, which fakes a hard link with a hidden `.l2s` data file kept in the folder of
 * the first name. That is fragile: when a program replaces that folder (npm does on every
 * install and update), the data disappears while other names still point at it, and the
 * program breaks with "not found" or "Operation not permitted". Claude Code's 240 MB binary
 * was lost exactly that way.
 *
 * Instead, a small library (tools/compat-shim) is preloaded into every guest program through
 * `/etc/ld.so.preload`, as a distribution would: a refused `link()` becomes an independent
 * copy of the file, which every one of those tools accepts. Only glibc reads that file, so
 * musl or static programs are unaffected. Existing fake links are converted into real files
 * once ([migrateFakeLinks]); until that has finished, sessions keep `--link2symlink` so nothing
 * changes under them half-way.
 */
object GuestCompat {

    private const val TAG = "GuestCompat"

    /** Where the library lives in the guest. Not shared storage, which is mounted noexec. */
    const val LIBRARY = "/opt/termfold/libtermfold-compat.so"

    /** The earlier /proc/self/fd-only shim, preloaded per agent; replaced by [LIBRARY]. */
    private const val OLD_PROCFD_SHIM = "/opt/termfold/procfd-shim.so"

    private const val MIGRATED_MARKER = "var/lib/termfold/l2s-migrated"

    fun asset(abi: String): String = "compat-shim-$abi.so"

    /** True once every fake link has been converted, so PRoot can run without faking them. */
    fun migrated(rootfs: File): Boolean = File(rootfs, MIGRATED_MARKER).isFile

    /**
     * Installs the library and registers it in `/etc/ld.so.preload`, keeping any lines the user
     * added there. Cheap and idempotent: files are rewritten only when they differ.
     */
    fun install(context: Context, rootfs: File, abi: String) {
        runCatching {
            val bytes = context.assets.open(asset(abi)).use { it.readBytes() }
            val lib = File(rootfs, LIBRARY.trimStart('/'))
            if (!(lib.isFile && lib.readBytes().contentEquals(bytes))) {
                lib.parentFile?.mkdirs()
                // Written aside and renamed, so a program starting meanwhile never maps half a file.
                val tmp = File(lib.parentFile, lib.name + ".tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(lib)) {
                    lib.delete()
                    check(tmp.renameTo(lib)) { "could not place $lib" }
                }
            }
            // Only after the library is in place: a preload entry for a missing file makes the
            // loader print a warning in front of every command.
            val preload = File(rootfs, "etc/ld.so.preload")
            val kept = if (preload.isFile) {
                preload.readLines().filter { line ->
                    val path = line.trim()
                    path.isNotEmpty() && path != LIBRARY && path != OLD_PROCFD_SHIM
                }
            } else {
                emptyList()
            }
            val content = (listOf(LIBRARY) + kept).joinToString("\n", postfix = "\n")
            if (!(preload.isFile && preload.readText() == content)) preload.writeText(content)
            File(rootfs, OLD_PROCFD_SHIM.trimStart('/')).delete()
        }.onFailure { Log.w(TAG, "Could not install the compatibility library", it) }
    }

    /**
     * Removes the empty mount points older versions left at the top of the guest, one per
     * device folder they bind-mounted in (/MyProject), which PRoot created without any
     * permissions. Only empty directories with names that
     * are not part of the Linux layout are touched, so nothing with content is ever removed.
     */
    fun removeStaleMountPoints(rootfs: File) {
        val stale = rootfs.listFiles()?.filter { f ->
            if (f.name in STANDARD_ROOT || Files.isSymbolicLink(f.toPath()) || !f.isDirectory) return@filter false
            // PRoot creates a bind target that does not exist with no permissions at all.
            if (f.list() == null) runCatching { f.setReadable(true, true); f.setExecutable(true, true) }
            f.list()?.isEmpty() == true
        }.orEmpty()
        if (stale.isEmpty()) return
        // The image's / is read-only (0555, as on any distribution); open it just for this.
        val root = rootfs.toPath()
        val mode = runCatching { Files.getPosixFilePermissions(root) }.getOrNull()
        runCatching { rootfs.setWritable(true, true) }
        try {
            stale.forEach { f ->
                runCatching { Files.delete(f.toPath()) }
                    .onSuccess { log("Removed the empty mount point /${f.name}") }
                    .onFailure { Log.w(TAG, "Could not remove /${f.name}", it) }
            }
        } finally {
            if (mode != null) runCatching { Files.setPosixFilePermissions(root, mode) }
        }
    }

    private val STANDARD_ROOT = setOf(
        "bin", "boot", "dev", "etc", "home", "lib", "lib32", "lib64", "libx32", "lib.usr-is-merged",
        "media", "mnt", "opt", "proc", "root", "run", "sbin", "snap", "srv", "sys", "tmp", "usr", "var",
        "storage", "sdcard",
    )

    /**
     * Turns every fake hard link PRoot's `--link2symlink` left in the guest into a real file,
     * then removes the hidden `.l2s` files. Runs once; afterwards [migrated] is true.
     *
     * A fake link is a symlink whose target's name starts with `.l2s.`; that target is itself a
     * chain ending at the data file. Each name gets its own copy of the data (written beside it
     * and renamed over the symlink), so no name depends on another folder any more. A name
     * whose data is already gone is left as it is: it was broken before, and without the hidden
     * files it now reads as simply missing, which tools handle by reinstalling.
     */
    fun migrateFakeLinks(rootfs: File) {
        if (migrated(rootfs)) return
        val root = rootfs.toPath()
        val names = ArrayList<Path>()
        val hidden = ArrayList<Path>()
        // The mounted-in host directories and the project folders are not part of the rootfs;
        // the rest is walked without following links.
        val skip = setOf("proc", "sys", "dev", "storage", "sdcard", "mnt").map { root.resolve(it) }.toSet()
        runCatching {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes) =
                        if (dir in skip) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val name = file.fileName?.toString().orEmpty()
                        if (name.startsWith(".l2s.")) {
                            hidden.add(file)
                        } else if (attrs.isSymbolicLink) {
                            val target = runCatching { Files.readSymbolicLink(file).toString() }.getOrNull()
                            if (target != null && target.substringAfterLast('/').startsWith(".l2s.")) names.add(file)
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException?) = FileVisitResult.CONTINUE
                },
            )
        }.onFailure { log("Walking the guest failed: $it"); return }

        var converted = 0
        var broken = 0
        var failed = 0
        val stillNeeded = HashSet<Path>()
        for (link in names) {
            val data = resolveChain(root, link)
            if (data == null) {
                // Keep whatever part of its chain exists: deleting hidden files is only safe
                // when no remaining name leads to them.
                broken++
                chainOf(root, link).forEach(stillNeeded::add)
                continue
            }
            val ok = runCatching {
                val tmp = link.resolveSibling(link.fileName.toString() + ".termfold-tmp")
                Files.deleteIfExists(tmp)
                Files.copy(data, tmp, StandardCopyOption.COPY_ATTRIBUTES)
                runCatching { Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(data)) }
                // A rename replaces the symlink itself, never what it points to.
                Files.move(tmp, link, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.onFailure { log("Could not convert $link: $it") }.isSuccess
            if (ok) {
                converted++
            } else {
                failed++
                chainOf(root, link).forEach(stillNeeded::add)
            }
        }
        var removed = 0
        for (h in hidden) {
            if (h in stillNeeded) continue
            if (runCatching { Files.deleteIfExists(h) }.getOrDefault(false)) removed++
        }
        // Done unless a conversion failed (a full disk, say); then the next start tries again,
        // with PRoot still faking links meanwhile.
        if (failed == 0) {
            runCatching {
                val marker = File(rootfs, MIGRATED_MARKER)
                marker.parentFile?.mkdirs()
                marker.writeText("converted=$converted broken=$broken removed=$removed\n")
            }
        }
        log("Fake hard links: $converted converted, $broken already broken, $failed failed, $removed hidden files removed")
    }

    private fun log(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    /**
     * Where a symlink in the guest points, as a host path. PRoot's fake links store the *host*
     * path of their target (/data/user/0/<app>/files/linux/rootfs/usr/bin/.l2s.perl0001),
     * while links made by guest programs store guest paths (/usr/bin/perl); both occur.
     */
    private fun resolveTarget(root: Path, link: Path): Path? {
        val target = runCatching { Files.readSymbolicLink(link).toString() }.getOrNull() ?: return null
        val rootText = root.toString().trimEnd('/')
        return when {
            target == rootText || target.startsWith("$rootText/") -> java.nio.file.Paths.get(target)
            target.startsWith("/") -> root.resolve(target.trimStart('/'))
            else -> link.resolveSibling(target)
        }.normalize()
    }

    /** Follows a fake link to its data file, or null when the chain is broken. */
    private fun resolveChain(root: Path, link: Path): Path? {
        var p = link
        repeat(8) {
            if (!Files.isSymbolicLink(p)) {
                return p.takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            }
            p = resolveTarget(root, p) ?: return null
            if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return null
        }
        return null
    }

    private fun chainOf(root: Path, link: Path): List<Path> {
        val out = ArrayList<Path>()
        var p: Path? = link
        repeat(8) {
            val cur = p ?: return out
            if (!Files.isSymbolicLink(cur)) return out
            val next = resolveTarget(root, cur) ?: return out
            out.add(next)
            p = next
        }
        return out
    }
}
