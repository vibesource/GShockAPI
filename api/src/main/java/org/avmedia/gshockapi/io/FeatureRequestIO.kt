package org.avmedia.gshockapi.io

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.avmedia.gshockapi.ble.GetSetMode
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Source-aware request/reply helper for raw register traffic on characteristics 0x2c/0x2d. */
object FeatureRequestIO {
    private data class Pending(
        val expectedPrefix: ByteArray,
        val result: CompletableDeferred<ByteArray>,
    )

    private var pending: Pending? = null

    suspend fun request(
        request: ByteArray,
        expectedPrefix: ByteArray = request,
        timeout: Duration = 5.seconds,
    ): ByteArray {
        require(request.isNotEmpty()) { "feature request cannot be empty" }
        require(expectedPrefix.isNotEmpty()) { "expected prefix cannot be empty" }
        val deferred = CompletableDeferred<ByteArray>()
        val transaction = Pending(expectedPrefix.copyOf(), deferred)
        synchronized(this) {
            check(pending == null) { "a feature request is already active" }
            pending = transaction
        }

        return try {
            IO.writeCmd(GetSetMode.GET, request)
            withTimeout(timeout) { deferred.await() }
        } finally {
            synchronized(this) {
                if (pending === transaction) pending = null
            }
            deferred.cancel()
        }
    }

    /** Returns true when this packet belongs to the active raw request. */
    fun onReceived(packet: ByteArray): Boolean {
        val transaction = synchronized(this) { pending } ?: return false
        if (!packet.startsWith(transaction.expectedPrefix)) return false
        transaction.result.complete(packet.copyOf())
        return true
    }

    fun cancel(reason: String = "feature request cancelled") {
        val transaction = synchronized(this) { pending.also { pending = null } }
        transaction?.result?.completeExceptionally(IllegalStateException(reason))
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }
}
