package com.termfold.app.acp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Exercises [AcpArchives] against the real agent archives from the ACP registry.
 *
 * The registry's `cmd` entries and archive layouts are all slightly different — nested
 * `bin/` directories (devin, kimchi), versioned wrapper directories (cortex-code), nested zip
 * layouts (junie), bzip2 compression (goose), and one raw ELF binary with no archive at all
 * (sigit) — and a resolution rule that works for one agent silently fails for the next. So the
 * rules run against the actual downloads.
 *
 * The archive set is not in source control. Point `termfold.acpArchives` at a directory holding
 * the downloaded archives plus the `manifest.json` produced alongside them; without it the test
 * skips, and the emulator run covers the same code path end to end.
 */
class AcpArchivesTest {

    private val archives = File(
        System.getProperty("termfold.acpArchives") ?: "src/test/acp-archives",
    )

    private val manifest by lazy {
        val file = File(archives, "manifest.json")
        assumeTrue("no archive set at ${archives.absolutePath}", file.isFile)
        file.readText()
    }

    private data class Entry(val file: String, val cmd: String)

    private fun entries(): List<Entry> {
        val root = org.json.JSONObject(manifest)
        return root.getJSONArray("archives").let { array ->
            List(array.length()) { i ->
                val e = array.getJSONObject(i)
                Entry(e.getString("file"), e.getString("cmd"))
            }
        }
    }

    @Test
    fun `every registry archive extracts and resolves to its command`() {
        val list = entries()
        assumeTrue("archive set is empty", list.isNotEmpty())

        val results = mutableListOf<String>()
        for (entry in list) {
            val archive = File(archives, entry.file)
            assumeTrue("missing archive ${entry.file}", archive.isFile)

            val destination = Files.createTempDirectory("tf-acp").toFile()
            try {
                AcpArchives.extract(archive, destination, entry.cmd)
                val resolved = AcpArchives.resolveCommand(destination, entry.cmd)
                assertNotNull("${entry.file}: cmd '${entry.cmd}' did not resolve", resolved)
                resolved!!

                val base = AcpArchives.normaliseCmd(entry.cmd).substringAfterLast('/')
                assertEquals(
                    "${entry.file}: resolved '${resolved.name}' but expected '$base'",
                    base,
                    resolved.name,
                )
                val relative = resolved.relativeTo(destination).invariantSeparatorsPath
                results += "${entry.file} -> $relative"
            } finally {
                destination.deleteRecursively()
            }
        }
        println(results.joinToString("\n"))
    }

    @Test
    fun `command normalisation handles registry spellings`() {
        assertEquals("bin/devin", AcpArchives.normaliseCmd("./bin/devin"))
        assertEquals("devin", AcpArchives.normaliseCmd("./devin"))
        assertEquals("bin/devin.exe", AcpArchives.normaliseCmd(".\\bin\\devin.exe"))
        assertEquals("dist-package/cursor-agent", AcpArchives.normaliseCmd("dist-package/cursor-agent"))
    }

    @Test
    fun `resolution never mistakes a longer-named sibling for the command`() {
        // cursor ships `cursor-agent`, `cursor-agent-sea` and `cursor-agent-worker-sea` in one
        // directory; only the exact basename may win.
        val destination = Files.createTempDirectory("tf-acp-sibling").toFile()
        try {
            File(destination, "dist-package").mkdirs()
            File(destination, "dist-package/cursor-agent").writeText("x")
            File(destination, "dist-package/cursor-agent-sea").writeText("x")

            val resolved = AcpArchives.resolveCommand(destination, "./dist-package/cursor-agent")
            assertEquals("dist-package/cursor-agent", resolved!!.relativeTo(destination).invariantSeparatorsPath)
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun `resolution digs through a single wrapper directory`() {
        // Versioned wrapper directories appear without the registry mentioning them.
        val destination = Files.createTempDirectory("tf-acp-wrapper").toFile()
        try {
            File(destination, "coco-1.0.73+180523-linux-arm64").mkdirs()
            File(destination, "coco-1.0.73+180523-linux-arm64/cortex").writeText("x")

            val resolved = AcpArchives.resolveCommand(
                destination,
                "./coco-9.9.9+1-linux-arm64/cortex",
            )
            assertNotNull(resolved)
            assertTrue(resolved!!.isFile)
        } finally {
            destination.deleteRecursively()
        }
    }
}
