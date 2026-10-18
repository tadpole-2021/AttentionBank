package com.attentionbank.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationFormatTest {
    @Test
    fun dailyAllowanceDisplaysMinutesAndSeconds() {
        assertEquals("10:00", formatDuration(600))
    }

    @Test
    fun decrementDisplaysSecondsPrecisely() {
        assertEquals("09:59", formatDuration(599))
        assertEquals("00:01", formatDuration(1))
    }

    @Test
    fun zeroBalanceDisplaysZero() {
        assertEquals("00:00", formatDuration(0))
    }

    @Test
    fun longerBalanceIncludesHours() {
        assertEquals("59:59", formatDuration(3599))
        assertEquals("1:00:00", formatDuration(3600))
        assertEquals("2:01:01", formatDuration(7261))
    }

    @Test
    fun negativeBalanceIsNeverDisplayed() {
        assertEquals("00:00", formatDuration(-1))
    }
}
