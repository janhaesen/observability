package io.github.aeshen.observability.config.sink

import kotlin.reflect.KClass

data class Slf4j(
    val logger: KClass<*>,
) : SinkConfig
