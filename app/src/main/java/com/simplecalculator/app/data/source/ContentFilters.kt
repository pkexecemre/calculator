package com.simplecalculator.app.data.source

/**
 * Title-level filters used by the merged feeds. Parsers stay dumb; these rules
 * are shared by every source and are pure, so the feed policy is testable.
 */
object ContentFilters {

    /** Japanese/Chinese/Korean script. Live is a Western section. */
    private val CJK = Regex("[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uac00-\\ud7af]")

    /**
     * Hidden-camera / voyeur / toilet content. The JAV tab is for ordinary
     * studio releases; this kind of title should not own the trending feed.
     */
    private val LOW_QUALITY = Regex(
        """(?i)\b(toilet|toire|hidden[ ._-]?cam(era)?|secret[ ._-]?cam|voyeur|spy[ ._-]?cam|peeping|peep(ing)?|upskirt|scat|poop|urine|golden[ ._-]?shower|pee(ing)?|tousatsu|tosatsu|kakushi.?dori|nozoki|omorashi|houryou)\b|トイレ|盗撮|隠し撮り|のぞき|覗き|おもらし|放尿|排泄|スカトロ|うんこ|偷拍|偷窺|偷窥|厕所|廁所"""
    )

    private val AMATEUR = Regex(
        """(?i)\b(amateur|homemade|ugc|personal[ ._-]?filming|private[ ._-]?filming|fc2|fc2-ppv)\b|素人|個人撮影|ハメ撮り"""
    )

    /** JAV product codes such as SSIS-123 or ssis-123. */
    private val PRODUCT_CODE = Regex("""(?i)\b[a-z]{2,6}[-_]\d{2,5}\b""")

    /** Japanese live catalogues: selectable in Filters, but not in the default Live feed. */
    val JAPANESE_LIVE_SOURCES: Set<String> = setOf("141", "avg")

    fun isCjkTitle(title: String): Boolean = CJK.containsMatchIn(title)

    /**
     * Applies the category policy to a merged pool. Explicit source selection
     * still wins: picking 141tube or Avgle shows their Japanese catalogue.
     */
    fun filterFeed(
        items: List<VideoSummary>,
        category: Category,
        sourceFilter: String?
    ): List<VideoSummary> = when (category) {
        Category.JAV -> items.filterNot { isLowQuality(it.title, it.vkey) }
        Category.LIVE -> {
            val allowCjk = sourceFilter != null && sourceFilter in JAPANESE_LIVE_SOURCES
            items.filterNot { video ->
                (video.sourceId in JAPANESE_LIVE_SOURCES && sourceFilter == null) ||
                    (!allowCjk && isCjkTitle(video.title))
            }
        }
        else -> items
    }

    fun rankBonus(category: Category): (VideoSummary) -> Double = when (category) {
        Category.JAV -> { video ->
            qualityScore(video.title, video.vkey, video.sourceId)
        }
        else -> { _ -> 0.0 }
    }

    fun isLowQuality(title: String, vkey: String): Boolean =
        LOW_QUALITY.containsMatchIn(title) || LOW_QUALITY.containsMatchIn(vkey)

    fun isAmateur(title: String, vkey: String): Boolean =
        AMATEUR.containsMatchIn(title) || AMATEUR.containsMatchIn(vkey)

    /**
     * Positive for normal studio releases and negative for amateur/UGC feeds.
     * Used only as a ranking nudge; explicit low-quality titles are filtered
     * before ranking.
     */
    fun qualityScore(title: String, vkey: String, sourceId: String): Double {
        val text = title + " " + vkey
        var score = 0.0
        if (PRODUCT_CODE.containsMatchIn(text)) score += 1.2
        score += when (sourceId) {
            "msav" -> 1.0
            "jvs", "jvn", "kjv" -> 0.7
            "85p", "jvf" -> 0.3
            "mrd" -> 0.0
            "tmo" -> -1.5
            else -> 0.0
        }
        if (isAmateur(title, vkey)) score -= 0.8
        return score
    }
}
