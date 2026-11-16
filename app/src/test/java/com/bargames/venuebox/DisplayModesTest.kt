package com.bargames.venuebox

import com.bargames.venuebox.client.Mode
import com.bargames.venuebox.client.bestMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayModesTest {
    private val hd60 = Mode(1, 1920, 1080, 60f)
    private val hd50 = Mode(2, 1920, 1080, 50f)
    private val uhd60 = Mode(3, 3840, 2160, 60f)
    private val uhd5994 = Mode(4, 3840, 2160, 59.94f)
    private val uhd30 = Mode(5, 3840, 2160, 30f)
    private val uhd50 = Mode(6, 3840, 2160, 50f)
    private val dci60 = Mode(7, 4096, 2160, 60f)

    @Test
    fun picks4kAtTheSameRefreshRate() {
        assertEquals(uhd60, bestMode(listOf(hd60, hd50, uhd30, uhd50, uhd60), hd60))
        assertEquals(uhd50, bestMode(listOf(hd60, hd50, uhd30, uhd50, uhd60), hd50))
    }

    @Test
    fun treats5994As60() {
        assertEquals(uhd5994, bestMode(listOf(hd60, uhd5994, uhd30), Mode(9, 1920, 1080, 59.94f)))
        assertEquals(uhd60, bestMode(listOf(hd60, uhd5994, uhd60), hd60))
    }

    @Test
    fun neverDropsRefreshRateOrShape() {
        assertNull(bestMode(listOf(hd60, uhd30), hd60))
        assertNull(bestMode(listOf(hd60, dci60), hd60))
    }

    @Test
    fun noSwitchWhenAlreadyBest() {
        assertNull(bestMode(listOf(hd60, uhd60), uhd60))
        assertNull(bestMode(listOf(hd60), hd60))
        assertNull(bestMode(emptyList(), hd60))
    }
}
