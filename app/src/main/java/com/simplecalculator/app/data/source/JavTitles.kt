package com.simplecalculator.app.data.source

/** Censorship heuristics for JAV listings, shared by the feed filter and the UI. */
object JavTitles {

    private val uncensored = Regex(
        """(?i)uncensor|無修正|無碼|无码|uncen\b|leak|-leak|decensor""",
        RegexOption.IGNORE_CASE
    )
    private val censored = Regex("""(?i)censor|修正あり|有碼|有码|mosaic""")

    private val subtitled = Regex(
        """(?i)english.?sub|eng.?sub|sub.?eng|subtitled|subbed|-subtitle|chinese.?sub|字幕""",
        RegexOption.IGNORE_CASE
    )

    /** Releases that ship with (usually burned-in) subtitles. */
    fun isSubtitled(title: String, vkey: String): Boolean =
        subtitled.containsMatchIn(title) || subtitled.containsMatchIn(vkey)

    fun isUncensored(title: String, vkey: String): Boolean =
        uncensored.containsMatchIn(title) || uncensored.containsMatchIn(vkey)

    /** Everything that is not flagged as a leak still ships mosaic-censored. */
    fun isCensored(title: String, vkey: String): Boolean = !isUncensored(title, vkey)
}
