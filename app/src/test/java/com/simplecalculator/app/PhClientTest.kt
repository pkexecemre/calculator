package com.simplecalculator.app

import com.simplecalculator.app.data.source.ph.PhRetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhClientTest {

    @Test
    fun retriesOnlyTransientFailures() {
        assertTrue(PhRetryPolicy.isRetryable(408))
        assertTrue(PhRetryPolicy.isRetryable(425))
        assertTrue(PhRetryPolicy.isRetryable(429))
        assertTrue(PhRetryPolicy.isRetryable(500))
        assertTrue(PhRetryPolicy.isRetryable(503))
        assertFalse(PhRetryPolicy.isRetryable(400))
        assertFalse(PhRetryPolicy.isRetryable(403))
        assertFalse(PhRetryPolicy.isRetryable(404))
        assertFalse(PhRetryPolicy.isRetryable(410))
    }

    @Test
    fun backoffGrowsWithAttemptAndHasJitter() {
        val first = PhRetryPolicy.backoffMs(0)
        val second = PhRetryPolicy.backoffMs(1)
        val third = PhRetryPolicy.backoffMs(2)

        assertTrue(first in 300L..549L)
        assertTrue(second in 600L..849L)
        assertTrue(third in 1_200L..1_449L)
    }

    @Test
    fun retryAfterIsHonouredAndCapped() {
        assertEquals(2_000L, PhRetryPolicy.backoffMs(0, 2_000L))
        assertEquals(0L, PhRetryPolicy.backoffMs(0, -1L))
        assertEquals(5_000L, PhRetryPolicy.backoffMs(0, 600_000L))
    }
}
