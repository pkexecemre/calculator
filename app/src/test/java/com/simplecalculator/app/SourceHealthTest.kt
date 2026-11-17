package com.simplecalculator.app

import com.simplecalculator.app.data.source.Category
import com.simplecalculator.app.data.source.InMemoryHealthStore
import com.simplecalculator.app.data.source.Source
import com.simplecalculator.app.data.source.SourceHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceHealthTest {

    private var now = 1_000L
    private val store = InMemoryHealthStore()
    private val health = SourceHealth(store) { now }

    private val source = object : Source {
        override val id = "test"
        override val displayName = "Test"
        override val category = Category.LIVE
        override val playerMode = com.simplecalculator.app.data.source.PlayerMode.NATIVE
        override val supportsStars = false
        override suspend fun listing(
            kind: com.simplecalculator.app.data.source.ListingKind,
            page: Int,
            query: String?,
            slug: String?
        ) = emptyList<com.simplecalculator.app.data.source.VideoSummary>()
        override suspend fun detail(vkey: String) = com.simplecalculator.app.data.source.VideoDetail()
        override suspend fun comments(
            vkey: String,
            commentsUrl: String?,
            offset: Int
        ) = emptyList<com.simplecalculator.app.data.source.CommentItem>()
        override suspend fun related(
            vkey: String,
            relatedUrl: String?
        ) = emptyList<com.simplecalculator.app.data.source.VideoSummary>()
    }

    @Test
    fun pausesAfterThreeFailuresAndRecoversAfterCooldown() {
        health.recordFailure("test", "timeout")
        health.recordFailure("test", "HTTP 500")
        assertFalse(health.isPaused("test"))

        health.recordFailure("test", "HTTP 403")
        assertTrue(health.isPaused("test"))
        assertEquals(3, health.info(source).failures)
        assertEquals("HTTP 403", health.info(source).lastError)

        now += SourceHealth.COOLDOWN_MS + 1
        assertFalse(health.isPaused("test"))

        health.recordSuccess("test")
        assertEquals(0, health.info(source).failures)
        assertEquals(now, health.info(source).lastSuccessAt)
        assertNull(health.info(source).lastError)
    }

    @Test
    fun resetClearsEverything() {
        repeat(4) { health.recordFailure("test", "boom") }
        assertTrue(health.isPaused("test"))
        health.reset("test")
        assertFalse(health.isPaused("test"))
        assertEquals(0, health.info(source).failures)
        assertNull(health.info(source).lastError)
    }

    @Test
    fun snapshotCoversEveryRegisteredSourceShape() {
        val infos = health.snapshot(listOf(source))
        assertEquals(1, infos.size)
        assertEquals("test", infos[0].id)
        assertEquals(Category.LIVE, infos[0].category)
    }
}
