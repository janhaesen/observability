package io.github.aeshen.observability.sink.decorator

fun interface BackoffStrategy {
    fun nextDelayMillis(attempt: Int): Long

    companion object {
        @JvmStatic
        fun fixed(delayMillis: Long): BackoffStrategy = BackoffStrategy { _ -> delayMillis.coerceAtLeast(0L) }

        @JvmStatic
        @JvmOverloads
        fun exponential(
            initialDelayMillis: Long = 10,
            multiplier: Double = 2.0,
            maxDelayMillis: Long = 1000,
        ): BackoffStrategy =
            BackoffStrategy { attempt ->
                val raw = initialDelayMillis * Math.pow(multiplier, (attempt - 1).coerceAtLeast(0).toDouble())
                raw.toLong().coerceAtMost(maxDelayMillis).coerceAtLeast(0L)
            }
    }
}
