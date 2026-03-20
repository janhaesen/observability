package io.github.aeshen.observability.sink.impl

import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.sink.ObservabilitySink
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
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
        Files.createDirectories(zipPath.parent)
        zos = ZipOutputStream(BufferedOutputStream(Files.newOutputStream(zipPath)))
    }

    @Synchronized
    override fun handle(event: EncodedEvent) {
        val entryName = buildEntryName(event.metadata)
        val entry =
            ZipEntry(entryName).apply {
                method = ZipEntry.DEFLATED
                size = event.encoded.size.toLong()
            }

        zos.putNextEntry(entry)
        zos.write(event.encoded)
        zos.closeEntry()
        zos.flush()
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

    override fun close() {
        zos.close()
    }
}
