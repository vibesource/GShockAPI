package org.avmedia.gshockapi.io

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.avmedia.gshockapi.WatchInfo
import org.avmedia.gshockapi.ProgressEvents
import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.model.LocationIndicatorCommand
import org.avmedia.gshockapi.protocols.GgB100ProtocolPackets
import org.avmedia.gshockapi.utils.Utils

object LocationIndicatorIO {
    private var request: CompletableDeferred<ByteArray>? = null

    suspend fun requestCommand(): LocationIndicatorCommand {
        check(WatchInfo.hasLocationIndicator) { "Location Indicator is not supported by this watch" }
        IO.writeCmd(GetSetMode.SET, GgB100ProtocolPackets.locationIndicatorWatchName())

        val deferred = CompletableDeferred<ByteArray>()
        synchronized(this) { request = deferred }
        IO.request(GgB100ProtocolPackets.LOCATION_INDICATOR.toString(16))
        val packet = try {
            withTimeout(5_000) { deferred.await() }
        } finally {
            synchronized(this) { request = null }
        }
        return GgB100ProtocolPackets.locationIndicatorCommand(packet) ?: error(
            "Unexpected Location Indicator request: ${packet.joinToString(" ") { "%02x".format(it) }}"
        )
    }

    suspend fun respond(
        command: LocationIndicatorCommand,
        resultCode: Int,
        distanceMetres: Long = 0,
        bearingDegrees: Int = 0,
    ) {
        IO.writeCmd(
            GetSetMode.SET,
            GgB100ProtocolPackets.locationIndicatorResponse(
                command,
                resultCode,
                distanceMetres,
                bearingDegrees,
            ),
        )
    }

    fun onReceived(data: String) {
        val packet = runCatching { Utils.toIntArray(data).map(Int::toByte).toByteArray() }
            .getOrNull() ?: return
        val pending = synchronized(this) { request }
        if (pending != null) {
            pending.complete(packet)
        } else {
            GgB100ProtocolPackets.locationIndicatorCommand(packet)?.let { command ->
                ProgressEvents.onNext("LocationIndicatorCommandReceived", command)
            }
        }
    }
}
