package com.termfold.app.acp

import com.termfold.app.shell.TarExtractor
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File

/**
 * Format detection, extraction and command resolution for registry agent archives — deliberately
 * free of Android dependencies so it is covered by a plain JVM test against the real downloads.
 *
 * Registries ship gzip tarballs, bzip2 tarballs, plain tar, zip, and occasionally a raw ELF
 * binary with no archive wrapper at all (sigit). Which one it is can only be told from the
 * leading bytes: file names in the wild disagree with contents (`sigit-linux-arm64` is an ELF).
 */
internal object AcpArchives {

    /**
     * Extracts [archive] into [destination]. [cmdPath] is the registry's command path; when the
     * download turns out to be a bare binary it is written to exactly that path.
     */
    fun extract(archive: File, destination: File, cmdPath: String?) {
        val magic = archive.inputStream().use { stream ->
            val buffer = ByteArray(263)
            var read = 0
            while (read < buffer.size) {
                val n = stream.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            buffer.copyOf(read)
        }

        fun hasMagic(vararg bytes: Int): Boolean =
            bytes.indices.all { magic.size > it && magic[it] == bytes[it].toByte() }

        when {
            hasMagic(0x1f, 0x8b) ->
                // TarExtractor gunzips internally; hand it the raw stream.
                archive.inputStream().buffered().use { input ->
                    TarExtractor.extract(input, destination, confined = true)
                }

            hasMagic(0x42, 0x5a, 0x68) -> // "BZh" — bzip2-compressed tar (goose)
                archive.inputStream().buffered().use { input ->
                    BZip2CompressorInputStream(input, true).use { decompressed ->
                        TarExtractor.extract(decompressed, destination, gunzip = false, confined = true)
                    }
                }

            hasMagic(0x50, 0x4b, 0x03, 0x04) -> extractZip(archive, destination) // "PK.." — zip

            hasMagic(0x7f, 0x45, 0x4c, 0x46) -> { // ELF — the download is the binary itself
                val cmd = cmdPath?.let { normaliseCmd(it) }?.takeIf { it.isNotBlank() }
                    ?: error("A raw-binary distribution needs a command path to land at")
                require(!cmd.split('/').contains("..")) { "Unsafe command path: $cmd" }
                val target = File(destination, cmd)
                target.parentFile?.mkdirs()
                archive.copyTo(target, overwrite = true)
                target.setExecutable(true, false)
            }

            magic.size >= 262 && String(magic, 257, 5) == "ustar" ->
                // Uncompressed tar: the ustar magic lives at offset 257 of the first header.
                archive.inputStream().buffered().use { input ->
                    TarExtractor.extract(input, destination, gunzip = false, confined = true)
                }

            else -> error(
                "Archive format not recognised (${magic.take(8).joinToString(" ") { "%02x".format(it) }})",
            )
        }
    }

    /**
     * Locates the registry's `cmd` inside the extracted archive directory.
     *
     * Registry entries spell the path relative to the archive root ("./bin/devin"), so that
     * exact path is tried first. Archives sometimes wrap everything in one versioned directory
     * the entry does not mention, so the same nested path is then probed under a lone top-level
     * directory, and finally the shallowest file with the cmd's basename is accepted — the
     * shallowest, because `dist-package/cursor-agent` must not be satisfied by
     * `dist-package/cursor-agent-sea` sitting beside it. Returns null when nothing plausible
     * exists; the caller turns that into a diagnostic listing.
     */
    fun resolveCommand(dir: File, cmd: String): File? {
        val cleaned = normaliseCmd(cmd)
        File(dir, cleaned).takeIf { it.isFile }?.let { return it }

        // One top-level directory and nothing else at the root: the archive is wrapped.
        val top = dir.listFiles().orEmpty()
        if (top.size == 1 && top[0].isDirectory) {
            File(top[0], cleaned).takeIf { it.isFile }?.let { return it }
        }

        val base = cleaned.substringAfterLast('/')
        return dir.walkTopDown()
            .maxDepth(6)
            .filter { it.isFile && it.name == base }
            .minByOrNull { it.relativeTo(dir).invariantSeparatorsPath.length }
    }

    /** Registry commands are "./"-prefixed relative paths; Windows builds use backslashes. */
    fun normaliseCmd(cmd: String): String =
        cmd.replace('\\', '/').trim().removePrefix("./").trimStart('/')

    private fun extractZip(archive: File, destination: File) {
        // With the separator, so "/x/acp-evil" does not pass for a path inside "/x/acp".
        val canonical = destination.canonicalPath + File.separator
        java.util.zip.ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val out = File(destination, entry.name)
                if (!out.canonicalPath.startsWith(canonical)) continue // zip-slip guard
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                    // Zip entries carry no permission bits; everything we unpack must be
                    // executable for the guest to run it.
                    out.setExecutable(true, false)
                }
            }
        }
    }
}
