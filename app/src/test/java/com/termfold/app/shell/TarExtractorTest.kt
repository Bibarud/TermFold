package com.termfold.app.shell

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files

/**
 * Exercises [TarExtractor] against the actual Ubuntu image the app ships.
 *
 * A tar reader is easy to get subtly wrong, and its failure mode is silent: a stream that loses
 * sync stops early, or drops entries, without throwing. So it is tested against the real archive
 * rather than a hand-made one.
 *
 * Two things are asserted only off Windows, because the filesystem cannot represent them there:
 * symlinks (which need a privilege this build user does not have) and names containing a colon
 * (`gcc-14-base:amd64.list`, one per installed package). Both are ordinary on Linux and Android,
 * which is the platform that matters; the emulator run covers them for real.
 */
class TarExtractorTest {

    private val archive = File(
        System.getProperty("termfold.rootfsAsset") ?: "src/main/assets/ubuntu-x86_64.bin"
    )

    private val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /** The image holds this many entries of each kind. */
    private val archiveFiles = 2561
    private val archiveDirs = 656
    private val archiveSymlinks = 197

    @Test
    fun `unpacks the bundled ubuntu image`() {
        assumeTrue("rootfs image not present; run tools/fetch-shell-runtime.py", archive.isFile)

        val destination = Files.createTempDirectory("tf-rootfs").toFile()
        try {
            FileInputStream(archive).use { input -> TarExtractor.extract(input, destination) }

            // The pieces a usable Ubuntu guest cannot do without.
            listOf(
                "usr/bin/bash",
                "usr/bin/apt",
                "usr/bin/dpkg",
                "usr/bin/env",
                "etc/passwd",
                "etc/apt/sources.list.d/ubuntu.sources",
                "var/lib/dpkg/status",
            ).forEach { path ->
                assertTrue("missing $path", File(destination, path).isFile)
            }

            // The executable bit is what lets the guest shell run at all.
            assertTrue(File(destination, "usr/bin/bash").canExecute())

            if (!isWindows) {
                // /bin, /lib and most of /usr/bin are symlinks in this image, and the guest is
                // unusable without them.
                listOf("bin", "lib", "sbin").forEach { path ->
                    assertTrue(
                        "$path should be a symlink",
                        Files.isSymbolicLink(File(destination, path).toPath()),
                    )
                }
                assertTrue(File(destination, "bin/bash").canonicalFile.isFile)
                assertTrue(File(destination, "lib/x86_64-linux-gnu/libc.so.6").canonicalFile.isFile)
            }
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun `extracts every entry without losing sync`() {
        assumeTrue("rootfs image not present", archive.isFile)

        val destination = Files.createTempDirectory("tf-count").toFile()
        try {
            FileInputStream(archive).use { input -> TarExtractor.extract(input, destination) }

            val visible = destination.walkTopDown().toList()
            val files = visible.count { it.isFile }
            val dirs = visible.count { it.isDirectory }

            if (isWindows) {
                // 274 entries in this image have a colon in their name — dpkg's per-package index
                // files such as `gcc-14-base:amd64.list`. Windows cannot represent all of those,
                // so an exact count is not meaningful here. A reader that loses sync does not lose
                // a handful of entries, it stops hundreds early, so this bound still catches it;
                // the exact assertions below run on the platform the app actually ships on.
                assertTrue("regular files: $files, expected over 2300", files > 2300)
                assertTrue("directories: $dirs", dirs > 600)
            } else {
                assertTrue("regular files: $files, expected $archiveFiles", files == archiveFiles)
                assertTrue("directories: $dirs, expected $archiveDirs", dirs == archiveDirs)
                val links = visible.count { Files.isSymbolicLink(it.toPath()) }
                assertTrue(
                    "symlinks: $links, expected $archiveSymlinks",
                    links == archiveSymlinks,
                )
            }
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun `extracts into a path containing spaces`() {
        // The app's directory on a device contains no spaces, but the working copy of this
        // project does, and a reader that mishandles them would be caught here.
        assumeTrue("rootfs image not present", archive.isFile)

        val parent = Files.createTempDirectory("tf space").toFile()
        val destination = File(parent, "nested dir/rootfs")
        try {
            FileInputStream(archive).use { input -> TarExtractor.extract(input, destination) }
            assertTrue(File(destination, "usr/bin/bash").isFile)
            assertTrue(File(destination, "etc/passwd").isFile)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `is safe to run twice into the same directory`() {
        // Provisioning can be retried after a failure, so the extractor has to tolerate a
        // half-populated destination instead of stopping on the first file that already exists.
        assumeTrue("rootfs image not present", archive.isFile)

        val destination = Files.createTempDirectory("tf-retry").toFile()
        try {
            FileInputStream(archive).use { input -> TarExtractor.extract(input, destination) }
            FileInputStream(archive).use { input -> TarExtractor.extract(input, destination) }
            assertTrue(File(destination, "usr/bin/bash").isFile)
            assertTrue(
                File(destination, "usr/bin/bash").canonicalFile.isFile || isWindows
            )
        } finally {
            destination.deleteRecursively()
        }
    }
}
