package org.avmedia.gshockapi.io

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.avmedia.gshockapi.WatchInfo
import org.avmedia.gshockapi.ProgressEvents
import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.model.LocationIndicatorFailure
import org.avmedia.gshockapi.protocols.GgB100ProtocolPackets
import org.avmedia.gshockapi.utils.Utils

object LocationIndicatorIO {
    private var request: CompletableDeferred<ByteArray>? = null

    suspend fun complete(distanceMetres: Long, bearingDegrees: Int) {
        exchange(GgB100ProtocolPackets.locationIndicatorResult(distanceMetres, bearingDegrees))
    }

    suspend fun fail(reason: LocationIndicatorFailure) {
        exchange(GgB100ProtocolPackets.locationIndicatorFailure(reason.code))
    }

    suspend fun update(distanceMetres: Long, bearingDegrees: Int) {
        IO.writeCmd(
            GetSetMode.SET,
            GgB100ProtocolPackets.locationIndicatorResult(distanceMetres, bearingDegrees),
        )
    }

    suspend fun updateFailure(reason: LocationIndicatorFailure) {
        IO.writeCmd(GetSetMode.SET, GgB100ProtocolPackets.locationIndicatorFailure(reason.code))
    }

    private suspend fun exchange(result: ByteArray) {
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
        check(GgB100ProtocolPackets.isLocationIndicatorCalculationRequest(packet)) {
            "Unexpected Location Indicator request: ${packet.joinToString(" ") { "%02x".format(it) }}"
        }
        IO.writeCmd(GetSetMode.SET, result)
    }

    fun onReceived(data: String) {
        val packet = runCatching { Utils.toIntArray(data).map(Int::toByte).toByteArray() }
            .getOrNull() ?: return
        val pending = synchronized(this) { request }
        if (pending != null) {
            pending.complete(packet)
        } else if (GgB100ProtocolPackets.isLocationIndicatorCalculationRequest(packet)) {
            ProgressEvents.onNext("LocationIndicatorRefreshRequested")
        }
    }
}
