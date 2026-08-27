package org.avmedia.gshockapi.io

import org.avmedia.gshockapi.WatchInfo
import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.protocols.GgB100ProtocolPackets

object AltimeterCorrectionIO {
    fun correct(altitudeMetres: Int?): Boolean {
        check(WatchInfo.hasAltimeterCorrection) {
            "Altimeter correction is not supported by this watch"
        }
        IO.writeCmd(
            GetSetMode.SET,
            GgB100ProtocolPackets.altimeterCorrection(altitudeMetres),
        )
        // Register 0x36 carries the phone-side result and is write-only. The watch does not
        // echo an acknowledgement; completion is therefore the successful queueing of the write.
        return true
    }
}
