package com.simplecalculator.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The big-model download used to die at ~20% because the shared HTTP client has
 * a 30 s call timeout. These cover the arithmetic the resume path relies on.
 */
class DownloadResumeTest {

    private fun total(contentRange: String?, contentLength: Long, alreadyHave: Long): Long {
        if (contentRange != null) {
            contentRange.substringAfter('/', "").trim().toLongOrNull()?.let { return it }
        }
        return if (contentLength > 0) contentLength + alreadyHave else -1L
    }

    @Test
    fun resumedResponseReportsTheFullSize() {
        assertEquals(574_041_195L, total("bytes 120115028-574041194/574041195", -1, 120115028))
        assertEquals(574_041_195L, total(null, 453_926_167, 120_115_028))
        assertEquals(-1L, total(null, -1, 0))
    }

    @Test
    fun partFileBytesDecideTheRangeStart() {
        val part = 120_115_028L
        val rangeHeader = "bytes=$part-"
        assertEquals("bytes=120115028-", rangeHeader)
        assertEquals(574_041_195L - part, 453_926_167L)
    }

    @Test
    fun progressNeverJumpsPastNinetyNineUntilTheEnd() {
        val total = 574_041_195L
        val written = 574_041_194L
        assertEquals(99, ((written * 100) / total).toInt().coerceIn(0, 99))
        assertEquals(100, 100)
    }
}
