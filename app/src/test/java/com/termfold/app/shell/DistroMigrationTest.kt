package com.termfold.app.shell

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test

/** The parts of the Ubuntu to Debian migration that do not need a device. */
class DistroMigrationTest {

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    @Test
    fun `records what the user installed and leaves out dependencies and removals`() {
        val old = Files.createTempDirectory("old").toFile()
        val staging = Files.createTempDirectory("new").toFile()
        try {
            val log = File(old, "var/log/apt/history.log")
            log.parentFile.mkdirs()
            log.writeText(
                """
                Start-Date: 2026-01-01  10:00:00
                Commandline: apt-get install -y htop tmux
                Install: htop:arm64 (3.3.0-4), libncurses6:arm64 (6.4, automatic), tmux:arm64 (3.4-1)
                End-Date: 2026-01-01  10:00:05

                Start-Date: 2026-01-02  10:00:00
                Commandline: apt remove tmux
                Remove: tmux:arm64 (3.4-1)
                End-Date: 2026-01-02  10:00:05

                Start-Date: 2026-01-03  10:00:00
                Install: ubuntu-advantage-tools:arm64 (30), ripgrep:arm64 (14.1.0-1), language-pack-en:arm64 (1)
                End-Date: 2026-01-03  10:00:05
                """.trimIndent()
            )
            DistroMigration.recordPackages(old, staging)
            val written = File(staging, DistroMigration.PACKAGE_LIST).readLines().filter { it.isNotBlank() }
            assertEquals(listOf("htop", "ripgrep"), written)
        } finally {
            old.deleteRecursively()
            staging.deleteRecursively()
        }
    }

    @Test
    fun `writes nothing when there is no apt history`() {
        val old = Files.createTempDirectory("old").toFile()
        val staging = Files.createTempDirectory("new").toFile()
        try {
            DistroMigration.recordPackages(old, staging)
            assertFalse(File(staging, DistroMigration.PACKAGE_LIST).exists())
        } finally {
            old.deleteRecursively()
            staging.deleteRecursively()
        }
    }

    @Test
    fun `deleting a tree removes a symlink without following it`() {
        assumeFalse("symlinks need a privilege on Windows", isWindows)
        val outside = Files.createTempDirectory("outside").toFile()
        val tree = Files.createTempDirectory("tree").toFile()
        try {
            File(outside, "keep.txt").writeText("keep")
            File(tree, "a/b").mkdirs()
            File(tree, "a/b/file").writeText("x")
            Files.createSymbolicLink(File(tree, "a/link").toPath(), outside.toPath())

            DistroMigration.deleteTree(tree)

            assertFalse(tree.exists())
            assertTrue("what the link pointed at must survive", File(outside, "keep.txt").isFile)
        } finally {
            tree.deleteRecursively()
            outside.deleteRecursively()
        }
    }
}
