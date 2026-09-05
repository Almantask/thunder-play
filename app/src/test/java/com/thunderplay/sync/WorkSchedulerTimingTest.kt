package com.thunderplay.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class WorkSchedulerTimingTest {

    private val zone = ZoneId.of("Europe/Vilnius")

    @Test
    fun `when afternoon, delay targets 3 AM next day`() {
        val now = ZonedDateTime.of(2026, 9, 5, 20, 0, 0, 0, zone) // 8:00 PM
        val delayMs = calculateDelayToNighttime(now, targetHour = 3)
        // 8:00 PM to 3:00 AM next day is 7 hours
        assertThat(delayMs).isEqualTo(TimeUnit.HOURS.toMillis(7))
    }

    @Test
    fun `when early morning before 3 AM, delay targets 3 AM same day`() {
        val now = ZonedDateTime.of(2026, 9, 5, 1, 30, 0, 0, zone) // 1:30 AM
        val delayMs = calculateDelayToNighttime(now, targetHour = 3)
        // 1:30 AM to 3:00 AM same day is 1 hour 30 minutes
        assertThat(delayMs).isEqualTo(TimeUnit.MINUTES.toMillis(90))
    }

    @Test
    fun `when exactly 3 AM, delay targets 3 AM next day`() {
        val now = ZonedDateTime.of(2026, 9, 5, 3, 0, 0, 0, zone) // 3:00:00 AM
        val delayMs = calculateDelayToNighttime(now, targetHour = 3)
        // Exactly at 3:00:00 AM, should schedule for tomorrow (24 hours)
        assertThat(delayMs).isEqualTo(TimeUnit.HOURS.toMillis(24))
    }

    @Test
    fun `when past 3 AM, delay targets 3 AM next day`() {
        val now = ZonedDateTime.of(2026, 9, 5, 4, 0, 0, 0, zone) // 4:00 AM
        val delayMs = calculateDelayToNighttime(now, targetHour = 3)
        // 4:00 AM to 3:00 AM next day is 23 hours
        assertThat(delayMs).isEqualTo(TimeUnit.HOURS.toMillis(23))
    }
}
