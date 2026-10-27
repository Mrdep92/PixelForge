package com.pixelforge.routing

import org.junit.Assert.*
import org.junit.Test

class TpmTrackerTest {
    private var now = 1_000_000L

    @Test fun noLimitMeansNoWait() {
        assertEquals(0L, TpmTracker(emptyMap()) { now }.waitNeededMs("X", 99999))
    }

    @Test fun waitsUntilOldTokensLeaveWindow() {
        val t = TpmTracker(mapOf("G" to 1000)) { now }
        t.record("G", 700)
        now += 10_000
        assertEquals(0L, t.waitNeededMs("G", 100))          // 800 <= 800 (80%)
        assertEquals(50_000L, t.waitNeededMs("G", 200))     // harus menunggu 700 itu keluar dari jendela
        now += 50_000
        assertEquals(0L, t.waitNeededMs("G", 200))
    }

    @Test fun requestBiggerThanBudgetNeverFits() {
        assertEquals(TpmTracker.WINDOW, TpmTracker(mapOf("G" to 1000)) { now }.waitNeededMs("G", 900))
    }

    @Test fun parsesErrorTexts() {
        assertTrue(TpmTracker.isTpmError("Rate limit reached ... tokens per minute (TPM)"))
        assertTrue(TpmTracker.isRpmError("limit on requests per minute"))
        assertFalse(TpmTracker.isRpmError("tokens per minute"))
        assertEquals(3170L, TpmTracker.parseRetryMs("Please try again in 3.17s."))
        assertEquals(250L, TpmTracker.parseRetryMs("try again in 250ms"))
        assertNull(TpmTracker.parseRetryMs("nothing"))
    }
}
