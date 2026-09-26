package com.termfold.app.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * The one-time conversion of PRoot's fake hard links, against the exact layout PRoot leaves on
 * disk. An earlier version misread host-path targets as broken and deleted their data.
 */
class GuestCompatTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun link(at: File, target: String) {
        at.parentFile.mkdirs()
        Files.createSymbolicLink(at.toPath(), java.nio.file.Paths.get(target))
    }

    private fun canSymlink(): Boolean = runCatching {
        val probe = File(tmp.root, "probe")
        Files.createSymbolicLink(probe.toPath(), java.nio.file.Paths.get("x"))
        probe.delete()
    }.isSuccess

    @Test
    fun `fake links become real files and only their hidden data is removed`() {
        assumeTrue("symlinks not permitted on this host", canSymlink())
        val rootfs = tmp.newFolder("rootfs")
        val host = rootfs.absolutePath

        // perl and perl5.38 as PRoot writes them: host paths, two levels of indirection.
        val data = File(rootfs, "usr/bin/.l2s.perl0001.0002").apply { parentFile.mkdirs(); writeText("PERL") }
        data.setExecutable(true, false)
        link(File(rootfs, "usr/bin/.l2s.perl0001"), "$host/usr/bin/.l2s.perl0001.0002")
        link(File(rootfs, "usr/bin/perl"), "$host/usr/bin/.l2s.perl0001")
        link(File(rootfs, "usr/bin/perl5.38"), "$host/usr/bin/.l2s.perl0001")

        // A chain with a guest path, one level.
        File(rootfs, "opt/a/.l2s.x0001").apply { parentFile.mkdirs(); writeText("X") }
        link(File(rootfs, "opt/b/x"), "/opt/a/.l2s.x0001")

        // Already broken before the migration: its data is gone.
        link(File(rootfs, "opt/c/y"), "$host/opt/c/.l2s.gone0001")

        // An ordinary symlink a package made.
        File(rootfs, "usr/bin/python3").writeText("PY")
        link(File(rootfs, "usr/bin/python"), "python3")

        GuestCompat.migrateFakeLinks(rootfs)

        for (name in listOf("usr/bin/perl", "usr/bin/perl5.38")) {
            val f = File(rootfs, name)
            assertFalse("$name should no longer be a link", Files.isSymbolicLink(f.toPath()))
            assertEquals("PERL", f.readText())
            assertTrue("$name should stay executable", f.canExecute())
        }
        assertEquals("X", File(rootfs, "opt/b/x").readText())
        assertFalse(Files.isSymbolicLink(File(rootfs, "opt/b/x").toPath()))

        // The hidden files are gone now that nothing needs them.
        assertFalse(File(rootfs, "usr/bin/.l2s.perl0001.0002").exists())
        assertFalse(Files.exists(File(rootfs, "usr/bin/.l2s.perl0001").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertFalse(File(rootfs, "opt/a/.l2s.x0001").exists())

        // Untouched: the broken name and the ordinary symlink.
        assertTrue(Files.isSymbolicLink(File(rootfs, "opt/c/y").toPath()))
        assertTrue(Files.isSymbolicLink(File(rootfs, "usr/bin/python").toPath()))
        assertEquals("python3", Files.readSymbolicLink(File(rootfs, "usr/bin/python").toPath()).toString())

        assertTrue(GuestCompat.migrated(rootfs))
    }

    @Test
    fun `hidden files a name still reaches are kept, orphans removed`() {
        assumeTrue("symlinks not permitted on this host", canSymlink())
        val rootfs = tmp.newFolder("rootfs2")
        val host = rootfs.absolutePath
        // A chain the migration cannot resolve (a loop): its hidden files must survive.
        link(File(rootfs, "usr/lib/libfoo.so"), "$host/usr/lib/.l2s.loop0001")
        link(File(rootfs, "usr/lib/.l2s.loop0001"), "$host/usr/lib/.l2s.loop0002")
        link(File(rootfs, "usr/lib/.l2s.loop0002"), "$host/usr/lib/.l2s.loop0001")
        // Hidden data nothing points at any more: removed.
        File(rootfs, "usr/lib/.l2s.old0001.0001").writeText("OLD")

        GuestCompat.migrateFakeLinks(rootfs)

        assertTrue(Files.isSymbolicLink(File(rootfs, "usr/lib/libfoo.so").toPath()))
        assertTrue(Files.isSymbolicLink(File(rootfs, "usr/lib/.l2s.loop0001").toPath()))
        assertTrue(Files.isSymbolicLink(File(rootfs, "usr/lib/.l2s.loop0002").toPath()))
        assertFalse(File(rootfs, "usr/lib/.l2s.old0001.0001").exists())
    }
}
