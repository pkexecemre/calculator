package com.simplecalculator.app

import com.simplecalculator.app.data.source.ph.PhParser
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture tests for the Pornhub DOM/JSON parser. The captures under
 * src/test/resources/ph are real pages, so these tests fail when a selector
 * breaks against the current markup instead of only when Kotlin stops compiling.
 */
class PhParserTest {

    private val listHtml: String by lazy { fixture("list.html") }
    private val detailHtml: String by lazy { fixture("detail.html") }
    private val modelHtml: String by lazy { fixture("model.html") }
    private val searchHtml: String by lazy { fixture("search.html") }
    private val commentsHtml: String by lazy { fixture("comments.html") }
    private val relatedJson: String by lazy { fixture("related.json") }

    @Test
    fun listFixtureParsesAtLeastThirtyUniqueCards() {
        val cards = PhParser.parseList(listHtml, "ph")

        assertTrue("expected >= 30 cards, got ${cards.size}", cards.size >= 30)
        for (card in cards) {
            assertTrue("blank vkey in $card", card.vkey.isNotBlank())
            assertTrue("blank title in $card", card.title.isNotBlank())
            assertEquals("ph", card.sourceId)
        }

        val withThumb = cards.count { it.thumb.isNotBlank() }
        assertTrue("only $withThumb of ${cards.size} cards have thumbnails", withThumb >= cards.size * 8 / 10)

        assertEquals("cards must be deduped by vkey", cards.size, cards.map { it.vkey }.distinct().size)

        val again = PhParser.parseList(listHtml, "ph")
        assertEquals(cards.map { it.vkey }, again.map { it.vkey })
        assertEquals(cards.map { it.title }, again.map { it.title })
    }

    @Test
    fun modelListingParsesAtLeastFifteenCards() {
        val cards = PhParser.parseList(modelHtml, "ph")

        assertTrue("expected >= 15 model videos, got ${cards.size}", cards.size >= 15)
        assertTrue(cards.all { it.vkey.isNotBlank() && it.title.isNotBlank() })
        assertEquals(cards.size, cards.map { it.vkey }.distinct().size)
    }

    @Test
    fun searchListingParsesAtLeastFifteenCards() {
        val cards = PhParser.parseList(searchHtml, "ph")

        assertTrue("expected >= 15 search results, got ${cards.size}", cards.size >= 15)
        assertTrue(cards.all { it.vkey.isNotBlank() && it.title.isNotBlank() })
        assertEquals(cards.size, cards.map { it.vkey }.distinct().size)
    }

    @Test
    fun detailParsesPlayableHlsStreamsAndMetadata() {
        val detail = PhParser.parseDetail(detailHtml)

        assertTrue("expected >= 4 streams, got ${detail.streams.size}", detail.streams.size >= 4)
        for (stream in detail.streams) {
            assertTrue("stream is not HTTPS: ${stream.url}", stream.url.startsWith("https://"))
            assertFalse("get_media stub leaked into streams: ${stream.url}", stream.url.contains("/get_media"))
            assertTrue("blank stream URL", stream.url.isNotBlank())
        }
        assertEquals("stream URLs must be deduped", detail.streams.size, detail.streams.map { it.url }.distinct().size)
        assertTrue("720p label missing: ${detail.streams.map { it.label }}", detail.streams.any { it.label == "720p" })
        assertTrue("1080p label missing: ${detail.streams.map { it.label }}", detail.streams.any { it.label == "1080p" })
        assertTrue(detail.streams.any { it.hls })

        assertNotNull("related_url was not parsed", detail.relatedUrl)
        assertTrue(detail.relatedUrl!!.contains("related_datas"))
        assertNotNull("comments URL was not parsed", detail.commentsUrl)
        assertTrue(detail.commentsUrl!!.contains("/comment/show"))
        assertNotNull("uploader was not parsed", detail.uploaderSlug)
        assertTrue("uploader slug is blank", detail.uploaderSlug!!.isNotBlank())
        assertNotNull("uploader name was not parsed", detail.uploaderName)
        assertTrue("tags were not parsed", detail.tags.isNotEmpty())
        assertTrue("too many tags: ${detail.tags.size}", detail.tags.size <= 20)
    }

    @Test
    fun unavailableVideoReturnsNoStreams() {
        val html = """
            <html><body><script>
            var flashvars_1 = {
              "video_unavailable": "true",
              "mediaDefinitions": [
                {"format":"hls","quality":"720","videoUrl":"https://cdn.test/720.m3u8"}
              ]
            };
            </script></body></html>
        """.trimIndent()

        val detail = PhParser.parseDetail(html)
        assertTrue("unavailable videos must not expose streams", detail.streams.isEmpty())
    }

    @Test
    fun detailFallsBackToMediaDefinitionsScan() {
        val html = """
            <html><body><script>
            window.playerConfig = {"mediaDefinitions":[
              {"format":"hls","quality":"720","videoUrl":"https://cdn.test/720.m3u8"},
              {"format":"mp4","quality":1080,"videoUrl":"https://cdn.test/1080.mp4"},
              {"format":"mp4","quality":[],"videoUrl":"https://cdn.test/unknown.mp4"},
              {"format":"mp4","quality":"480","videoUrl":"https://cdn.test/get_media?id=1"},
              {"format":"mp4","quality":"480","videoUrl":""}
            ]};
            </script></body></html>
        """.trimIndent()

        val detail = PhParser.parseDetail(html)
        assertEquals("stub and empty URLs must be dropped", 3, detail.streams.size)
        assertTrue(detail.streams.any { it.label == "720p" && it.hls })
        assertTrue(detail.streams.any { it.label == "1080p" && !it.hls })
        assertTrue(detail.streams.none { it.url.contains("get_media") })
        assertTrue(detail.streams.all { it.url.startsWith("https://") })
    }

    @Test
    fun flashvarsBraceMatchingIgnoresBracesInsideStrings() {
        val html = """
            <script>
            var flashvars_42 = {"text":"brace }{ and quote \" still inside","mediaDefinitions":[]};
            </script>
        """.trimIndent()

        val flash = PhParser.extractFlashvars(html)
        assertNotNull(flash)
        assertEquals(
            "brace }{ and quote \" still inside",
            flash!!["text"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun commentsFixtureYieldsAuthorsAndBodies() {
        val comments = PhParser.parseComments(commentsHtml)

        assertTrue("expected >= 10 comments, got ${comments.size}", comments.size >= 10)
        for (comment in comments) {
            assertTrue("blank author in $comment", comment.author.isNotBlank())
            assertTrue("blank body in $comment", comment.text.isNotBlank())
            assertTrue("negative votes in $comment", comment.votes >= 0)
        }
        assertTrue("expected at least one upvoted comment", comments.any { it.votes > 0 })
    }

    @Test
    fun relatedFixtureYieldsVideos() {
        val related = PhParser.parseRelated(relatedJson, "ph")

        assertTrue("expected >= 10 related videos, got ${related.size}", related.size >= 10)
        for (video in related) {
            assertTrue("blank related vkey in $video", video.vkey.isNotBlank())
            assertTrue("blank related title in $video", video.title.isNotBlank())
        }
        assertEquals("related videos must be deduped", related.size, related.map { it.vkey }.distinct().size)
    }

    @Test
    fun relatedTupleAcceptsProtocolRelativeThumbnails() {
        val raw = """
            [["//pix-cdn.test/video.jpg","Protocol-relative thumb","1:02:03",42,
              "https://www.pornhub.com/view_video.php?viewkey=abc123",987654]]
        """.trimIndent()

        val videos = PhParser.parseRelated(raw, "ph")
        assertEquals(1, videos.size)
        assertEquals("abc123", videos[0].vkey)
        assertEquals("Protocol-relative thumb", videos[0].title)
        assertEquals("https://pix-cdn.test/video.jpg", videos[0].thumb)
        assertEquals(3723, videos[0].durationSec)
        assertEquals(987_654L, videos[0].views)
    }

    @Test
    fun modelFixtureProvidesStarAvatar() {
        val avatar = PhParser.parseStarAvatar(modelHtml)

        assertNotNull("no avatar found in model markup", avatar)
        assertTrue("avatar is not a URL: $avatar", avatar!!.startsWith("https://"))
    }

    @Test
    fun malformedAndEmptyMarkupReturnsEmptyResults() {
        assertTrue(PhParser.parseList("", "ph").isEmpty())
        assertTrue(PhParser.parseList("<html><body><div", "ph").isEmpty())
        assertTrue(
            PhParser.parseList(
                """<div class="pcVideoListItem" data-video-vkey=""><span class="title"><a></a></span></div>""",
                "ph"
            ).isEmpty()
        )

        assertTrue(PhParser.parseDetail("").streams.isEmpty())
        assertTrue(PhParser.parseDetail("<html><body>not a video page").streams.isEmpty())
        assertTrue(PhParser.parseComments("").isEmpty())
        assertTrue(PhParser.parseComments("<html><body><p>no comments").isEmpty())
        assertTrue(PhParser.parseRelated("", "ph").isEmpty())
        assertTrue(PhParser.parseRelated("{not json", "ph").isEmpty())
        assertTrue(PhParser.parseRelated("[]", "ph").isEmpty())
        assertNull(PhParser.extractFlashvars("<html><body>nothing</body></html>"))
        assertNull(PhParser.parseStarAvatar(""))
    }

    @Test
    fun clockAndCountParsing() {
        assertEquals(754, PhParser.parseClock("12:34"))
        assertEquals(3723, PhParser.parseClock("1:02:03"))
        assertEquals(0, PhParser.parseClock(""))
        assertEquals(0, PhParser.parseClock("not a clock"))

        assertEquals(1_200_000L, PhParser.parseCount("1.2M"))
        assertEquals(3_400L, PhParser.parseCount("3,4K"))
        assertEquals(3_400L, PhParser.parseCount("3,400"))
        assertEquals(2_800_000L, PhParser.parseCount("2.8M views"))
        assertEquals(0L, PhParser.parseCount(""))
        assertEquals(0L, PhParser.parseCount(null))
    }

    @Test
    fun blockedPageDetection() {
        assertFalse("a normal feed page is not an interstitial", PhParser.isBlockedPage(listHtml))
        assertFalse(PhParser.isBlockedPage(""))
        assertFalse(PhParser.isBlockedPage("<html><body><p>plain page</p></body></html>"))
        assertFalse(
            "an empty search page still carries the hidden age modal",
            PhParser.isBlockedPage(
                """<html><body><ul id="videoSearchResult"></ul><div id="js-ageDisclaimerModal"></div></body></html>"""
            )
        )

        assertTrue(
            PhParser.isBlockedPage(
                """<html><head><title>Age Verification</title></head><body><div id="ageDisclaimer">Enter your age</div></body></html>"""
            )
        )
        assertTrue(
            PhParser.isBlockedPage(
                """<html><body><div id="accessAgeDisclaimer">You must be 18 or older</div></body></html>"""
            )
        )
        assertTrue(
            PhParser.isBlockedPage(
                """<html><body><h1>Age verification</h1><p>Verify your age before continuing.</p></body></html>"""
            )
        )
    }

    private fun fixture(name: String): String {
        val loader = PhParserTest::class.java.classLoader ?: error("no class loader")
        val stream = loader.getResourceAsStream("ph/$name") ?: error("missing fixture ph/$name")
        return stream.use { it.bufferedReader(Charsets.UTF_8).readText() }
    }
}
