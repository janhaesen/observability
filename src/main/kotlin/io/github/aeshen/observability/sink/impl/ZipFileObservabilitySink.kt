package io.github.aeshen.observability.sink.impl

import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.sink.ObservabilitySink
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val PAD_START = 12

/**
 * Writes each accepted record as its own entry in a zip file.
 * Entry naming is monotonic and safe without extra dependencies.
 */
internal class ZipFileObservabilitySink(
    zipPath: Path,
    private val entryPrefix: String = "log-",
    private val entrySuffix: String = ".jsonl",
) : ObservabilitySink {
    private var counter: Long = 0L
    private val zos: ZipOutputStream

    init {
        zipPath.parent?.let { Files.createDirectories(it) }
        val existingEntries = loadExistingEntries(zipPath)
        zos = ZipOutputStream(BufferedOutputStream(Files.newOutputStream(zipPath)))
        existingEntries.forEach { (name, bytes) ->
            counter = maxOf(counter, parseCounter(name))
            writeEntry(name, bytes)
        }
    }

    @Synchronized
    override fun handle(event: EncodedEvent) {
        val entryName = buildEntryName(event.metadata)
        writeEntry(entryName, event.encoded)
    }

    private fun buildEntryName(meta: MutableMap<String, Any?>): String {
        counter += 1
        val idx = counter.toString().padStart(PAD_START, '0')

        val suffix =
            (meta["event"] as? String)
                ?.takeIf { it.isNotBlank() }
                ?.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
                ?.let { "-$it" }
                ?: ""

        return "$entryPrefix$idx$suffix$entrySuffix"
    }

    private fun loadExistingEntries(zipPath: Path): List<Pair<String, ByteArray>> {
        if (!Files.exists(zipPath) || Files.size(zipPath) == 0L) {
            return emptyList()
        }

        val entries = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(Files.newInputStream(zipPath)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entries += entry.name to zis.readBytes()
                entry = zis.nextEntry
            }
        }
        return entries
    }

    private fun writeEntry(
        entryName: String,
        bytes: ByteArray,
    ) {
        val entry =
            ZipEntry(entryName).apply {
                method = ZipEntry.DEFLATED
                size = bytes.size.toLong()
            }

        zos.putNextEntry(entry)
        zos.write(bytes)
        zos.closeEntry()
        zos.flush()
    }

    private fun parseCounter(entryName: String): Long {
        if (!entryName.startsWith(entryPrefix) || !entryName.endsWith(entrySuffix)) {
            return 0L
        }

        val core = entryName.removePrefix(entryPrefix).removeSuffix(entrySuffix)
        val numericPrefix = core.takeWhile { it.isDigit() }
        return numericPrefix.toLongOrNull() ?: 0L
    }

    override fun close() {
        zos.close()
    }
}
