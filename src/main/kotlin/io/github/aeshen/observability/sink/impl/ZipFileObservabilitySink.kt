package io.github.aeshen.observability.sink.impl

import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.sink.ObservabilitySink
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
        val replaySource = moveExistingToReplaySource(zipPath)
        zos = ZipOutputStream(BufferedOutputStream(Files.newOutputStream(zipPath)))
        replayExistingEntries(replaySource)
        replaySource?.let { Files.deleteIfExists(it) }
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

    private fun moveExistingToReplaySource(zipPath: Path): Path? {
        if (!Files.exists(zipPath) || Files.size(zipPath) == 0L) {
            return null
        }
        val source = Files.createTempFile(zipPath.parent, "observability-zip-replay-", ".zip")
        Files.move(zipPath, source, StandardCopyOption.REPLACE_EXISTING)
        return source
    }

    private fun replayExistingEntries(source: Path?) {
        if (source == null) {
            return
        }
        ZipInputStream(Files.newInputStream(source)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                counter = maxOf(counter, parseCounter(entry.name))
                writeEntryFromStream(entry.name, zis)
                entry = zis.nextEntry
            }
        }
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

    private fun writeEntryFromStream(
        entryName: String,
        source: ZipInputStream,
    ) {
        val entry = ZipEntry(entryName).apply { method = ZipEntry.DEFLATED }
        zos.putNextEntry(entry)
        source.copyTo(zos)
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
