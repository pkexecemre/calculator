package com.simplecalculator.app.data.source

/**
 * Default-feed policy for the single Pornhub source.
 *
 * Pornhub's most-viewed feed mixes Western clips with Japanese catalogue
 * uploads. One watched Japanese video used to push its uploader (and everything
 * that looks like it) to the top of the feed, so the default feed is Western
 * again: Japanese-script titles and JAV catalogue codes are held back unless
 * the viewer opts in. Search and star catalogues are unaffected — those are
 * explicit requests, not recommendations.
 */
object FeedPolicy {

    // Kana, CJK ideographs, Hangul and half-width katakana.
    private val NON_LATIN = Regex(
        "[\\u3040-\\u30ff\\u31f0-\\u31ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uac00-\\ud7af\\uff66-\\uff9f]"
    )

    // JAV releases are often titled in romaji, e.g. "SSIS-123 ...".
    private val CATALOG_CODE = Regex("""(?i)\b[a-z]{2,6}[-_]\d{2,5}\b""")

    // Some uploads say the genre in English instead of using a catalogue code.
    private val JAPAN_WORD = Regex("""(?i)\b(japan|japanese|jav|hentai)\b""")

    fun isJapaneseCatalog(video: VideoSummary): Boolean {
        val text = video.title + " " + video.vkey
        return NON_LATIN.containsMatchIn(video.title) ||
            CATALOG_CODE.containsMatchIn(text) ||
            JAPAN_WORD.containsMatchIn(video.title)
    }

    fun visibleInFeed(video: VideoSummary, showJapanese: Boolean): Boolean =
        showJapanese || !isJapaneseCatalog(video)

    /** Used only when filtering everything would leave a blank screen. */
    fun rankPenalty(video: VideoSummary, showJapanese: Boolean): Double =
        if (!showJapanese && isJapaneseCatalog(video)) -2.5 else 0.0
}
