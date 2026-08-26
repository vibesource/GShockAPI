package org.avmedia.gshockapi.io

import android.os.Build
import androidx.annotation.RequiresApi
import org.avmedia.gshockapi.WatchInfo
import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.model.MissionLogData
import org.avmedia.gshockapi.protocols.GgB100ProtocolPackets
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Complete, watch-initiated Module 5594 Mission Log download and commit sequence. */
@RequiresApi(Build.VERSION_CODES.O)
object MissionLogIO {
    suspend fun download(
        latitude: Double,
        longitude: Double,
        timeZone: String,
        now: Instant = Instant.now(),
    ): MissionLogData {
        check(WatchInfo.hasMissionLog) { "Mission Log is not supported by this watch" }
        val zoneId = ZoneId.of(timeZone)

        val statePacket = FeatureRequestIO.request(byteArrayOf(GgB100ProtocolPackets.MISSION_LOG.toByte()))
        val state = GgB100ProtocolPackets.parseMissionLogState(statePacket)
            ?: error("invalid Module 5594 Mission Log state")
        check(
            state.command in setOf(
                GgB100ProtocolPackets.MissionLogState.Command.START,
                GgB100ProtocolPackets.MissionLogState.Command.CONTINUE,
                GgB100ProtocolPackets.MissionLogState.Command.STOP,
            ),
        ) {
            "no watch-initiated Mission Log transition is pending"
        }

        val altitude = ConvoyTransferIO.request(GgB100ProtocolPackets.DRSP_ALTITUDE)
        val exercise = ConvoyTransferIO.request(GgB100ProtocolPackets.DRSP_EXERCISE)
        completeSession(latitude, longitude, zoneId, now)

        Timber.i(
            "Mission Log downloaded: command=${state.command}, " +
                "altitude=${altitude.size}B, exercise=${exercise.size}B",
        )
        return MissionLogData(state, altitude, exercise)
    }

    private suspend fun completeSession(
        latitude: Double,
        longitude: Double,
        zoneId: ZoneId,
        now: Instant,
    ) {
        request(0x20)
        request(0x28)
        request(0x20)
        request(0x28)

        readAndEcho(byteArrayOf(0x1D))

        val dst0 = request(0x1E, 0x00)
        val dst1 = request(0x1E, 0x01)
        require(dst0.size == 7 && dst1.size == 7) { "unexpected timezone record length" }
        write(dst0)
        write(dst1)

        val priorWorld = request(0x24, 0x01)

        val homeOffsetMinutes = zoneId.rules.getStandardOffset(now).totalSeconds / 60
        val home = GgB100ProtocolPackets.locationAndRadioInformation(
            slot = 0,
            latitude = latitude,
            longitude = longitude,
            radioId = GgB100ProtocolPackets.radioIdForUtcOffsetMinutes(homeOffsetMinutes),
        )
        val worldOffsetMinutes = dst1[4].toInt() * 15
        val world = GgB100ProtocolPackets.locationReadToWrite(
            priorWorld,
            GgB100ProtocolPackets.radioIdForUtcOffsetMinutes(worldOffsetMinutes),
        )
        write(home)
        write(world)

        readAndEcho(byteArrayOf(0x1F, 0x00))
        readAndEcho(byteArrayOf(0x1F, 0x01))
        readAndEcho(byteArrayOf(0x2F))

        val localNow = ZonedDateTime.ofInstant(now, zoneId).toLocalDateTime()
        write(TimeIOFunctional.buildTimeCommand(localNow))
    }

    private suspend fun request(vararg bytes: Int): ByteArray {
        val packet = ByteArray(bytes.size) { index -> bytes[index].toByte() }
        return FeatureRequestIO.request(packet)
    }

    private suspend fun readAndEcho(request: ByteArray): ByteArray =
        FeatureRequestIO.request(request).also(::write)

    private fun write(packet: ByteArray) {
        IO.writeCmd(GetSetMode.SET, packet)
    }
}
