package org.avmedia.gshockapi.io

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvoyTransferAssemblerTest {
    @Test
    fun `reassembles arbitrary fragments to the exact announced length`() {
        val assembler = ConvoyTransferAssembler()
        assembler.start(0x11)

        assertEquals(
            ConvoyTransferAssembler.Event.HeaderAccepted(0x11, 6),
            assembler.onDrsp(hex("00 11 06 00 00 00 00")),
        )
        assertEquals(
            ConvoyTransferAssembler.Event.ChunkAccepted(2, 6),
            assembler.onConvoy(hex("fe ff")),
        )

        val completed = assembler.onConvoy(hex("26 00 12 34"))
            as ConvoyTransferAssembler.Event.Completed
        assertEquals(0x11, completed.category)
        assertArrayEquals(hex("fe ff 26 00 12 34"), completed.payload)
        assertFalse(assembler.isActive())
    }

    @Test
    fun `rejects a category mismatch and resets`() {
        val assembler = ConvoyTransferAssembler()
        assembler.start(0x19)

        val rejected = assembler.onDrsp(hex("00 11 a0 00 00 00 00"))
            as ConvoyTransferAssembler.Event.Rejected
        assertTrue(rejected.reason.contains("does not match"))
        assertFalse(assembler.isActive())
    }

    @Test
    fun `rejects convoy data before the header`() {
        val assembler = ConvoyTransferAssembler()
        assembler.start(0x19)

        val rejected = assembler.onConvoy(hex("01 02"))
            as ConvoyTransferAssembler.Event.Rejected
        assertTrue(rejected.reason.contains("before"))
        assertFalse(assembler.isActive())
    }

    @Test
    fun `rejects data beyond the announced length`() {
        val assembler = ConvoyTransferAssembler()
        assembler.start(0x19)
        assembler.onDrsp(hex("00 19 02 00 00 00 00"))

        val rejected = assembler.onConvoy(hex("01 02 03"))
            as ConvoyTransferAssembler.Event.Rejected
        assertTrue(rejected.reason.contains("exceeded"))
        assertFalse(assembler.isActive())
    }

    @Test
    fun `rejects empty or excessive announced lengths`() {
        val empty = ConvoyTransferAssembler(maxTransferSize = 10)
        empty.start(0x11)
        assertTrue(
            empty.onDrsp(hex("00 11 00 00 00 00 00"))
                is ConvoyTransferAssembler.Event.Rejected,
        )

        val excessive = ConvoyTransferAssembler(maxTransferSize = 10)
        excessive.start(0x11)
        assertTrue(
            excessive.onDrsp(hex("00 11 0b 00 00 00 00"))
                is ConvoyTransferAssembler.Event.Rejected,
        )
    }

    @Test
    fun `allows only one active transaction`() {
        val assembler = ConvoyTransferAssembler()
        assembler.start(0x11)

        assertThrows(IllegalStateException::class.java) {
            assembler.start(0x19)
        }
    }

    private fun hex(value: String): ByteArray =
        value.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
