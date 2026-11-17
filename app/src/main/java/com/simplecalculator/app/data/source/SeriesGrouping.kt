package com.simplecalculator.app.data.source

/** A hentai series collapsed from one or more source listings. */
data class SeriesGroup(
    val key: String,
    val title: String,
    val thumb: String,
    val videos: List<VideoSummary>,
    val sourceIds: List<String>
) {
    val episodeCount: Int get() = videos.size

    /** Episode range the source advertises, e.g. "EP 1-7" -> 7. */
    val advertisedEpisodes: Int
        get() = videos.firstNotNullOfOrNull { video ->
            Regex("""(?i)EP\s*\d+\s*-\s*(\d+)""")
                .find(video.episodeLabel.orEmpty())
                ?.groupValues?.get(1)?.toIntOrNull()
        } ?: 0

    /** Card badge: the real count once expanded, the advertised range before. */
    val displayEpisodes: Int get() = maxOf(episodeCount, advertisedEpisodes)
}

/**
 * Turns flat hentai listings into series cards. Episode numbers from titles and
 * source URLs are used to drop the same episode mirrored on five different
 * sites, while genuinely different episodes are kept and sorted.
 */
object SeriesGrouping {

    private val episodeInTitle = Regex(
        """(?i)\b(?:ep(?:isode)?\.?|ep)\s*[#\-_ ]?\s*(\d{1,4})\b"""
    )
    private val episodeInPath = Regex("""(?i)(?:episode|ep)[-_/]?(\d{1,4})""")
    private val volEpisode = Regex("""(?i)\bvol\.?\s*(\d+)|ตอนที่\s*(\d+)""")
    // Longest alternatives first: otherwise "Sub-Eng" matches "sub" and leaves "-Eng".
    private val noisyWords = Regex(
        """(?i)\b(remove ?censored|eng-sub|sub-eng|subbed|english sub|uncensored|censored|sub|dub|raw|1080p|720p|480p|360p|hd|sd|english|japanese|eng|thai|ซับไทย)\b|ตอนที่"""
    )
    private val THAI_MARKERS = Regex("""(?i)ซับไทย|ไทย|พากย์ไทย""")
    private val trailingNumber = Regex("""\s*[-–—|]\s*\d+\s*$""")
    private val brackets = Regex("""[\(\[][^\)\]]*[\)\]]""")
    private val trailingPunctuation = Regex("""\s*[-–—|,.:]+\s*$""")
    private val spaces = Regex("""\s+""")

    fun seriesTitle(video: VideoSummary): String {
        var text = video.title.replace('_', ' ')
        text = episodeInTitle.replace(text, " ")
        text = volEpisode.replace(text, " ")
        // Thai has no word boundaries, so strip these separately.
        text = THAI_MARKERS.replace(text, " ")
        text = noisyWords.replace(text, " ")
        text = brackets.replace(text, " ")
        text = trailingNumber.replace(text, " ")
        text = trailingPunctuation.replace(text, " ")
        text = spaces.replace(text, " ").trim(' ', '-', '–', '—', '|', '.', ',', ':')
        return text.ifBlank { video.title.trim() }
    }

    fun seriesKey(video: VideoSummary): String = seriesTitle(video).lowercase()

    fun episodeKey(video: VideoSummary): String =
        episodeInTitle.find(video.title)?.groupValues?.get(1)
            ?: volEpisode.find(video.title)?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }
            ?: video.episodeLabel?.let { label ->
                volEpisode.find(label)?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }
                    // "EP 1-3" is a range, not an episode; treat it as a series card.
                    ?: label.trim().takeIf { it.toIntOrNull() != null }
            }
            ?: episodeInPath.find(video.vkey)?.groupValues?.get(1)
            ?: episodeInPath.find(video.title)?.groupValues?.get(1)
            // No episode number: mirrors of the same title are the same entry,
            // not one row per source.
            ?: "title:" + seriesKey(video)

    private fun episodeNumber(video: VideoSummary): Int =
        episodeKey(video).toIntOrNull() ?: Int.MAX_VALUE

    /** Applies the same de-duplication rules to an already-built group. */
    fun rebuild(base: SeriesGroup, videos: List<VideoSummary>): SeriesGroup {
        val ordered = videos
            .sortedWith(compareBy<VideoSummary> { episodeNumber(it) }.thenBy { it.key })
            .distinctBy(::episodeKey)
            .let { list ->
                if (list.any { !episodeKey(it).startsWith("title:") }) {
                    list.filterNot { episodeKey(it).startsWith("title:") }
                } else {
                    list
                }
            }
        if (ordered.isEmpty()) return base
        return base.copy(
            title = seriesTitle(ordered.first()),
            thumb = ordered.firstNotNullOfOrNull { it.thumb.takeIf(String::isNotBlank) } ?: base.thumb,
            videos = ordered,
            sourceIds = ordered.map { it.sourceId }.distinct()
        )
    }

    fun group(items: List<VideoSummary>): List<SeriesGroup> {
        val bySeries = LinkedHashMap<String, MutableList<VideoSummary>>()
        for (video in items) {
            if (video.category != Category.HENTAI) continue
            bySeries.getOrPut(seriesKey(video)) { ArrayList() }.add(video)
        }
        return bySeries.map { (key, videos) ->
            val ordered = videos
                .sortedWith(compareBy<VideoSummary> { episodeNumber(it) }.thenBy { it.key })
                .distinctBy(::episodeKey)
                // If the source gave real episode numbers, the unnumbered
                // mirrors are the same series, not extra episodes.
                .let { list ->
                    if (list.any { !episodeKey(it).startsWith("title:") }) {
                        list.filterNot { episodeKey(it).startsWith("title:") }
                    } else {
                        list
                    }
                }
            SeriesGroup(
                key = key,
                title = seriesTitle(ordered.first()),
                thumb = ordered.firstNotNullOfOrNull { it.thumb.takeIf(String::isNotBlank) }.orEmpty(),
                videos = ordered,
                sourceIds = ordered.map { it.sourceId }.distinct()
            )
        }.sortedByDescending { it.videos.maxOfOrNull { video -> video.views } ?: 0 }
    }
}
