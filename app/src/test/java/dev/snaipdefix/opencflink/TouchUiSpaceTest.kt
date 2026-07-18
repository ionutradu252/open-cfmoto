package dev.snaipdefix.opencflink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * touch coords sent to AA are in the ui-inside-margins space, not the canvas.
 *
 * numbers are from the 2026-07-17 800NK log: video 720x1280, ui 720x712 (margins 0x568), fill
 * crop offset 288, ui origin 284. we used to send canvas coords (y up to ~1000) against a
 * 720x1280 touchscreen declaration; AA rescaled them onto the 712-high ui and every tap landed
 * above the finger, bottom half dead. third bike test on this one bug, hence a test.
 */
class TouchUiSpaceTest {

    private val SRC_W = 720
    private val SRC_H = 1280
    private val UI_W = 720
    private val UI_H = 712

    private fun ui(sx: Int, sy: Int) = AaCompositor.sourceToUi(sx, sy, SRC_W, SRC_H, UI_W, UI_H)

    @Test
    fun `a tap from the log lands 4px from the finger, not 288`() {
        // bike (675,673) mapped to source (675,961) in fill mode. ui y must be ~673, not 961
        assertEquals(675 to 677, ui(675, 961))
    }

    @Test
    fun `letterbox taps land in the same ui space`() {
        // bike (537,542) -> source (681,985) through the 396x704 letterbox viewport
        assertEquals(681 to 701, ui(681, 985))
    }

    @Test
    fun `the bottom of the panel is alive`() {
        // source y 991 is the last fill-visible row; used to map past the declared screen
        assertEquals(718 to 707, ui(718, 991))
    }

    @Test
    fun `the fill crop's 4px sliver above the ui band snaps to the edge`() {
        // panel row 0 in fill mode is source y 288... the crop offset. rows just above the
        // ui origin (284) are inside the band; a hair below it snaps to 0
        assertEquals(360 to 4, ui(360, 288))
        assertEquals(360 to 0, ui(360, 280))
    }

    @Test
    fun `taps in the margin band are dropped, not clamped across the screen`() {
        // letterbox shows the whole canvas, including the empty margin above the ui
        assertNull(ui(360, 100))
        assertNull(ui(360, 1200))
    }

    @Test
    fun `no margins means identity`() {
        assertEquals(100 to 200, AaCompositor.sourceToUi(100, 200, 800, 480, 800, 480))
    }
}
