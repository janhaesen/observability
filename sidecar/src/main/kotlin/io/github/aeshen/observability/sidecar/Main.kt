package io.github.aeshen.observability.sidecar

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.aeshen.observability.EventName
import io.github.aeshen.observability.Observability
import io.github.aeshen.observability.ObservabilityContext
import io.github.aeshen.observability.ObservabilityFactory
import io.github.aeshen.observability.config.sink.Console
import io.github.aeshen.observability.event
import io.github.aeshen.observability.key.TypedKey
import io.github.aeshen.observability.sink.EventLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.time.Instant

private const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
private const val PROBLEM_CONTENT_TYPE = "application/problem+json; charset=utf-8"
private const val DEFAULT_PORT = 8080
private const val DEFAULT_MAX_REQUEST_BYTES = 1_048_576
private const val DEFAULT_MAX_BATCH_SIZE = 100
private const val HTTP_OK = 200
private const val HTTP_ACCEPTED = 202
private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_PAYLOAD_TOO_LARGE = 413
private const val HTTP_METHOD_NOT_ALLOWED = 405
private const val HTTP_SERVICE_UNAVAILABLE = 503
private const val MAX_PORT = 65535
private const val SERVER_THREADS = 4
private const val OVER_LIMIT_READ_BYTES = 1

fun main() {
    val config = SidecarConfig.fromEnvironment()
    val observability =
        ObservabilityFactory.create(
            ObservabilityFactory.Config(
                sinks = listOf(Console),
                profile = config.profile,
                persistentBufferDirectory = config.persistentBufferDirectory,
            ),
        )
    SidecarServer(config, observability).start()
}

data class SidecarConfig(
    val host: String,
    val port: Int,
    val bearerToken: String?,
    val profile: ObservabilityFactory.Profile,
    val persistentBufferDirectory: Path?,
    val maxRequestBytes: Int = DEFAULT_MAX_REQUEST_BYTES,
    val maxBatchSize: Int = DEFAULT_MAX_BATCH_SIZE,
) {
    init {
        require(port in 1..MAX_PORT) { "SIDECAR_PORT must be between 1 and $MAX_PORT." }
        require(maxRequestBytes > 0) { "SIDECAR_MAX_REQUEST_BYTES must be greater than 0." }
        require(maxBatchSize > 0) { "SIDECAR_MAX_BATCH_SIZE must be greater than 0." }
        require(bearerToken != null || InetAddress.getByName(host).isLoopbackAddress) {
            "SIDECAR_HOST must resolve to a loopback address when SIDECAR_BEARER_TOKEN is not configured."
        }
        require(profile != ObservabilityFactory.Profile.AUDIT_DURABLE || persistentBufferDirectory != null) {
            "SIDECAR_PERSISTENT_BUFFER_DIRECTORY is required for AUDIT_DURABLE."
        }
    }

    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): SidecarConfig =
            SidecarConfig(
                host = environment["SIDECAR_HOST"] ?: "127.0.0.1",
                port = environment["SIDECAR_PORT"]?.toIntOrNull() ?: DEFAULT_PORT,
                bearerToken = environment["SIDECAR_BEARER_TOKEN"]?.takeIf(String::isNotBlank),
                profile =
                    environment["SIDECAR_PROFILE"]
                        ?.let(ObservabilityFactory.Profile::valueOf)
                        ?: ObservabilityFactory.Profile.STANDARD,
                persistentBufferDirectory = environment["SIDECAR_PERSISTENT_BUFFER_DIRECTORY"]?.let(Path::of),
                maxRequestBytes = environment["SIDECAR_MAX_REQUEST_BYTES"]?.toIntOrNull() ?: DEFAULT_MAX_REQUEST_BYTES,
                maxBatchSize = environment["SIDECAR_MAX_BATCH_SIZE"]?.toIntOrNull() ?: DEFAULT_MAX_BATCH_SIZE,
            )
    }
}

class SidecarServer(
    private val config: SidecarConfig,
    private val observability: Observability,
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(config.host, config.port), 0)

    init {
        server.executor = Executors.newFixedThreadPool(SERVER_THREADS)
        server.createContext(
            "/healthz",
        ) { exchange -> exchange.respond(HTTP_OK, """{"status":"ok"}""", JSON_CONTENT_TYPE) }
        server.createContext("/v1/events") { exchange -> handleSingle(exchange) }
        server.createContext("/v1/events:batch") { exchange -> handleBatch(exchange) }
    }

    fun start() {
        Runtime.getRuntime().addShutdownHook(Thread(::close, "observability-sidecar-shutdown"))
        server.start()
    }

    override fun close() {
        server.stop(0)
        observability.close()
    }

    private fun handleSingle(exchange: HttpExchange) {
        exchange.use {
            if (!authorize(it)) return
            if (it.requestMethod != "POST") {
                it.problem(HTTP_METHOD_NOT_ALLOWED, "method-not-allowed", "Only POST is supported.")
                return
            }
            submit(it) { body -> listOf(parseEvent(body.jsonObject)) }
        }
    }

    private fun handleBatch(exchange: HttpExchange) {
        exchange.use {
            if (!authorize(it)) return
            if (it.requestMethod != "POST") {
                it.problem(HTTP_METHOD_NOT_ALLOWED, "method-not-allowed", "Only POST is supported.")
                return
            }
            submit(it) { body ->
                val objectBody = body.jsonObject.requireOnly("events")
                val events = objectBody["events"]?.jsonArray ?: throw RequestValidationException("events is required.")
                require(events.isNotEmpty()) { "events must not be empty." }
                require(events.size <= config.maxBatchSize) { "events exceeds the configured batch limit." }
                events.map { parseEvent(it.jsonObject) }
            }
        }
    }

    private fun submit(
        exchange: HttpExchange,
        parse: (JsonElement) -> List<io.github.aeshen.observability.ObservabilityEvent>,
    ) {
        try {
            val body =
                exchange.requestBody.use { input ->
                    input.readNBytes(config.maxRequestBytes + OVER_LIMIT_READ_BYTES).also {
                        if (it.size > config.maxRequestBytes) {
                            throw RequestTooLargeException()
                        }
                    }
                }
            val events = parse(Json.parseToJsonElement(body.decodeToString()))
            events.forEach(observability::emit)
            exchange.respond(
                HTTP_ACCEPTED,
                buildJsonObject {
                    put("submissionId", UUID.randomUUID().toString())
                    put("acceptedCount", events.size)
                }.toString(),
                JSON_CONTENT_TYPE,
            )
        } catch (_: RequestTooLargeException) {
            exchange.problem(
                HTTP_PAYLOAD_TOO_LARGE,
                "payload-too-large",
                "The request exceeds the configured size limit.",
            )
        } catch (e: RequestValidationException) {
            exchange.problem(HTTP_BAD_REQUEST, "invalid-request", e.message ?: "The request is invalid.")
        } catch (e: IllegalArgumentException) {
            exchange.problem(HTTP_BAD_REQUEST, "invalid-request", e.message ?: "The request is invalid.")
        } catch (e: IllegalStateException) {
            exchange.problem(HTTP_SERVICE_UNAVAILABLE, "unavailable", e.message ?: "The sidecar cannot accept events.")
        }
    }

    private fun authorize(exchange: HttpExchange): Boolean {
        val expected = config.bearerToken ?: return true
        val supplied = exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ") ?: ""
        if (!MessageDigest.isEqual(expected.encodeToByteArray(), supplied.encodeToByteArray())) {
            exchange.problem(HTTP_UNAUTHORIZED, "unauthorized", "A valid bearer token is required.")
            return false
        }
        return true
    }

    private fun parseEvent(input: JsonObject): io.github.aeshen.observability.ObservabilityEvent {
        input.requireOnly("clientEventId", "name", "level", "timestamp", "message", "context", "payloadBase64", "error")
        val clientEventId =
            try {
                UUID.fromString(input.requiredString("clientEventId"))
            } catch (_: IllegalArgumentException) {
                throw RequestValidationException("clientEventId must be a UUID.")
            }
        val name = input.requiredString("name")
        val level = parseLevel(input.requiredString("level"))
        val timestamp = parseTimestamp(input.requiredString("timestamp"))
        val context = input["context"]?.jsonObject ?: throw RequestValidationException("context is required.")
        val payload = input["payloadBase64"]?.jsonPrimitive?.contentOrNull?.let(::decodePayload)
        val remoteError = input["error"]?.jsonObject?.toRemoteError()
        return event(SubmittedEventName(name)) {
            level(level)
            timestamp(timestamp)
            context(context.toObservabilityContext(clientEventId))
            input["message"]?.jsonPrimitive?.contentOrNull?.let(::message)
            payload?.let(::payload)
            remoteError?.let(::error)
        }
    }

    private fun parseLevel(value: String): EventLevel =
        try {
            EventLevel.valueOf(value)
        } catch (_: IllegalArgumentException) {
            throw RequestValidationException("level must be a supported event level.")
        }

    private fun parseTimestamp(value: String): Instant =
        try {
            Instant.parse(value)
        } catch (_: IllegalArgumentException) {
            throw RequestValidationException("timestamp must be an ISO-8601 instant.")
        }

    private fun decodePayload(value: String): ByteArray =
        try {
            Base64.getDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            throw RequestValidationException("payloadBase64 must be valid Base64.")
        }
}

private class SubmittedEventName(
    private val value: String,
) : EventName {
    override val name: String = value
    override val eventName: String? = value
}

private class RemoteEventException(
    type: String,
    message: String?,
    val remoteStacktrace: String,
) : RuntimeException("$type${message?.let { ": $it" } ?: ""}")

private class DynamicContextKey(
    override val keyName: String,
) : TypedKey<Any>

private class RequestValidationException(
    override val message: String,
) : IllegalArgumentException(message)

private class RequestTooLargeException : RuntimeException()

private fun JsonObject.toObservabilityContext(clientEventId: UUID): ObservabilityContext {
    val builder = ObservabilityContext.builder()
    builder.put(DynamicContextKey("client_event_id"), clientEventId.toString())
    forEach { (key, value) ->
        val primitive =
            value as? JsonPrimitive ?: throw RequestValidationException(
                "context.$key must be a scalar value.",
            )
        val scalar: Any =
            when {
                primitive.isString -> primitive.content
                primitive.booleanOrNull != null -> primitive.boolean
                primitive.longOrNull != null -> primitive.long
                primitive.doubleOrNull != null -> primitive.double
                else -> throw RequestValidationException("context.$key must be a scalar value.")
            }
        builder.put(DynamicContextKey(key), scalar)
    }
    return builder.build()
}

private fun JsonObject.toRemoteError(): Throwable {
    requireOnly("type", "message", "stacktrace")
    val type = requiredString("type")
    val stacktrace = requiredString("stacktrace")
    return RemoteEventException(type, this["message"]?.jsonPrimitive?.contentOrNull, stacktrace)
}

private fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        ?: throw RequestValidationException("$name is required.")

private fun JsonObject.requireOnly(vararg names: String): JsonObject {
    val allowed = names.toSet()
    val unknown = keys - allowed
    if (unknown.isNotEmpty()) {
        throw RequestValidationException("Unknown properties: ${unknown.sorted().joinToString()}.")
    }
    return this
}

private fun HttpExchange.problem(
    status: Int,
    type: String,
    detail: String,
) {
    respond(
        status,
        buildJsonObject {
            put("type", "https://github.com/janhaesen/observability/problems/$type")
            put("title", type.replace('-', ' '))
            put("status", status)
            put("detail", detail)
        }.toString(),
        PROBLEM_CONTENT_TYPE,
    )
}

private fun HttpExchange.respond(
    status: Int,
    body: String,
    contentType: String,
) {
    responseHeaders.set("Content-Type", contentType)
    sendResponseHeaders(status, body.encodeToByteArray().size.toLong())
    responseBody.use { it.write(body.encodeToByteArray()) }
}
