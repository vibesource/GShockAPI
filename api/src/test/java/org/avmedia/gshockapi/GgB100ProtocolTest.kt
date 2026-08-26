package org.avmedia.gshockapi

import org.avmedia.gshockapi.protocols.GgB100Protocol
import org.avmedia.gshockapi.protocols.GgB100ProtocolPackets
import org.avmedia.gshockapi.protocols.GgB100ProtocolPackets.MissionLogState.Command
import org.avmedia.gshockapi.io.ButtonPressedIOFunctional
import org.avmedia.gshockapi.io.IO
import org.avmedia.gshockapi.model.LocationIndicatorCommand
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class GgB100ProtocolTest {
    @Test
    fun `GG-B100 resolves to its Module 5594 profile`() {
        val model = WatchInfo.resolveModel("CASIO GG-B100")
        val info = WatchInfo.resolveModelInfo(model)

        assertEquals(WatchInfo.WatchModel.GG_B100, model)
        assertSame(GgB100Protocol, info.protocol)
        assertTrue(info.hasLocationIndicator)
        assertTrue(info.hasMissionLog)
        assertTrue(info.hasAltimeterCorrection)
        assertTrue(info.hasStepCounter)
    }

    @Test
    fun `location result matches verified 1234 metre 270 degree packet`() {
        assertArrayEquals(
            hex("35 02 00 d2 04 00 00 0e 01"),
            GgB100ProtocolPackets.locationIndicatorResult(1234, 270),
        )
    }

    @Test
    fun `location indicator handshake packets match official captures`() {
        assertArrayEquals(
            hex("23 43 41 53 49 4f 20 47 47 2d 42 31 30 30 00 00 00 00 00 00"),
            GgB100ProtocolPackets.locationIndicatorWatchName(),
        )
        assertArrayEquals(
            hex("35 02 03 00 00 00 00 00 00"),
            GgB100ProtocolPackets.locationIndicatorFailure(3),
        )
        assertTrue(
            GgB100ProtocolPackets.isLocationIndicatorCalculationRequest(
                hex("35 02 00 00 00 00 00 00 00"),
            ),
        )
        assertArrayEquals(
            hex("35 00 00 00 00 00 00 00 00"),
            GgB100ProtocolPackets.locationIndicatorResponse(
                LocationIndicatorCommand.SAVE_CURRENT_LOCATION,
                0,
            ),
        )
        assertEquals(
            LocationIndicatorCommand.SAVE_CURRENT_LOCATION,
            GgB100ProtocolPackets.locationIndicatorCommand(
                hex("35 00 00 00 00 00 00 00 00"),
            ),
        )
        assertEquals(
            LocationIndicatorCommand.DELETE_SAVED_LOCATION,
            GgB100ProtocolPackets.locationIndicatorCommand(
                hex("35 01 00 00 00 00 00 00 00"),
            ),
        )
        assertEquals(
            LocationIndicatorCommand.CALCULATE_DISTANCE_AND_BEARING,
            GgB100ProtocolPackets.locationIndicatorCommand(
                hex("35 02 00 00 00 00 00 00 00"),
            ),
        )
    }

    @Test
    fun `connection reason 07 is Location Indicator`() {
        assertEquals(
            IO.WatchButton.LOCATION_INDICATOR,
            ButtonPressedIOFunctional.parseButtonPress(
                "0x10 02 43 23 13 4B D3 7F 07 03 0F FF FF FF FF 24 00 00 00",
            ).getOrNull(),
        )
    }

    @Test
    fun `mission state decodes captured stop timestamp`() {
        val state = GgB100ProtocolPackets.parseMissionLogState(
            hex("37 03 26 08 25 15 50 04"),
        )

        assertEquals(Command.STOP, state?.command)
        assertEquals(LocalDateTime.of(2026, 8, 25, 15, 50, 4), state?.timestampUtc)
    }

    @Test
    fun `empty mission timestamp is unavailable`() {
        val state = GgB100ProtocolPackets.parseMissionLogState(
            hex("37 00 ff ff ff ff ff ff"),
        )

        assertEquals(Command.NO_FUNCTION, state?.command)
        assertNull(state?.timestampUtc)
    }

    @Test
    fun `altitude block decodes samples end marker and timestamped points`() {
        val block = ByteArray(294) { 0xFF.toByte() }
        hex("26 08 25 15 44 04").copyInto(block, 0)
        repeat(60) { index -> hex("ff 7f").copyInto(block, 6 + index * 2) }
        hex("54 00 55 00 fe 7f").copyInto(block, 6)
        hex("51 00 26 08 25 15 00 55 5b 04 00 ff").copyInto(block, 126)

        val decoded = GgB100ProtocolPackets.decodeMissionLogAltitude(block)

        assertEquals(LocalDateTime.of(2026, 8, 25, 15, 44, 4), decoded?.startTimeUtc)
        assertEquals(2, decoded?.samples?.size)
        assertEquals(84, decoded?.samples?.get(0)?.altitudeMetres)
        assertEquals(LocalDateTime.of(2026, 8, 25, 15, 46, 4), decoded?.samples?.get(1)?.timestampUtc)
        assertEquals(2, decoded?.endMarkerIndex)
        assertEquals(81, decoded?.points?.single()?.altitudeMetres)
        assertEquals(LocalDateTime.of(2026, 8, 25, 15, 0, 55), decoded?.points?.single()?.timestampUtc)
        assertEquals("5b 04 00 ff", decoded?.points?.single()?.metadataHex)
    }

    @Test
    fun `unknown altitude block length remains lossless but undecoded`() {
        assertNull(GgB100ProtocolPackets.decodeMissionLogAltitude(ByteArray(12)))
    }

    @Test
    fun `exercise block decodes captured QW5594 steps and exercise totals`() {
        val block = ByteArray(160)
        repeat(48) { index -> hex("fe ff").copyInto(block, index * 2) }
        repeat(14) { index -> hex("fe ff ff ff").copyInto(block, 104 + index * 4) }
        hex("c7 00 33 01 cb 01 a3 00").copyInto(block, 0)
        hex("5b 00 7c 00 ac 00 3d 00").copyInto(block, 48)
        hex("46 07 00 00 d0 02 00 00").copyInto(block, 96)

        val decoded = GgB100ProtocolPackets.decodeMissionLogExercise(block)

        assertEquals(24, decoded?.stepSlots?.size)
        assertEquals(listOf(199, 307, 459, 163), decoded?.stepSlots?.filterNotNull())
        assertEquals(listOf(91, 124, 172, 61), decoded?.exerciseSlots?.filterNotNull())
        assertEquals(1862L, decoded?.currentDay?.steps)
        assertEquals(720L, decoded?.currentDay?.exercise)
        assertNull(decoded?.dailyTotals?.get(1)?.steps)
        assertNull(decoded?.dailyTotals?.get(7)?.exercise)

        val shared = decoded?.toStepCounterData(LocalDateTime.of(2026, 8, 26, 0, 0).toLocalDate())
        assertEquals(1862, shared?.currentDaySteps)
        assertEquals(listOf(199, 307, 459, 163), shared?.hourlySteps?.filterNotNull())
        assertEquals(7, shared?.dailyHistory?.size)
    }

    @Test
    fun `exercise slots decode unsigned values and reject unknown length`() {
        val block = ByteArray(160)
        repeat(48) { index -> hex("fe ff").copyInto(block, index * 2) }
        repeat(16) { index -> hex("fe ff ff ff").copyInto(block, 96 + index * 4) }
        hex("34 12").copyInto(block, 0)
        hex("78 56").copyInto(block, 48)

        val decoded = GgB100ProtocolPackets.decodeMissionLogExercise(block)

        assertEquals(0x1234, decoded?.stepSlots?.first())
        assertEquals(0x5678, decoded?.exerciseSlots?.first())
        assertNull(GgB100ProtocolPackets.decodeMissionLogExercise(ByteArray(159)))
    }

    @Test
    fun `DRSP commands and headers match Mission Log captures`() {
        assertArrayEquals(hex("00 19 00 00 00"), GgB100ProtocolPackets.drspStart(0x19))
        assertArrayEquals(hex("04 11 00 00 00"), GgB100ProtocolPackets.drspEnd(0x11))
        assertEquals(
            GgB100ProtocolPackets.DrspHeader(0x19, 294),
            GgB100ProtocolPackets.parseDrspHeader(hex("00 19 26 01 00 00 00")),
        )
        assertEquals(
            GgB100ProtocolPackets.DrspHeader(0x11, 160),
            GgB100ProtocolPackets.parseDrspHeader(hex("00 11 a0 00 00 00 00")),
        )
    }

    @Test
    fun `altimeter correction uses signed little endian metres`() {
        assertArrayEquals(hex("36 00 01 57 00"), GgB100ProtocolPackets.altimeterCorrection(87))
        assertArrayEquals(hex("36 00 01 f4 ff"), GgB100ProtocolPackets.altimeterCorrection(-12))
        assertArrayEquals(hex("36 01 01 00 00"), GgB100ProtocolPackets.altimeterCorrection(null))
        assertEquals(
            true,
            GgB100ProtocolPackets.altimeterCorrectionSucceeded(hex("36 00 01 57 00")),
        )
        assertEquals(
            false,
            GgB100ProtocolPackets.altimeterCorrectionSucceeded(hex("36 01 01 00 00")),
        )
        assertNull(GgB100ProtocolPackets.altimeterCorrectionSucceeded(hex("36 00 00 57 00")))
    }

    @Test
    fun `phone location record matches official Module 5594 capture`() {
        assertArrayEquals(
            hex("24 00 01 40 4a 28 69 6a fd a4 dc bf f8 9f df 99 58 2b 3a 04"),
            GgB100ProtocolPackets.locationAndRadioInformation(
                slot = 0,
                latitude = 52.3157171,
                longitude = -1.5390316,
                radioId = 4,
            ),
        )
    }

    @Test
    fun `watch-read location converts to big endian write record`() {
        assertArrayEquals(
            hex("24 01 01 40 2d dc 65 40 cc 78 ea c0 37 83 3e 57 53 a3 ec 00"),
            GgB100ProtocolPackets.locationReadToWrite(
                hex("24 01 01 ea 78 cc 40 65 dc 2d 40 ec a3 53 57 3e 83 37 c0 00"),
                radioId = 0,
            ),
        )
    }

    @Test
    fun `empty watch location record is preserved`() {
        val emptyWorld = hex("24 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00")

        assertArrayEquals(
            emptyWorld,
            GgB100ProtocolPackets.locationReadToWrite(emptyWorld, radioId = 4),
        )
    }

    @Test
    fun `radio IDs follow Casio timezone reception table`() {
        assertEquals(1, GgB100ProtocolPackets.radioIdForUtcOffsetMinutes(-5 * 60))
        assertEquals(4, GgB100ProtocolPackets.radioIdForUtcOffsetMinutes(0))
        assertEquals(3, GgB100ProtocolPackets.radioIdForUtcOffsetMinutes(8 * 60))
        assertEquals(2, GgB100ProtocolPackets.radioIdForUtcOffsetMinutes(9 * 60))
        assertEquals(0, GgB100ProtocolPackets.radioIdForUtcOffsetMinutes(-60))
    }

    @Test
    fun `connection reason 08 is routed as Mission Log`() {
        val packet = "10 17 62 07 38 85 cd 7f 08 03 0f ff ff ff ff 24 00 00 00"

        assertEquals(IO.WatchButton.MISSION_LOG, ButtonPressedIOFunctional.parseButtonPress(packet).getOrThrow())
    }

    private fun hex(value: String): ByteArray =
        value.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
