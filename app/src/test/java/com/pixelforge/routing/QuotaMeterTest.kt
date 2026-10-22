package com.pixelforge.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaMeterTest {

    private var now = 20_000L * QuotaMeter.DAY + 1000L
    private fun meter() = QuotaMeter { now }

    @Test
    fun okUntilMinuteWindowIsFull() {
        val m = meter()
        m.setLimits("P", QuotaMeter.Limits(perMinute = 2, perDay = null))
        assertEquals(QuotaMeter.Verdict.Ok, m.check("P"))
        m.record("P"); m.record("P")
        val v = m.check("P")
        assertTrue(v is QuotaMeter.Verdict.Wait)
        now += QuotaMeter.MINUTE + 1
        assertEquals(QuotaMeter.Verdict.Ok, m.check("P"))
    }

    @Test
    fun dayExhaustedUntilNextUtcDay() {
        val m = meter()
        m.setLimits("P", QuotaMeter.Limits(perMinute = null, perDay = 3))
        repeat(3) { m.record("P") }
        val v = m.check("P")
        assertTrue(v is QuotaMeter.Verdict.DayExhausted)
        assertEquals(20_001L * QuotaMeter.DAY, (v as QuotaMeter.Verdict.DayExhausted).resetAtMs)
        now = 20_001L * QuotaMeter.DAY + 5
        assertEquals(QuotaMeter.Verdict.Ok, m.check("P"))
        assertEquals(0, m.snapshots.value.first().usedDay)
    }

    @Test
    fun rateLimitedHoldsProviderForRetryAfter() {
        val m = meter()
        m.noteRateLimited("P", 30_000L)
        val v = m.check("P") as QuotaMeter.Verdict.Wait
        assertEquals(30_000L, v.ms)
    }

    @Test
    fun zeroRemainingHeaderBlocksUsingResetSeconds() {
        val m = meter()
        val headers = mapOf("x-ratelimit-remaining-requests" to "0", "x-ratelimit-reset-requests" to "12")
        m.observeHeaders("P") { headers[it] }
        val v = m.check("P") as QuotaMeter.Verdict.Wait
        assertEquals(12_000L, v.ms)
        assertEquals(0, m.snapshots.value.first().reportedRemaining)
    }

    @Test
    fun epochResetHeaderIsUnderstood() {
        val m = meter()
        val headers = mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to (now + 8_000L).toString())
        m.observeHeaders("P") { headers[it] }
        val v = m.check("P") as QuotaMeter.Verdict.Wait
        assertEquals(8_000L, v.ms)
    }

    @Test
    fun exportRestoreKeepsDailyCountOnly() {
        val a = meter()
        a.setLimits("P", QuotaMeter.Limits(null, 50))
        repeat(7) { a.record("P") }
        val b = meter()
        b.setLimits("P", QuotaMeter.Limits(null, 50))
        b.restore(a.export())
        val s = b.snapshots.value.first { it.provider == "P" }
        assertEquals(7, s.usedDay)
        assertEquals(43, s.remainingDay)
        assertEquals(0, s.usedMinute)
    }

    @Test
    fun snapshotsReportLimitsAndUsage() {
        val m = meter()
        m.setLimits("OpenRouter", QuotaMeter.OPENROUTER_FREE)
        m.record("OpenRouter")
        val s = m.snapshots.value.single()
        assertEquals(1, s.usedDay)
        assertEquals(1, s.usedMinute)
        assertEquals(50, s.perDay)
        assertEquals(20, s.perMinute)
    }
}
