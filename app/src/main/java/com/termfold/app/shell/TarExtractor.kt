package com.termfold.app.shell

import android.util.Log
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPInputStream

/**
 * Unpacks a `.tar.gz` root filesystem without running a single external process.
 *
 * This is deliberately pure Kotlin. The obvious approach — shelling out to a bundled `tar` — does
 * not work here: Android's seccomp policy makes the raw `fork` syscall fail with `ENOSYS` for app
 * processes, and BusyBox's tar forks. Extracting in-process sidesteps that entirely, and it also
 * means the rootfs is unpacked before any of the bundled executables have to prove they run.
 *
 * The archives this handles are Linux distribution rootfs images, so the features that matter are
 * the ones those images use: gzip compression, the `ustar` prefix field, GNU and PAX long names,
 * symlinks (a great many), a couple of hard links, and the executable permission bits.
 */
internal object TarExtractor {

    private const val BLOCK = 512

    /** Extracts [archive] into [destination], which is created if needed. */
    fun extract(
        archive: InputStream,
        destination: File,
        onWarning: (String) -> Unit = {},
    ) {
        destination.mkdirs()

        val buffer = ByteArray(BLOCK)
        var pendingLongName: String? = null
        var pendingLongLink: String? = null

        GZIPInputStream(archive, 1 shl 16).use { tar ->
            while (true) {
                if (!readFully(tar, buffer)) break

                // The archive ends with one or more all-zero blocks.
                if (buffer.all { it == 0.toByte() }) break

                val name = pendingLongName ?: readString(buffer, 0, 100)
                val linkName = pendingLongLink ?: readString(buffer, 157, 100)
                pendingLongName = null
                pendingLongLink = null

                val prefix = readString(buffer, 345, 155)
                val size = readOctal(buffer, 124, 12)
                val mode = readOctal(buffer, 100, 8).toInt()
                val type = buffer[156].toInt().toChar()

                val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name

                // Whether the branch below consumed this entry's payload. Exactly one of the two
                // must happen: reading it and then also skipping it desynchronises the whole
                // stream, which shows up as nonsense filenames several hundred entries later.
                var payloadRead = false

                when (type) {
                    // GNU long name / long link: the payload is the name for the next entry.
                    'L' -> {
                        pendingLongName = readPayload(tar, size).toString(Charsets.UTF_8).trimEnd('\u0000')
                        payloadRead = true
                    }

                    'K' -> {
                        pendingLongLink = readPayload(tar, size).toString(Charsets.UTF_8).trimEnd('\u0000')
                        payloadRead = true
                    }
                    // PAX headers carry extended attributes; only "path" and "linkpath" matter here.
                    'x', 'g' -> {
                        parsePax(readPayload(tar, size).toString(Charsets.UTF_8)).forEach { (key, value) ->
                            when (key) {
                                "path" -> pendingLongName = value
                                "linkpath" -> pendingLongLink = value
                            }
                        }
                        payloadRead = true
                    }

                    // A regular file: its contents are this entry's payload.
                    '0', '\u0000', '7' -> {
                        val file = target(destination, fullName)
                        file.parentFile?.mkdirs()
                        writeFile(tar, file, size, mode)
                        // The payload has been consumed, but its block padding has not.
                        skipPadding(tar, size)
                        payloadRead = true
                    }

                    '5' -> target(destination, fullName).mkdirs()

                    '2' -> {
                        val link = target(destination, fullName)
                        link.parentFile?.mkdirs()
                        createSymlink(link, linkName, onWarning)
                    }

                    // A hard link: both names have to end up referring to one file. If the
                    // filesystem refuses, a copy is a correct if slightly wasteful substitute.
                    '1' -> {
                        val link = target(destination, fullName)
                        val source = target(destination, linkName)
                        link.parentFile?.mkdirs()
                        runCatching {
                            link.delete()
                            Files.createLink(link.toPath(), source.toPath())
                        }.onFailure {
                            runCatching { source.copyTo(link, overwrite = true) }
                        }
                    }

                    // Character/block devices, FIFOs and GNU sparse entries do not appear in the
                    // images this app ships, and creating them would need privileges anyway.
                    else -> Unit
                }

                if (!payloadRead) {
                    // Payloads are padded out to a whole block; the padding is always consumed.
                    skipFully(tar, size + paddingFor(size))
                }
            }
        }
    }
    /** Resolves a tar entry name against the destination, refusing to escape it. */
    private fun target(destination: File, name: String): File {
        // Names are relative, occasionally prefixed "./", and use forward slashes throughout.
        val cleaned = name.removePrefix("./").trimStart('/')
        require(!cleaned.split('/').contains("..")) { "Unsafe path in archive: $name" }
        return File(destination, cleaned)
    }

    private fun writeFile(input: InputStream, file: File, size: Long, mode: Int) {
        file.parentFile?.mkdirs()
        // A directory may already sit where a file belongs if the archive is ordered unusually.
        if (file.isDirectory) file.deleteRecursively()
        file.outputStream().use { output ->
            copy(input, output, size)
        }
        applyMode(file, mode)
    }

    private fun createSymlink(link: File, targetPath: String, onWarning: (String) -> Unit) {
        link.delete()
        runCatching {
            Files.createSymbolicLink(link.toPath(), Paths.get(targetPath))
        }.onFailure { error ->
            // Without symlinks the guest is unusable: /bin, /lib and most of /usr/bin are links.
            onWarning("Could not create symlink ${link.path} -> $targetPath: ${error.message}")
        }
    }

    /**
     * Applies the mode bits the archive records.
     *
     * Only the executable bit is set explicitly. Everything extracted already belongs to the app's
     * own UID, so the read and write bits Android grants by default are enough for the app — and
     * for PRoot, which runs as that same UID while faking a different identity to the guest.
     * Rewriting the other bits is not just unnecessary, it throws on this platform.
     */
    private fun applyMode(file: File, mode: Int) {
        if (mode and 0b001_001_001 != 0) {
            file.setExecutable(true, false)
        }
    }

    /** Reads exactly [size] bytes of payload into memory, for header records. */
    private fun readPayload(input: InputStream, size: Long): ByteArray {
        val data = ByteArray(size.toInt())
        var offset = 0
        while (offset < data.size) {
            val read = input.read(data, offset, data.size - offset)
            if (read < 0) break
            offset += read
        }
        val padding = (BLOCK - (size % BLOCK)) % BLOCK
        skipFully(input, padding)
        return data
    }

    private fun parsePax(record: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var index = 0
        while (index < record.length) {
            val space = record.indexOf(' ', index)
            if (space < 0) break
            val length = record.substring(index, space).toIntOrNull() ?: break
            if (length <= 0 || index + length > record.length) break
            val entry = record.substring(space + 1, index + length).trimEnd('\n')
            val equals = entry.indexOf('=')
            if (equals > 0) result[entry.substring(0, equals)] = entry.substring(equals + 1)
            index += length
        }
        return result
    }

    private fun readString(buffer: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = offset + length
        while (end < limit && buffer[end] != 0.toByte()) end++
        return String(buffer, offset, end - offset, Charsets.UTF_8)
    }

    /** Octal numbers in a tar header are ASCII digits, NUL or space terminated. */
    private fun readOctal(buffer: ByteArray, offset: Int, length: Int): Long {
        var value = 0L
        for (index in offset until offset + length) {
            val byte = buffer[index].toInt()
            when {
                byte == 0 || byte == ' '.code -> if (value != 0L) return value
                byte in '0'.code..'7'.code -> value = value * 8 + (byte - '0'.code)
                else -> return value
            }
        }
        return value
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) return offset != 0
            offset += read
        }
        return true
    }

    /** Discards the block padding that follows an entry payload of [size] bytes. */
    private fun skipPadding(input: InputStream, size: Long) {
        skipFully(input, paddingFor(size))
    }

    /** Entry payloads occupy whole 512-byte blocks. */
    private fun paddingFor(size: Long): Long = (BLOCK - (size % BLOCK)) % BLOCK

    /**
     * Discards [count] bytes.
     *
     * Deliberately reads rather than calling `skip()`. `GZIPInputStream.skip` inflates into an
     * internal buffer and can advance further than asked, which silently desynchronises the tar
     * stream and makes every following header be read at the wrong offset.
     */
    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val read = input.read(discard, 0, minOf(discard.size.toLong(), remaining).toInt())
            if (read < 0) return
            remaining -= read
        }
    }

    private val discard = ByteArray(1 shl 16)

    private fun copy(input: InputStream, output: java.io.OutputStream, count: Long) {
        val buffer = ByteArray(1 shl 16)
        var remaining = count
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
    }
}
