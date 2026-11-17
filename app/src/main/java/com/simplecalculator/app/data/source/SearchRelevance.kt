package com.simplecalculator.app.data.source

/** Keeps fuzzy site search from filling hentai results with unrelated slop. */
object SearchRelevance {

    private val stopwords = setOf(
        "the", "and", "for", "with", "from", "that", "this", "full", "movie",
        "video", "watch", "free", "sub", "subbed", "uncensored", "episode"
    )

    fun tokens(query: String): List<String> = query.lowercase()
        .split(Regex("[^a-z0-9\\p{L}]+"))
        .filter { it.length >= 3 && it !in stopwords }
        .distinct()

    fun matches(title: String, query: String): Boolean {
        val terms = tokens(query)
        if (terms.isEmpty()) return true
        val haystack = title.lowercase()
        return terms.any { haystack.contains(it) }
    }
}
