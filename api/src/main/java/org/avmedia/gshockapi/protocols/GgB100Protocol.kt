package org.avmedia.gshockapi.protocols

import android.os.Build
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.DateTimeException
import java.time.LocalDateTime

/** Module 5594 protocol profile for the GG-B100. */
@RequiresApi(Build.VERSION_CODES.O)
open class GgB100Protocol : StandardProtocol() {
    companion object : GgB100Protocol()
}

/** Pure packet codecs for Module 5594 commands verified against live captures. */
object GgB100ProtocolPackets {
    const val LOCATION_INDICATOR = 0x35
    const val CORRECT_SENSOR = 0x36
    const val MISSION_LOG = 0x37

    const val DRSP_EXERCISE = 0x11
    const val DRSP_ALTITUDE = 0x19

    data class MissionLogState(
        val command: Command,
        val timestampUtc: LocalDateTime?,
    ) {
        enum class Command(val code: Int) {
            NO_FUNCTION(0), START(1), CONTINUE(2), STOP(3), UNKNOWN(-1);

            companion object {
                fun fromCode(code: Int): Command = entries.firstOrNull { it.code == code } ?: UNKNOWN
            }
        }
    }

    data class DrspHeader(val category: Int, val length: Int)

    fun locationIndicatorResult(distanceMetres: Long, bearingDegrees: Int): ByteArray {
        require(distanceMetres in 0..0xFFFF_FFFFL) { "distance must fit an unsigned 32-bit value" }
        require(bearingDegrees in 0..360) { "bearing must be between 0 and 360 degrees" }
        return byteArrayOf(LOCATION_INDICATOR.toByte(), 0x02, 0x00) +
            littleEndian(distanceMetres, 4) + littleEndian(bearingDegrees.toLong(), 2)
    }

    fun altimeterCorrection(altitudeMetres: Int?): ByteArray {
        if (altitudeMetres == null) {
            return byteArrayOf(CORRECT_SENSOR.toByte(), 0x01, 0x01, 0x00, 0x00)
        }
        require(altitudeMetres in -32768..32767) {
            "altitude must fit a signed 16-bit value"
        }
        return byteArrayOf(CORRECT_SENSOR.toByte(), 0x00, 0x01) +
            littleEndian(altitudeMetres.toLong() and 0xFFFFL, 2)
    }

    /**
     * Builds the Module 5594 Location & Radio Information register (0x24).
     * Coordinates are IEEE-754 doubles in big-endian order on writes.
     */
    fun locationAndRadioInformation(
        slot: Int,
        latitude: Double,
        longitude: Double,
        radioId: Int,
    ): ByteArray {
        require(slot in 0..1) { "Module 5594 location slot must be 0 or 1" }
        require(latitude.isFinite() && latitude in -90.0..90.0) { "invalid latitude" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "invalid longitude" }
        require(radioId in 0..0xFF) { "radio ID must fit one byte" }

        return ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
            .put(0x24)
            .put(slot.toByte())
            .put(0x01)
            .putDouble(latitude)
            .putDouble(longitude)
            .put(radioId.toByte())
            .array()
    }

    /**
     * Converts a watch-read 0x24 record to its write representation and restores
     * the radio ID, which is not retained in the read response.
     */
    fun locationReadToWrite(packet: ByteArray, radioId: Int): ByteArray {
        require(packet.size == 20 && packet[0].toInt() and 0xFF == 0x24) {
            "invalid Module 5594 location record"
        }
        require(packet[1].toInt() and 0xFF in 0..1) { "invalid Module 5594 location slot" }
        val hasPosition = packet[2].toInt() and 0xFF
        require(hasPosition in 0..1) { "invalid Module 5594 position flag" }
        require(radioId in 0..0xFF) { "radio ID must fit one byte" }

        if (hasPosition == 0) {
            require(packet.copyOfRange(3, packet.size).all { it == 0.toByte() }) {
                "invalid empty Module 5594 location record"
            }
            return packet.copyOf()
        }

        return packet.copyOf().apply {
            packet.copyOfRange(3, 11).reversedArray().copyInto(this, 3)
            packet.copyOfRange(11, 19).reversedArray().copyInto(this, 11)
            this[19] = radioId.toByte()
        }
    }

    /** Mapping shipped in CASIO WATCHES' dst_auto_rep_enable table. */
    fun radioIdForUtcOffsetMinutes(offsetMinutes: Int): Int = when (offsetMinutes) {
        -8 * 60, -7 * 60, -6 * 60, -5 * 60, -4 * 60 -> 1
        9 * 60 -> 2
        8 * 60 -> 3
        0, 1 * 60, 2 * 60 -> 4
        else -> 0
    }

    fun parseMissionLogState(packet: ByteArray): MissionLogState? {
        if (packet.size != 8 || packet[0].toInt() and 0xFF != MISSION_LOG) return null
        val command = MissionLogState.Command.fromCode(packet[1].toInt() and 0xFF)
        val timestamp = decodeBcdTimestamp(packet.copyOfRange(2, 8))
        return MissionLogState(command, timestamp)
    }

    fun drspStart(category: Int): ByteArray = drspCommand(0x00, category)

    fun drspEnd(category: Int): ByteArray = drspCommand(0x04, category)

    fun parseDrspHeader(packet: ByteArray): DrspHeader? {
        if (packet.size != 7 || packet[0].toInt() and 0xFF != 0x00) return null
        val category = packet[1].toInt() and 0xFF
        val length = (packet[2].toInt() and 0xFF) or
            ((packet[3].toInt() and 0xFF) shl 8) or
            ((packet[4].toInt() and 0xFF) shl 16)
        return DrspHeader(category, length)
    }

    private fun drspCommand(command: Int, category: Int): ByteArray {
        require(category in 0..0xFF) { "category must fit one byte" }
        return byteArrayOf(command.toByte(), category.toByte(), 0x00, 0x00, 0x00)
    }

    private fun littleEndian(value: Long, size: Int): ByteArray =
        ByteArray(size) { index -> ((value ushr (index * 8)) and 0xFF).toByte() }

    private fun decodeBcdTimestamp(bytes: ByteArray): LocalDateTime? {
        if (bytes.size != 6 || bytes.all { it == 0x00.toByte() } || bytes.all { it == 0xFF.toByte() }) {
            return null
        }
        val decoded = bytes.map { value ->
            val unsigned = value.toInt() and 0xFF
            val high = unsigned ushr 4
            val low = unsigned and 0x0F
            if (high > 9 || low > 9) return null
            high * 10 + low
        }
        return try {
            LocalDateTime.of(2000 + decoded[0], decoded[1], decoded[2], decoded[3], decoded[4], decoded[5])
        } catch (_: DateTimeException) {
            null
        }
    }
}
