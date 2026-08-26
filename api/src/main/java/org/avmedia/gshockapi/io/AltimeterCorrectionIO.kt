package org.avmedia.gshockapi.io

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.avmedia.gshockapi.WatchInfo
import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.protocols.GgB100ProtocolPackets
import org.avmedia.gshockapi.utils.Utils

object AltimeterCorrectionIO {
    private var response: CompletableDeferred<ByteArray>? = null

    suspend fun correct(altitudeMetres: Int?): Boolean {
        check(WatchInfo.hasAltimeterCorrection) {
            "Altimeter correction is not supported by this watch"
        }
        val deferred = CompletableDeferred<ByteArray>()
        synchronized(this) { response = deferred }
        try {
            IO.writeCmd(
                GetSetMode.SET,
                GgB100ProtocolPackets.altimeterCorrection(altitudeMetres),
            )
            val packet = withTimeout(8_000) { deferred.await() }
            return GgB100ProtocolPackets.altimeterCorrectionSucceeded(packet) ?: error(
                "Unexpected altimeter correction response: " +
                    packet.joinToString(" ") { "%02x".format(it) },
            )
        } finally {
            synchronized(this) { response = null }
        }
    }

    fun onReceived(data: String) {
        val packet = runCatching { Utils.toIntArray(data).map(Int::toByte).toByteArray() }
            .getOrNull() ?: return
        synchronized(this) { response }?.complete(packet)
    }
}
