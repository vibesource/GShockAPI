package org.avmedia.gshockapi

import org.avmedia.gshockapi.protocols.GgB100Protocol
import org.avmedia.gshockapi.protocols.GgB100ProtocolPackets
import org.avmedia.gshockapi.protocols.GgB100ProtocolPackets.MissionLogState.Command
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertFalse(info.hasStepCounter)
    }

    @Test
    fun `location result matches verified 1234 metre 270 degree packet`() {
        assertArrayEquals(
            hex("35 02 00 d2 04 00 00 0e 01"),
            GgB100ProtocolPackets.locationIndicatorResult(1234, 270),
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
    }

    private fun hex(value: String): ByteArray =
        value.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
