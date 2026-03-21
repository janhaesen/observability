package io.github.aeshen.observability.sink.impl

import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.sink.ObservabilitySink
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Framework implementation detail.
 */
internal class FileObservabilitySink(
    private val path: Path,
) : ObservabilitySink {
    private val open = AtomicBoolean(true)
    private val output: OutputStream

    init {
        path.parent?.let { Files.createDirectories(it) }
        output =
            BufferedOutputStream(
                Files.newOutputStream(
                    path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND,
                ),
            )
    }

    @Synchronized
    override fun handle(event: EncodedEvent) {
        check(open.get()) { "FileObservabilitySink is closed." }

        val bytes =
            if (event.encoded.lastOrNull() == '\n'.code.toByte()) {
                event.encoded
            } else {
                event.encoded + "\n".toByteArray(Charsets.UTF_8)
            }

        output.write(bytes)
        output.flush()
    }

    @Synchronized
    override fun close() {
        if (!open.compareAndSet(true, false)) {
            return
        }
        output.close()
    }
}
