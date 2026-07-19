package io.github.aeshen.observability.sidecar.client

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

private const val ACCEPTED_STATUS = 202
private const val CLIENT_ERROR_MIN = 400
private const val CLIENT_ERROR_MAX = 499
private const val DEFAULT_MAX_EVENTS = 10_000
private const val DEFAULT_MAX_BYTES = 64L * 1024 * 1024

fun interface TokenProvider {
    fun token(): String?
}

@Serializable
data class EventSubmission(
    val clientEventId: String = UUID.randomUUID().toString(),
    val name: String,
    val level: String,
    val timestamp: String,
    val context: Map<String, String> = emptyMap(),
    val message: String? = null,
    val payloadBase64: String? = null,
)

class OutboxCapacityException(message: String) : IllegalStateException(message)

class SidecarClient(
    private val endpoint: URI,
    private val tokenProvider: TokenProvider = TokenProvider { null },
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) {
    fun submit(event: EventSubmission) {
        val request =
            HttpRequest
                .newBuilder(endpoint.resolve("/v1/events"))
                .header("Content-Type", "application/json")
                .apply { tokenProvider.token()?.let { header("Authorization", "Bearer $it") } }
                .POST(HttpRequest.BodyPublishers.ofString(Json.encodeToString(event)))
                .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())
        check(
            response.statusCode() == ACCEPTED_STATUS,
        ) { "Sidecar submission failed with status=${response.statusCode()}." }
    }
}

class FileOutbox(
    private val client: SidecarClient,
    private val directory: Path,
    private val maxEvents: Int = DEFAULT_MAX_EVENTS,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : AutoCloseable {
    private val journal = directory.resolve("outbox.jsonl")
    private val deadLetter = directory.resolve("outbox.dlq.jsonl")

    init {
        require(maxEvents > 0 && maxBytes > 0)
        Files.createDirectories(directory)
    }

    @Synchronized
    fun enqueue(event: EventSubmission) {
        val line = Json.encodeToString(event) + "\n"
        val count = if (Files.exists(journal)) Files.readAllLines(journal).size else 0
        val bytes = if (Files.exists(journal)) Files.size(journal) else 0
        if (count >= maxEvents || bytes + line.length > maxBytes) {
            throw OutboxCapacityException("Outbox capacity is exhausted.")
        }
        Files.writeString(
            journal,
            line,
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND,
        )
    }

    @Synchronized
    fun flush() {
        if (!Files.exists(journal)) return
        val pending = Files.readAllLines(journal).toMutableList()
        while (pending.isNotEmpty()) {
            val line = pending.first()
            val event = Json.decodeFromString<EventSubmission>(line)
            try {
                client.submit(event)
                pending.removeAt(0)
                rewrite(pending)
            } catch (error: IllegalStateException) {
                if ((CLIENT_ERROR_MIN..CLIENT_ERROR_MAX).any { error.message?.contains("status=$it") == true }) {
                    Files.writeString(
                        deadLetter,
                        "$line\n",
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND,
                    )
                    pending.removeAt(0)
                    rewrite(pending)
                } else {
                    return
                }
            }
        }
    }

    private fun rewrite(lines: List<String>) {
        val temporary = directory.resolve("outbox.jsonl.tmp")
        Files.write(temporary, lines)
        Files.move(temporary, journal, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun close() = flush()
}
