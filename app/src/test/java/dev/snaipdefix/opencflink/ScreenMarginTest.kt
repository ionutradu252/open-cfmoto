package dev.snaipdefix.opencflink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * screen margins hold Android Auto back from an edge so the dash's own furniture doesn't collide
 * with it — on an 800NK, MotoPlay's pull-down arrow across the top.
 *
 * two rects matter and they are not the same thing. the VIEWPORT is where the frame is scaled to,
 * and in fill mode it deliberately overflows the canvas. the CLIP is the area the picture is allowed
 * into. glViewport does not clip, so the first version of this drew straight over the margin and the
 * setting appeared to do nothing; these tests exist to keep that from coming back.
 */
class ScreenMarginTest {

    // the 800NK: dash canvas 720x704, AA source 720x1280 portrait
    private val CW = 720
    private val CH = 704
    private val SW = 720
    private val SH = 1280

    private fun vp(fill: Boolean, top: Int = 0, bottom: Int = 0, left: Int = 0, right: Int = 0) =
        AaCompositor.viewportFor(CW, CH, SW, SH, fill, topAlign = false,
            mTop = top, mBottom = bottom, mLeft = left, mRight = right)

    private fun clip(top: Int = 0, bottom: Int = 0, left: Int = 0, right: Int = 0) =
        AaCompositor.clipFor(CW, CH, top, bottom, left, right)

    /** the app drops a tap outside the clip, whatever the viewport is doing */
    private fun reaches(cx: Int, cy: Int, c: IntArray): Boolean =
        cx >= c[0] && cy >= c[1] && cx < c[0] + c[2] && cy < c[1] + c[3]

    @Test
    fun `no margins is exactly what the bike test produced`() {
        // "[COMPOSITOR] output set canvas=720x704 src=720x1280 -> fill(crop) rect=720x1280 @(0,-288)"
        assertEquals(listOf(0, -288, 720, 1280), vp(fill = true).toList())
    }

    @Test
    fun `no margins clips to the whole canvas, so nothing changes`() {
        assertEquals(listOf(0, 0, CW, CH), clip().toList())
    }

    @Test
    fun `a top margin blanks exactly that strip`() {
        val c = clip(top = 40)
        assertEquals("picture starts 40px down", 40, c[1])
        assertEquals("and is 40px shorter", CH - 40, c[3])
        assertEquals("full width still", CW, c[2])
    }

    @Test
    fun `a tap on the blanked strip never reaches Android Auto`() {
        val c = clip(top = 40)
        assertTrue("row 10 is under the arrow, must be dropped", !reaches(360, 10, c))
        assertTrue("row 39 is still in the strip", !reaches(360, 39, c))
        assertTrue("row 40 is the first live row", reaches(360, 40, c))
        assertTrue("the middle of the screen still works", reaches(360, 400, c))
    }

    @Test
    fun `fill mode overflows the canvas, which is exactly why the clip is needed`() {
        // this is the bug the first version had: the viewport covers the margin, so only the clip
        // keeps the strip black and untouchable
        val v = vp(fill = true, top = 40)
        val c = clip(top = 40)
        assertTrue("viewport really does cover row 0 in fill", v[1] <= 0)
        assertTrue("so the viewport alone would accept a tap at row 10", 10 >= v[1])
        assertTrue("but the clip rejects it", !reaches(360, 10, c))
    }

    @Test
    fun `letterbox with a top margin loses nothing, it just shrinks`() {
        val r = vp(fill = false, top = 40)
        val (x, y, w, h) = listOf(r[0], r[1], r[2], r[3])
        assertTrue("must fit inside the inset height", h <= CH - 40)
        assertTrue("must fit inside the width", w <= CW)
        assertTrue("must start at or below the margin", y >= 40)
        assertTrue("aspect preserved", kotlin.math.abs(w.toFloat() / h - SW.toFloat() / SH) < 0.02f)
        assertTrue(x >= 0)
    }

    @Test
    fun `left and right margins narrow the picture`() {
        val none = vp(fill = false)
        val inset = vp(fill = false, left = 30, right = 30)
        assertTrue("narrower than with no margins", inset[2] <= none[2])
        val c = clip(left = 30, right = 30)
        assertEquals(30, c[0])
        assertEquals(CW - 60, c[2])
        assertTrue("a tap on the left strip is dropped", !reaches(10, 400, c))
    }

    @Test
    fun `absurd margins cannot collapse the picture`() {
        val r = vp(fill = false, top = 400, bottom = 400)
        assertTrue("width stays positive", r[2] > 0)
        assertTrue("height stays positive", r[3] > 0)
        val c = clip(top = 400, bottom = 400)
        assertTrue("clip stays positive", c[2] > 0 && c[3] > 0)
    }

    @Test
    fun `the 450SR is untouched when no margins are set`() {
        // 800x400 canvas, 800x480 source, fill: the log shows rect=800x480 @(0,-40)
        val r = AaCompositor.viewportFor(800, 400, 800, 480, fill = true, topAlign = false,
            mTop = 0, mBottom = 0, mLeft = 0, mRight = 0)
        assertEquals(listOf(0, -40, 800, 480), r.toList())
        assertEquals(listOf(0, 0, 800, 400), AaCompositor.clipFor(800, 400, 0, 0, 0, 0).toList())
    }
}
