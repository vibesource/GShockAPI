package org.avmedia.gshockapi.io

import org.avmedia.gshockapi.WatchInfo
import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.protocols.GgB100ProtocolPackets
import timber.log.Timber

object AltimeterCorrectionIO {
    suspend fun correct(altitudeMetres: Int?): Boolean {
        check(WatchInfo.hasAltimeterCorrection) {
            "Altimeter correction is not supported by this watch"
        }
        return runCatching {
            IO.writeCmdAndWait(
                GetSetMode.SET,
                GgB100ProtocolPackets.altimeterCorrection(altitudeMetres),
            )
            // Register 0x36 is write-only and has no application-level response. Its Android
            // GATT completion callback is therefore the strongest available delivery evidence.
            true
        }.onFailure { error ->
            Timber.e(error, "Altimeter correction GATT write did not complete")
        }.getOrDefault(false)
    }
}
