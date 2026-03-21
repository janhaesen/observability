package io.github.aeshen.observability.sink.impl

import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.sink.ObservabilitySink
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Framework implementation detail.
 */
internal class FileObservabilitySink(
    private val path: Path,
) : ObservabilitySink {
    init {
        path.parent?.let { Files.createDirectories(it) }
    }

    override fun handle(event: EncodedEvent) {
        val bytes =
            if (event.encoded.lastOrNull() == '\n'.code.toByte()) {
                event.encoded
            } else {
                event.encoded + "\n".toByteArray(Charsets.UTF_8)
            }

        Files.write(
            path,
            bytes,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND,
        )
    }
}
