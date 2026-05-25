package io.github.aeshen.observability.sink.decorator

import io.github.aeshen.observability.EventName
import io.github.aeshen.observability.ObservabilityContext
import io.github.aeshen.observability.ObservabilityEvent
import io.github.aeshen.observability.codec.EncodedEvent
import io.github.aeshen.observability.key.TypedKey
import io.github.aeshen.observability.sink.EventLevel
import io.github.aeshen.observability.sink.ObservabilitySink
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import kotlin.time.Instant

private const val DEFAULT_MAX_PENDING_EVENTS = 10_000
private const val DEFAULT_MAX_JOURNAL_BYTES = 64L * 1024 * 1024
private const val DEFAULT_RETRY_DELAY_MILLIS = 1_000L
private const val DEFAULT_CLOSE_TIMEOUT_MILLIS = 5_000L
private const val JOURNAL_HEADER_BYTES = 22
private const val JOURNAL_MAGIC = 0x4F425350
private const val JOURNAL_VERSION: Short = 1
private const val MIN_COMPACTION_BYTES = 64L * 1024L
private const val COMPACTION_DIVISOR = 4L

/**
 * Decorator that durably journals encoded events before delegated delivery so they can be replayed
 * after process restarts.
 *
 * The wrapped delegate is treated as the acknowledgement boundary. Use delegates whose `handle`
 * call returns only after delivery has been attempted synchronously.
 */
class PersistentObservabilitySink
    @JvmOverloads
    constructor(
        private val delegate: ObservabilitySink,
        directory: Path,
        private val maxPendingEvents: Int = DEFAULT_MAX_PENDING_EVENTS,
        private val maxJournalBytes: Long = DEFAULT_MAX_JOURNAL_BYTES,
        private val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MILLIS,
        private val closeTimeoutMillis: Long = DEFAULT_CLOSE_TIMEOUT_MILLIS,
        private val joinWorker: (Thread, Long) -> Unit = { thread, timeout -> thread.join(timeout) },
        private val sleepBeforeRetry: (Long) -> Unit = { millis -> Thread.sleep(millis) },
    ) : ObservabilitySink {
        init {
            require(maxPendingEvents > 0) { "maxPendingEvents must be greater than 0." }
            require(maxJournalBytes > 0) { "maxJournalBytes must be greater than 0." }
            require(retryDelayMillis >= 0) { "retryDelayMillis must be greater than or equal to 0." }
            require(closeTimeoutMillis > 0) { "closeTimeoutMillis must be greater than 0." }
        }

        private val journal = PersistentJournal(directory, maxPendingEvents, maxJournalBytes)
        private val accepting = AtomicBoolean(true)
        private val workerFailure = AtomicReference<Exception?>(null)
        private val worker =
            Thread({ runLoop() }, "observability-persistent-sink").apply {
                isDaemon = true
                start()
            }

        override fun handle(event: EncodedEvent) {
            val snapshot = event.snapshot()
            if (!accepting.get()) {
                check(false) { "PersistentObservabilitySink is closed." }
            }

            workerFailure.get()?.let { failure ->
                throw IllegalStateException("Persistent sink worker has failed.", failure)
            }

            journal.append(snapshot)
        }

        override fun close() {
            if (!accepting.compareAndSet(true, false)) {
                return
            }

            journal.markClosing()
            joinWorker(worker, closeTimeoutMillis)

            var closeFailure: Exception? = null
            if (worker.isAlive) {
                worker.interrupt()
            }

            workerFailure.get()?.let { failure ->
                closeFailure = IllegalStateException("Persistent sink worker failed before close.", failure)
            }

            try {
                delegate.close()
            } catch (e: IOException) {
                closeFailure = mergeCloseFailure(closeFailure, e)
            } catch (e: IllegalArgumentException) {
                closeFailure = mergeCloseFailure(closeFailure, e)
            } catch (e: IllegalStateException) {
                closeFailure = mergeCloseFailure(closeFailure, e)
            }

            try {
                journal.close()
            } catch (e: IOException) {
                closeFailure = mergeCloseFailure(closeFailure, e)
            } catch (e: IllegalStateException) {
                closeFailure = mergeCloseFailure(closeFailure, e)
            }

            closeFailure?.let { throw it }
        }

        private fun runLoop() {
            while (true) {
                val entry = journal.awaitHead() ?: return
                if (tryDeliver(entry)) {
                    journal.acknowledge(entry.sequence)
                    continue
                }

                if (!sleepForRetry()) {
                    return
                }
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private fun tryDeliver(entry: PendingRecord): Boolean =
            try {
                delegate.handle(entry.event.snapshot())
                true
            } catch (_: IllegalArgumentException) {
                false
            } catch (_: IllegalStateException) {
                false
            } catch (e: RuntimeException) {
                workerFailure.compareAndSet(null, e)
                accepting.set(false)
                journal.markClosing()
                false
            }

        private fun sleepForRetry(): Boolean =
            try {
                sleepBeforeRetry(retryDelayMillis)
                workerFailure.get() == null
            } catch (_: InterruptedException) {
                accepting.get()
            }

        private fun mergeCloseFailure(
            currentFailure: Exception?,
            newFailure: Exception,
        ): Exception = currentFailure?.apply { addSuppressed(newFailure) } ?: newFailure
    }

private class PersistentJournal(
    directory: Path,
    private val maxPendingEvents: Int,
    private val maxJournalBytes: Long,
) : Closeable {
    private val journalPath = directory.resolve("persistent-buffer.journal")
    private val statePath = directory.resolve("persistent-buffer.state")

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = java.lang.Object()
    private val pending = ArrayDeque<PendingRecord>()
    private var pendingBytes = 0L
    private var acknowledgedBytesSinceCompaction = 0L
    private var nextSequence: Long
    private var closing = false
    private var channel: FileChannel

    init {
        Files.createDirectories(directory)
        val recovery = recover()
        pending += recovery.pending
        pendingBytes = recovery.pendingBytes
        nextSequence = recovery.nextSequence
        require(pending.size <= maxPendingEvents) {
            "Recovered journal exceeds maxPendingEvents=$maxPendingEvents."
        }
        require(pendingBytes <= maxJournalBytes) {
            "Recovered journal exceeds maxJournalBytes=$maxJournalBytes."
        }
        channel =
            FileChannel.open(
                journalPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
            ).apply {
                position(size())
            }
    }

    fun append(event: EncodedEvent) {
        val sequence =
            synchronized(lock) {
                val allocated = nextSequence
                nextSequence += 1
                allocated
            }
        val encodedRecord = PersistentJournalCodec.encode(sequence, event)
        synchronized(lock) {
            check(!closing) { "Persistent journal is closed." }
            check(pending.size < maxPendingEvents) {
                "Persistent journal is full (maxPendingEvents=$maxPendingEvents)."
            }
            check(pendingBytes + encodedRecord.size <= maxJournalBytes) {
                "Persistent journal exceeded maxJournalBytes=$maxJournalBytes."
            }

            appendBytes(encodedRecord)
            pending += PendingRecord(sequence = sequence, event = event, recordSize = encodedRecord.size.toLong())
            pendingBytes += encodedRecord.size.toLong()
            lock.notifyAll()
        }
    }

    fun awaitHead(): PendingRecord? =
        synchronized(lock) {
            while (pending.isEmpty()) {
                if (closing) {
                    return null
                }
                try {
                    lock.wait()
                } catch (_: InterruptedException) {
                    return null
                }
            }
            pending.first()
        }

    fun acknowledge(sequence: Long) {
        synchronized(lock) {
            val head =
                pending.removeFirstOrNull()
                    ?: error("Cannot acknowledge sequence $sequence because the journal is empty.")
            check(head.sequence == sequence) {
                "Attempted to acknowledge sequence $sequence while head is ${head.sequence}."
            }

            pendingBytes -= head.recordSize
            acknowledgedBytesSinceCompaction += head.recordSize
            persistAcknowledgement(sequence)
            compactIfNeeded()
        }
    }

    fun markClosing() {
        synchronized(lock) {
            closing = true
            lock.notifyAll()
        }
    }

    override fun close() {
        synchronized(lock) {
            channel.close()
        }
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private fun recover(): RecoveryResult {
        if (!Files.exists(journalPath)) {
            return RecoveryResult(emptyList(), 0L, readAcknowledgedSequence() + 1L)
        }

        val acknowledgedSequence = readAcknowledgedSequence()
        val recovered = mutableListOf<PendingRecord>()
        var recoveredBytes = 0L
        var position = 0L
        var lastSequence = acknowledgedSequence
        FileChannel.open(
            journalPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
        ).use { file ->
            val size = file.size()
            while (position < size) {
                val record = PersistentJournalCodec.decode(file, position, size)
                if (record == null) {
                    file.truncate(position)
                    break
                }
                position += record.totalBytes
                lastSequence = record.sequence
                if (record.sequence <= acknowledgedSequence) {
                    continue
                }
                recovered +=
                    PendingRecord(
                        sequence = record.sequence,
                        event = record.event,
                        recordSize = record.totalBytes,
                    )
                recoveredBytes += record.totalBytes
            }
        }
        return RecoveryResult(recovered, recoveredBytes, lastSequence + 1L)
    }

    private fun readAcknowledgedSequence(): Long {
        if (!Files.exists(statePath)) {
            return -1L
        }

        DataInputStream(Files.newInputStream(statePath)).use { input ->
            return input.readLong()
        }
    }

    private fun persistAcknowledgement(sequence: Long) {
        val tmpState = statePath.resolveSibling("${statePath.fileName}.tmp")
        DataOutputStream(
            Files.newOutputStream(
                tmpState,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            ),
        ).use { output ->
            output.writeLong(sequence)
            output.flush()
        }
        moveAtomically(tmpState, statePath)
    }

    private fun appendBytes(bytes: ByteArray) {
        channel.position(channel.size())
        writeFully(channel, ByteBuffer.wrap(bytes))
        channel.force(true)
    }

    private fun compactIfNeeded() {
        if (pending.isEmpty()) {
            channel.truncate(0)
            channel.force(true)
            channel.position(0)
            acknowledgedBytesSinceCompaction = 0L
            return
        }

        val threshold = maxOf(MIN_COMPACTION_BYTES, maxJournalBytes / COMPACTION_DIVISOR)
        if (acknowledgedBytesSinceCompaction < threshold) {
            return
        }

        val tmpJournal = journalPath.resolveSibling("${journalPath.fileName}.tmp")
        FileChannel.open(
            tmpJournal,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        ).use { tempChannel ->
            pending.forEach { record ->
                val bytes = PersistentJournalCodec.encode(record.sequence, record.event)
                writeFully(tempChannel, ByteBuffer.wrap(bytes))
            }
            tempChannel.force(true)
        }

        channel.close()
        moveAtomically(tmpJournal, journalPath)
        channel =
            FileChannel.open(
                journalPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
            ).apply {
                position(size())
            }
        acknowledgedBytesSinceCompaction = 0L
    }

    private fun moveAtomically(
        source: Path,
        target: Path,
    ) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

private data class RecoveryResult(
    val pending: List<PendingRecord>,
    val pendingBytes: Long,
    val nextSequence: Long,
)

private data class PendingRecord(
    val sequence: Long,
    val event: EncodedEvent,
    val recordSize: Long,
)

@Suppress("TooManyFunctions")
private object PersistentJournalCodec {
    fun encode(
        sequence: Long,
        event: EncodedEvent,
    ): ByteArray {
        val payload = encodeBody(event)
        val checksum = CRC32().apply { update(payload) }.value.toInt()
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(JOURNAL_MAGIC)
            data.writeShort(JOURNAL_VERSION.toInt())
            data.writeLong(sequence)
            data.writeInt(payload.size)
            data.writeInt(checksum)
            data.write(payload)
        }
        return output.toByteArray()
    }

    fun decode(
        channel: FileChannel,
        position: Long,
        fileSize: Long,
    ): DecodedRecord? {
        if (fileSize - position < JOURNAL_HEADER_BYTES) {
            return null
        }

        val header = ByteBuffer.allocate(JOURNAL_HEADER_BYTES)
        readFully(channel, header, position)
        header.flip()

        val magic = header.int
        check(magic == JOURNAL_MAGIC) {
            "Persistent journal is corrupted at byte offset $position."
        }

        val version = header.short
        check(version == JOURNAL_VERSION) {
            "Unsupported persistent journal version $version."
        }

        val sequence = header.long
        val payloadSize = header.int
        val checksum = header.int
        check(payloadSize >= 0) {
            "Persistent journal declared a negative payload size at sequence $sequence."
        }

        val totalBytes = JOURNAL_HEADER_BYTES + payloadSize.toLong()
        if (fileSize - position < totalBytes) {
            return null
        }

        val payload = ByteBuffer.allocate(payloadSize)
        readFully(channel, payload, position + JOURNAL_HEADER_BYTES)
        val bytes = payload.array()
        val actualChecksum = CRC32().apply { update(bytes) }.value.toInt()
        check(actualChecksum == checksum) {
            "Persistent journal checksum mismatch at sequence $sequence."
        }

        return DecodedRecord(
            sequence = sequence,
            event = decodeBody(bytes),
            totalBytes = totalBytes,
        )
    }

    private fun encodeBody(event: EncodedEvent): ByteArray =
        ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                val original = event.original
                writeString(data, original.name.name)
                writeNullableString(data, original.name.eventName)
                writeString(data, original.level.name)
                data.writeLong(original.timestamp.epochSeconds)
                data.writeInt(original.timestamp.nanosecondsOfSecond)
                writeNullableString(data, original.message)
                writeNullableBytes(data, original.payload)
                writeNullableThrowable(data, original.error)
                writeContext(data, original.context)
                writeMetadata(data, event.metadata)
                writeBytes(data, event.encoded)
            }
        }.toByteArray()

    private fun decodeBody(bytes: ByteArray): EncodedEvent =
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            val eventName = PersistedEventName(name = readString(data), eventName = readNullableString(data))
            val level = EventLevel.valueOf(readString(data))
            val timestamp = Instant.fromEpochSeconds(data.readLong(), data.readInt().toLong())
            val message = readNullableString(data)
            val payload = readNullableBytes(data)
            val error = readNullableThrowable(data)
            val context = readContext(data)
            val metadata = readMetadata(data)
            val encoded = readBytes(data)
            EncodedEvent(
                original =
                    ObservabilityEvent.EventBuilder(eventName)
                        .level(level)
                        .timestamp(timestamp)
                        .context(context)
                        .apply {
                            message?.let(::message)
                            payload?.let(::payload)
                            error?.let(::error)
                        }.build(),
                encoded = encoded,
                metadata = metadata,
            )
        }

    private fun writeContext(
        data: DataOutputStream,
        context: ObservabilityContext,
    ) {
        val entries = context.asMap().entries.toList()
        data.writeInt(entries.size)
        entries.forEach { (key, value) ->
            writeString(data, key.keyName)
            writeNullableScalar(data, value)
        }
    }

    private fun readContext(data: DataInputStream): ObservabilityContext {
        val count = data.readInt()
        val builder = ObservabilityContext.builder()
        repeat(count) {
            val keyName = readString(data)
            val value = readNullableScalar(data)
            if (value != null) {
                builder.put(PersistedTypedKey<Any?>(keyName), value)
            }
        }
        return builder.build()
    }

    private fun writeMetadata(
        data: DataOutputStream,
        metadata: Map<String, Any?>,
    ) {
        data.writeInt(metadata.size)
        metadata.forEach { (key, value) ->
            writeString(data, key)
            writeNullableScalar(data, value)
        }
    }

    private fun readMetadata(data: DataInputStream): MutableMap<String, Any?> {
        val count = data.readInt()
        val metadata = linkedMapOf<String, Any?>()
        repeat(count) {
            metadata[readString(data)] = readNullableScalar(data)
        }
        return metadata
    }

    private fun writeNullableThrowable(
        data: DataOutputStream,
        throwable: Throwable?,
    ) {
        data.writeBoolean(throwable != null)
        if (throwable == null) {
            return
        }

        writeString(data, throwable.javaClass.name)
        writeNullableString(data, throwable.message)
        writeString(data, throwable.stackTraceToString())
    }

    private fun readNullableThrowable(data: DataInputStream): Throwable? {
        if (!data.readBoolean()) {
            return null
        }

        return ReplayedObservabilityException(
            originalClassName = readString(data),
            originalMessage = readNullableString(data),
            originalStacktrace = readString(data),
        )
    }

    private fun writeNullableScalar(
        data: DataOutputStream,
        value: Any?,
    ) {
        when (value) {
            null -> data.writeByte(ValueTag.NULL.ordinal)
            is String -> {
                data.writeByte(ValueTag.STRING.ordinal)
                writeString(data, value)
            }
            is Boolean -> {
                data.writeByte(ValueTag.BOOLEAN.ordinal)
                data.writeBoolean(value)
            }
            is Byte -> {
                data.writeByte(ValueTag.BYTE.ordinal)
                data.writeByte(value.toInt())
            }
            is Short -> {
                data.writeByte(ValueTag.SHORT.ordinal)
                data.writeShort(value.toInt())
            }
            is Int -> {
                data.writeByte(ValueTag.INT.ordinal)
                data.writeInt(value)
            }
            is Long -> {
                data.writeByte(ValueTag.LONG.ordinal)
                data.writeLong(value)
            }
            is Float -> {
                data.writeByte(ValueTag.FLOAT.ordinal)
                data.writeFloat(value)
            }
            is Double -> {
                data.writeByte(ValueTag.DOUBLE.ordinal)
                data.writeDouble(value)
            }
            else -> throw IllegalArgumentException(
                "PersistentObservabilitySink only supports scalar String/Boolean/number/null values. " +
                    "Unsupported value type: ${value::class.qualifiedName}",
            )
        }
    }

    private fun readNullableScalar(data: DataInputStream): Any? =
        when (ValueTag.entries[data.readUnsignedByte()]) {
            ValueTag.NULL -> null
            ValueTag.STRING -> readString(data)
            ValueTag.BOOLEAN -> data.readBoolean()
            ValueTag.BYTE -> data.readByte()
            ValueTag.SHORT -> data.readShort()
            ValueTag.INT -> data.readInt()
            ValueTag.LONG -> data.readLong()
            ValueTag.FLOAT -> data.readFloat()
            ValueTag.DOUBLE -> data.readDouble()
        }

    private fun writeNullableString(
        data: DataOutputStream,
        value: String?,
    ) {
        data.writeBoolean(value != null)
        value?.let { writeString(data, it) }
    }

    private fun readNullableString(data: DataInputStream): String? =
        if (data.readBoolean()) {
            readString(data)
        } else {
            null
        }

    private fun writeString(
        data: DataOutputStream,
        value: String,
    ) {
        writeBytes(data, value.toByteArray(Charsets.UTF_8))
    }

    private fun readString(data: DataInputStream): String = readBytes(data).toString(Charsets.UTF_8)

    private fun writeNullableBytes(
        data: DataOutputStream,
        value: ByteArray?,
    ) {
        data.writeBoolean(value != null)
        value?.let { writeBytes(data, it) }
    }

    private fun readNullableBytes(data: DataInputStream): ByteArray? =
        if (data.readBoolean()) {
            readBytes(data)
        } else {
            null
        }

    private fun writeBytes(
        data: DataOutputStream,
        value: ByteArray,
    ) {
        data.writeInt(value.size)
        data.write(value)
    }

    private fun readBytes(data: DataInputStream): ByteArray {
        val size = data.readInt()
        check(size >= 0) { "Persistent journal encountered a negative byte-array length." }
        return data.readNBytes(size).also { bytes ->
            check(bytes.size == size) { "Persistent journal ended unexpectedly while reading record data." }
        }
    }
}

private data class DecodedRecord(
    val sequence: Long,
    val event: EncodedEvent,
    val totalBytes: Long,
)

private enum class ValueTag {
    NULL,
    STRING,
    BOOLEAN,
    BYTE,
    SHORT,
    INT,
    LONG,
    FLOAT,
    DOUBLE,
}

private data class PersistedEventName(
    override val name: String,
    override val eventName: String?,
) : EventName

private data class PersistedTypedKey<T>(
    override val keyName: String,
) : TypedKey<T>

internal class ReplayedObservabilityException internal constructor(
    val originalClassName: String,
    val originalMessage: String?,
    val originalStacktrace: String,
) : RuntimeException(originalMessage) {
    override fun fillInStackTrace(): Throwable = this

    override fun toString(): String = originalMessage?.let { "$originalClassName: $it" } ?: originalClassName
}

private fun EncodedEvent.snapshot(): EncodedEvent =
    EncodedEvent(
        original = original.snapshot(),
        encoded = encoded.copyOf(),
        metadata = metadata.toMutableMap(),
    )

private fun ObservabilityEvent.snapshot(): ObservabilityEvent =
    ObservabilityEvent.EventBuilder(PersistedEventName(name.name, name.eventName))
        .level(level)
        .timestamp(timestamp)
        .context(context.snapshot())
        .apply {
            message?.let(::message)
            payload?.let { payload(it.copyOf()) }
            error?.let(::error)
        }.build()

private fun ObservabilityContext.snapshot(): ObservabilityContext {
    val builder = ObservabilityContext.builder()
    asMap().forEach { (key, value) -> builder.put(PersistedTypedKey<Any?>(key.keyName), value) }
    return builder.build()
}

private fun readFully(
    channel: FileChannel,
    buffer: ByteBuffer,
    startPosition: Long,
) {
    var position = startPosition
    while (buffer.hasRemaining()) {
        val read = channel.read(buffer, position)
        check(read >= 0) { "Unexpected end of persistent journal." }
        position += read.toLong()
    }
}

private fun writeFully(
    channel: FileChannel,
    buffer: ByteBuffer,
) {
    while (buffer.hasRemaining()) {
        channel.write(buffer)
    }
}
