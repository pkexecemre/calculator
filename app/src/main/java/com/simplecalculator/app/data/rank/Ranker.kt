package com.simplecalculator.app.data.rank

import com.simplecalculator.app.data.source.VideoSummary

/**
 * Feed ranking. Signals, in order of weight:
 *   followed star  > repeat affinity for an uploader > freshness/position
 * with a sighting penalty and a small exploration jitter so a followed star does
 * not own the entire feed.
 */
class Ranker {

    fun rank(
        candidates: List<VideoSummary>,
        followedSlugs: Set<String>,
        affinity: Map<String, Int>,
        seen: Set<String>,
        bonus: (VideoSummary) -> Double = { 0.0 }
    ): List<VideoSummary> {
        val distinct = candidates.distinctBy { it.key }
        val scored = distinct.mapIndexed { index, video ->
            var score = 1.0 - (index.toDouble() / (distinct.size + 1)) * 0.6
            val slug = video.uploaderSlug
            if (slug != null) {
                if (slug in followedSlugs) score += 3.2
                val hits = affinity[slug] ?: 0
                score += minOf(hits * 0.45, 2.2)
            }
            if (video.key in seen) score -= 1.4
            if (video.viewedBoost()) score += 0.15
            score += bonus(video)
            video to (score + stableJitter(video))
        }
        val ordered = scored.sortedByDescending { it.second }.map { it.first }
        return diversify(ordered)
    }

    /** One general pick for every two starred videos, then whatever is left. */
    fun mix(general: List<VideoSummary>, starred: List<VideoSummary>): List<VideoSummary> {
        if (starred.isEmpty()) return general
        val out = ArrayList<VideoSummary>(general.size + starred.size)
        var g = 0
        var s = 0
        var sinceStarred = 0
        while (g < general.size || s < starred.size) {
            if (s < starred.size && sinceStarred >= 2) {
                out.add(starred[s++]); sinceStarred = 0
            } else if (g < general.size) {
                out.add(general[g++]); sinceStarred++
            } else {
                out.add(starred[s++]); sinceStarred = 0
            }
        }
        return out.distinctBy { it.key }
    }

    /** No more than two consecutive videos from the same uploader. */
    private fun diversify(items: List<VideoSummary>): List<VideoSummary> {
        val out = ArrayList<VideoSummary>(items.size)
        val deferred = ArrayDeque<VideoSummary>()
        var lastSlug: String? = null
        var run = 0
        for (item in items) {
            val slug = item.uploaderSlug
            if (slug != null && slug == lastSlug && run >= 2) {
                deferred.addLast(item)
                continue
            }
            if (slug == lastSlug) run++ else { lastSlug = slug; run = 1 }
            out.add(item)
            if (deferred.isNotEmpty()) {
                val next = deferred.removeFirst()
                if (next.uploaderSlug != lastSlug) {
                    out.add(next)
                    lastSlug = next.uploaderSlug
                    run = 1
                } else {
                    deferred.addLast(next)
                }
            }
        }
        out.addAll(deferred)
        return out
    }

    private fun VideoSummary.viewedBoost(): Boolean =
        durationSec in 45..5400 && views > 0

    /**
     * Deterministic exploration nudge. Using the video key keeps ranking
     * reproducible for the same input while still spreading similar scores.
     */
    private fun stableJitter(video: VideoSummary): Double {
        var hash = video.key.hashCode()
        hash = hash xor (hash ushr 16)
        val normalized = (hash and 0x7fffffff).toDouble() / Int.MAX_VALUE.toDouble()
        return normalized * 0.45
    }
}
