package com.simplecalculator.app.data.source

/**
 * Canonical tag vocabulary. Every site's own wording is folded into these ids so
 * the filter chips mean the same thing across sources.
 *
 * The blocklist at the bottom is not a preference and is not user-editable: any
 * item whose tags hit it is dropped before it reaches the UI.
 */
object Tags {

    const val RELATION = "relation"
    const val ACT = "act"
    const val FORM = "form"
    const val HARD = "hard"
    const val GENRE = "genre"
    const val PRODUCTION = "production"

    data class Tag(val id: String, val label: String, val group: String)

    val all: List<Tag> = listOf(
        Tag("vanilla", "Vanilla", RELATION),
        Tag("romance", "Romance", RELATION),
        Tag("harem", "Harem", RELATION),
        Tag("ntr", "NTR", RELATION),
        Tag("netori", "Netori", RELATION),
        Tag("affair", "Affair", RELATION),
        Tag("childhood", "Childhood friend", RELATION),
        Tag("senpai", "Senpai / kouhai", RELATION),
        Tag("coworker", "Coworker", RELATION),
        Tag("teacher", "Teacher", RELATION),
        Tag("milf", "MILF", RELATION),
        Tag("mature", "Mature", RELATION),
        Tag("sister", "Sister", RELATION),
        Tag("mother", "Mother", RELATION),
        Tag("stepfamily", "Step-family", RELATION),
        Tag("incest", "Incest", RELATION),
        Tag("widow", "Widow", RELATION),
        Tag("pregnant", "Pregnant", RELATION),
        Tag("marriage", "Marriage", RELATION),

        Tag("oral", "Oral", ACT),
        Tag("anal", "Anal", ACT),
        Tag("paizuri", "Paizuri", ACT),
        Tag("ahegao", "Ahegao", ACT),
        Tag("creampie", "Creampie", ACT),
        Tag("squirt", "Squirt", ACT),
        Tag("threesome", "Threesome", ACT),
        Tag("group", "Group", ACT),
        Tag("public", "Public", ACT),
        Tag("exhibition", "Exhibitionism", ACT),
        Tag("voyeur", "Voyeur", ACT),
        Tag("filming", "Filming", ACT),
        Tag("bondage", "Bondage", ACT),
        Tag("bdsm", "BDSM", ACT),
        Tag("femdom", "Femdom", ACT),
        Tag("maledom", "Maledom", ACT),
        Tag("mindcontrol", "Mind control", ACT),
        Tag("corruption", "Corruption", ACT),
        Tag("first", "First time", ACT),

        Tag("tentacle", "Tentacle", FORM),
        Tag("monster", "Monster", FORM),
        Tag("monstergirl", "Monster girl", FORM),
        Tag("orc", "Orc / demon", FORM),
        Tag("futanari", "Futanari", FORM),
        Tag("genderbend", "Gender bender", FORM),
        Tag("crossdress", "Crossdress", FORM),
        Tag("yuri", "Yuri", FORM),
        Tag("yaoi", "Yaoi", FORM),
        Tag("bara", "Bara", FORM),

        Tag("guro", "Guro", HARD),
        Tag("ryona", "Ryona", HARD),
        Tag("vore", "Vore", HARD),
        Tag("scat", "Scat", HARD),
        Tag("urine", "Urine", HARD),
        Tag("necro", "Necro", HARD),

        Tag("fantasy", "Fantasy", GENRE),
        Tag("isekai", "Isekai", GENRE),
        Tag("scifi", "Sci-fi", GENRE),
        Tag("historical", "Historical", GENRE),
        Tag("school", "School", GENRE),
        Tag("office", "Office", GENRE),
        Tag("magic", "Magic", GENRE),
        Tag("mecha", "Mecha", GENRE),
        Tag("horror", "Horror", GENRE),
        Tag("comedy", "Comedy", GENRE),
        Tag("drama", "Drama", GENRE),
        Tag("action", "Action", GENRE),

        Tag("ova", "OVA", PRODUCTION),
        Tag("cgi", "3DCG", PRODUCTION),
        Tag("doujin", "Doujin", PRODUCTION),
        Tag("voiced", "Voiced", PRODUCTION),
        Tag("subbed", "Subbed", PRODUCTION),
        Tag("uncensored", "Uncensored", PRODUCTION),
        Tag("censored", "Censored (修正あり)", PRODUCTION)
    )

    private val byLabel: Map<String, String> =
        all.associate { it.label.lowercase() to it.id } + all.associate { it.id to it.id }

    /** Site-specific wording folded onto canonical ids. */
    private val aliases: Map<String, String> = mapOf(
        "netorare" to "ntr",
        "cuckold" to "ntr",
        "cheating" to "affair",
        "adultery" to "affair",
        "schoolgirl" to "school",
        "student" to "school",
        "big breasts" to "milf",
        "motherly" to "mother",
        "sisterly" to "sister",
        "stepsister" to "stepfamily",
        "stepmother" to "stepfamily",
        "step mother" to "stepfamily",
        "step-sister" to "stepfamily",
        "aunt" to "incest",
        "cousin" to "incest",
        "hypnosis" to "mindcontrol",
        "mind break" to "corruption",
        "tentacles" to "tentacle",
        "monster girl" to "monstergirl",
        "futa" to "futanari",
        "futanari on female" to "futanari",
        "trap" to "crossdress",
        "cross-dressing" to "crossdress",
        "girls love" to "yuri",
        "boys love" to "yaoi",
        "yaoi" to "yaoi",
        "shibari" to "bondage",
        "sm" to "bdsm",
        "s&m" to "bdsm",
        "public sex" to "public",
        "exhibitionism" to "exhibition",
        "creampie" to "creampie",
        "nakadashi" to "creampie",
        "3d" to "cgi",
        "3dcg" to "cgi",
        "uncensored" to "uncensored",
        "censored" to "censored",
        "mosaic censorship" to "censored",
        "decensored" to "uncensored",
        "english" to "subbed",
        "subtitled" to "subbed"
    )

    /**
     * Explicit metadata tags are trusted, but matched on word boundaries so
     * "childhood friend" or "minority" are not caught by "child"/"minor".
     */
    private val blockedTags = listOf(
        "loli", "lolicon", "lolita", "shota", "shotacon",
        "child", "children", "kid", "kids", "toddler",
        "preteen", "pre-teen", "underage", "under-age", "minor",
        "elementary", "primary school",
        "小学生", "児童", "幼女", "ロリ", "ショタ"
    )

    /**
     * Titles are noisy, so only high-confidence phrases count here. A bare
     * "child", "kid", "minor" or "少女" in a title is intentionally allowed.
     */
    private val blockedTitlePhrases = listOf(
        "loli", "lolicon", "shota", "shotacon",
        "underage", "under age", "preteen", "pre teen", "toddler",
        "elementary school", "primary school",
        "child porn", "child sexual", "kid porn",
        "ロリ", "ショタ", "幼女", "小学生", "児童"
    )

    private val titleExceptions = listOf(
        "childhood friend", "kidnap", "kidnapped", "kidnapping", "minority"
    )

    private fun termRegex(term: String): Regex {
        // CJK has no word boundaries; substring is the only useful match there.
        if (term.any { it.code > 0x2E80 }) return Regex(Regex.escape(term))
        return Regex(
            "(^|[^a-z0-9])${Regex.escape(term)}([^a-z0-9]|$)",
            RegexOption.IGNORE_CASE
        )
    }

    private val blockedTagRegexes = blockedTags.map(::termRegex)
    private val blockedTitleRegexes = blockedTitlePhrases.map(::termRegex)

    fun canonical(raw: String): String? {
        val key = raw.trim().lowercase()
        if (key.isEmpty()) return null
        aliases[key]?.let { return it }
        byLabel[key]?.let { return it }
        return all.firstOrNull { key.contains(it.id) }?.id
    }

    fun normalize(rawTags: List<String>): List<String> =
        rawTags.mapNotNull { canonical(it) }.distinct()

    fun label(id: String): String = all.firstOrNull { it.id == id }?.label ?: id

    fun groupOf(id: String): String = all.firstOrNull { it.id == id }?.group ?: GENRE

    private fun matchesAny(raw: String, regexes: List<Regex>): Boolean {
        if (raw.isBlank()) return false
        val key = raw.replace(Regex("\\s+"), " ").trim()
        return regexes.any { it.containsMatchIn(key) }
    }

    fun isBlocked(raw: String): Boolean = matchesAny(raw, blockedTagRegexes)

    fun isBlockedTitle(raw: String): Boolean {
        if (raw.isBlank()) return false
        val lower = raw.lowercase()
        if (titleExceptions.any { lower.contains(it) }) return false
        return matchesAny(raw, blockedTitleRegexes)
    }

    fun isAllowed(rawTags: List<String>, title: String): Boolean =
        rawTags.none(::isBlocked) && !isBlockedTitle(title)
}
