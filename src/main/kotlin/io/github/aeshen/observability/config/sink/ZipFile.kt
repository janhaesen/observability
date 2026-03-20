package io.github.aeshen.observability.config.sink

import java.nio.file.Path

data class ZipFile(
    val path: Path,
) : SinkConfig
