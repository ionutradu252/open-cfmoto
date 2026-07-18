package dev.snaipdefix.opencflink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * the dash's touch frame layout, pinned against real frames from an 800NK (2026-07-17).
 *
 * this was a comment and a wrong u32 read for months. the wrong read only misfires on a second
 * finger, so a single finger test would have passed forever while pinch quietly corrupted AA's
 * gesture stream.
 */
class TouchFrameTest {

    /** same parse as EasyConnProber.handleTouch */
    private data class Touch(val action: Int, val x: Int, val y: Int, val pointer: Int)

    private fun parse(hex: String): Touch {
        val body = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        return Touch(
            action = b.getShort(0).toInt() and 0xFFFF,
            x = b.getShort(2).toInt() and 0xFFFF,
            y = b.getShort(4).toInt() and 0xFFFF,
            pointer = b.getShort(6).toInt() and 0xFFFF,
        )
    }

    private val PANEL_W = 720
    private val PANEL_H = 704

    @Test
    fun `first finger decodes to a sane point`() {
        val t = parse("0200410020000000e00a8d36030000000000")
        assertEquals(2, t.action)   // DOWN
        assertEquals(65, t.x)
        assertEquals(32, t.y)
        assertEquals(0, t.pointer)
    }

    @Test
    fun `second finger is a pointer index, not a giant y`() {
        val t = parse("02003f007c0001000000bb36030000000000")
        assertEquals(2, t.action)
        assertEquals(63, t.x)
        // reading y as u32 gave 65660 here, off a 704px panel by two orders of magnitude
        assertEquals(124, t.y)
        assertEquals(1, t.pointer)
    }

    @Test
    fun `every captured frame decodes inside the panel`() {
        val captured = listOf(
            "0200410020000000e00a8d36030000000000", "030041001a0000007c65b636030000000000",
            "02003f007c0001000000bb36030000000000", "01003f007c0001000000d036030000000000",
            "03003b001a0000007c651437030000000000", "03003a001d0000007c653437030000000000",
            "01003a001d00000000003b37030000000000", "02005500210000000000d637030000000000",
            "030055002f0000007c65ff37030000000000", "03005500270000007c652038030000000000",
            "030055002f000000307c3e38030000000000", "030055002c000000317c6438030000000000",
            "010055002c00000000007638030000000000", "0200e3003b00000000008c3a030000000000",
            "0300e3003e000000347ccb3a030000000000", "0100e3003e0000000000d33a030000000000",
            "02002001300000000000873b030000000000", "03001f012e000000377cd93b030000000000",
            "03001f0131000000387cf63b030000000000", "01001f01310000000000073c030000000000",
            "02004a01310000000000903c030000000000", "03004a012b000000317cc03c030000000000",
            "03004a012e000000327ce83c030000000000", "01004a012e0000000000ee3c030000000000",
        )
        for (hex in captured) {
            val t = parse(hex)
            assertTrue("action $:{t.action} unknown in $hex", t.action in listOf(1, 2, 3))
            assertTrue("x=${t.x} off-panel in $hex", t.x in 0..PANEL_W)
            assertTrue("y=${t.y} off-panel in $hex", t.y in 0..PANEL_H)
            assertTrue("pointer=${t.pointer} implausible in $hex", t.pointer in 0..9)
        }
    }

    @Test
    fun `the captured gesture is two fingers, in order`() {
        val seq = listOf(
            "0200410020000000e00a8d36030000000000",   // down  ptr0
            "030041001a0000007c65b636030000000000",   // move  ptr0
            "02003f007c0001000000bb36030000000000",   // down  ptr1  <- second finger
            "01003f007c0001000000d036030000000000",   // up    ptr1
            "01003a001d00000000003b37030000000000",   // up    ptr0
        ).map { parse(it) }
        assertEquals(listOf(0, 0, 1, 1, 0), seq.map { it.pointer })
        assertEquals(listOf(2, 3, 2, 1, 1), seq.map { it.action })
        assertTrue("two distinct fingers must be present", seq.map { it.pointer }.toSet() == setOf(0, 1))
    }
}
