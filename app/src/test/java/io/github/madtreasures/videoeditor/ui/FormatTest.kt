package io.github.madtreasures.videoeditor.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun formatTime_showsTenthsBelowAnHour() {
        assertEquals("0:00.0", formatTime(0))
        assertEquals("0:00.0", formatTime(-20))
        assertEquals("0:02.0", formatTime(1960))
        assertEquals("1:15.3", formatTime(75_300))
        assertEquals("59:59.9", formatTime(3_599_949))
        assertEquals("1:00:00", formatTime(3_599_950))
        assertEquals("1:02:03", formatTime(3_723_400))
    }

    @Test
    fun formatRulerTime_addsTenthsOnlyForFineSteps() {
        assertEquals("0:05", formatRulerTime(5_000, 1_000))
        assertEquals("0:05.5", formatRulerTime(5_500, 500))
        assertEquals("12:00", formatRulerTime(720_000, 60_000))
        assertEquals("1:00:00", formatRulerTime(3_600_000, 600_000))
    }

    @Test
    fun formatSpeed_dropsNeedlessDecimals() {
        assertEquals("1x", formatSpeed(1f))
        assertEquals("0.25x", formatSpeed(0.25f))
        assertEquals("1.5x", formatSpeed(1.5f))
        assertEquals("4x", formatSpeed(4f))
    }
}
