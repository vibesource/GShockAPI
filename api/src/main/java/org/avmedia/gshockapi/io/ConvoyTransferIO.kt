package org.avmedia.gshockapi.io

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.avmedia.gshockapi.ble.GetSetMode
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Pure, synchronized state machine for Casio DRSP/Convoy bulk transfers. */
class ConvoyTransferAssembler(
    private val maxTransferSize: Int = 1024 * 1024,
) {
    sealed interface Event {
        data object Ignored : Event
        data class HeaderAccepted(val category: Int, val expectedLength: Int) : Event
        data class ChunkAccepted(val receivedLength: Int, val expectedLength: Int) : Event
        data class Completed(val category: Int, val payload: ByteArray) : Event
        data class Rejected(val reason: String) : Event
    }

    private sealed interface State {
        data object Idle : State
        data class AwaitingHeader(val category: Int) : State
        data class Receiving(
            val category: Int,
            val expectedLength: Int,
            val payload: ByteArray,
            val receivedLength: Int,
        ) : State
    }

    private var state: State = State.Idle

    @Synchronized
    fun start(category: Int) {
        require(category in 0..0xFF) { "category must fit one byte" }
        check(state == State.Idle) { "a convoy transfer is already active" }
        state = State.AwaitingHeader(category)
    }

    @Synchronized
    fun onDrsp(packet: ByteArray): Event {
        val current = state
        if (current == State.Idle) return Event.Ignored
        if (packet.size != 7 || packet[0].toInt() and 0xFF != START_TRANSACTION) {
            return reject("invalid DRSP transfer header")
        }

        val category = packet[1].toInt() and 0xFF
        val requestedCategory = when (current) {
            is State.AwaitingHeader -> current.category
            is State.Receiving -> current.category
            State.Idle -> return Event.Ignored
        }
        if (category != requestedCategory) {
            return reject(
                "DRSP category 0x${category.toString(16)} does not match requested " +
                    "0x${requestedCategory.toString(16)}",
            )
        }

        val length = (packet[2].toInt() and 0xFF) or
            ((packet[3].toInt() and 0xFF) shl 8) or
            ((packet[4].toInt() and 0xFF) shl 16)
        if (length !in 1..maxTransferSize) {
            return reject("invalid announced transfer length $length")
        }
        if (current is State.Receiving) {
            return reject("duplicate DRSP transfer header")
        }

        state = State.Receiving(category, length, ByteArray(length), 0)
        return Event.HeaderAccepted(category, length)
    }

    @Synchronized
    fun onConvoy(fragment: ByteArray): Event {
        val current = state
        if (current == State.Idle) return Event.Ignored
        if (current is State.AwaitingHeader) {
            return reject("convoy data arrived before its DRSP header")
        }
        current as State.Receiving
        if (fragment.isEmpty()) return reject("empty convoy fragment")

        val newLength = current.receivedLength + fragment.size
        if (newLength > current.expectedLength) {
            return reject(
                "convoy payload exceeded announced length " +
                    "($newLength/${current.expectedLength})",
            )
        }

        fragment.copyInto(current.payload, destinationOffset = current.receivedLength)
        if (newLength == current.expectedLength) {
            state = State.Idle
            return Event.Completed(current.category, current.payload.copyOf())
        }

        state = current.copy(receivedLength = newLength)
        return Event.ChunkAccepted(newLength, current.expectedLength)
    }

    @Synchronized
    fun cancel() {
        state = State.Idle
    }

    @Synchronized
    fun isActive(): Boolean = state != State.Idle

    private fun reject(reason: String): Event.Rejected {
        state = State.Idle
        return Event.Rejected(reason)
    }

    private companion object {
        const val START_TRANSACTION = 0x00
    }
}

/** Imperative coroutine wrapper that performs writes and completes the transfer result. */
object ConvoyTransferIO {
    private val assembler = ConvoyTransferAssembler()
    private var result: CompletableDeferred<ByteArray>? = null

    suspend fun request(category: Int, timeout: Duration = 10.seconds): ByteArray {
        val deferred = CompletableDeferred<ByteArray>()
        synchronized(this) {
            check(result == null) { "a convoy transfer is already active" }
            assembler.start(category)
            result = deferred
        }

        return try {
            IO.writeCmd(GetSetMode.DATA_REQUEST, command(START_TRANSACTION, category))
            withTimeout(timeout) { deferred.await() }
        } finally {
            synchronized(this) {
                if (result === deferred) {
                    assembler.cancel()
                    result = null
                    deferred.cancel()
                }
            }
        }
    }

    fun onDrspReceived(packet: ByteArray) {
        handle(assembler.onDrsp(packet))
    }

    fun onConvoyReceived(fragment: ByteArray) {
        handle(assembler.onConvoy(fragment))
    }

    fun cancel(reason: String = "convoy transfer cancelled") {
        val deferred = synchronized(this) {
            assembler.cancel()
            result.also { result = null }
        }
        deferred?.completeExceptionally(ConvoyTransferException(reason))
    }

    private fun handle(event: ConvoyTransferAssembler.Event) {
        when (event) {
            ConvoyTransferAssembler.Event.Ignored -> Unit
            is ConvoyTransferAssembler.Event.HeaderAccepted ->
                Timber.d(
                    "Convoy transfer category=0x${event.category.toString(16)} " +
                        "announced=${event.expectedLength}B",
                )
            is ConvoyTransferAssembler.Event.ChunkAccepted ->
                Timber.d("Convoy transfer received=${event.receivedLength}/${event.expectedLength}B")
            is ConvoyTransferAssembler.Event.Completed -> {
                val deferred = synchronized(this) { result }
                if (deferred == null) return
                IO.writeCmd(GetSetMode.DATA_REQUEST, command(END_TRANSACTION, event.category))
                deferred.complete(event.payload)
            }
            is ConvoyTransferAssembler.Event.Rejected -> cancel(event.reason)
        }
    }

    private fun command(command: Int, category: Int): ByteArray =
        byteArrayOf(command.toByte(), category.toByte(), 0x00, 0x00, 0x00)

    private const val START_TRANSACTION = 0x00
    private const val END_TRANSACTION = 0x04
}

class ConvoyTransferException(message: String) : IllegalStateException(message)
