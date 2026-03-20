package io.github.aeshen.observability.config.sink

import java.nio.file.Path

data class File(
    val path: Path,
) : SinkConfig
