package com.simplecalculator.app.data.source.generic

/**
 * Listing cards often prepend metadata to the title ("2K 7:19 …", "HD 26:22 …
 * 124K 82% 1週間前"). These are quality, duration, view count, rating and age —
 * not the title — so they are stripped before the card reaches the UI.
 */
object Titles {

    private val quality = Regex("""^\s*(8K|4K|2K|FHD|HD|SD)\b[\s·|,-]*""", RegexOption.IGNORE_CASE)
    private val clock = Regex("""^\s*\d{1,2}:\d{2}(:\d{2})?[\s·|,-]*""")
    private val count = Regex("""^\s*\d+(\.\d+)?\s*[KMB]\b[\s·|,-]*""", RegexOption.IGNORE_CASE)
    private val percent = Regex("""^\s*\d{1,3}\s*%[\s·|,-]*""")
    private val age = Regex(
        """^\s*\d+\s*(seconds?|minutes?|mins?|hours?|hrs?|days?|weeks?|months?|years?|秒|分|時間|日|週間|週|ヶ月|か月|年)\s*(ago|前)[\s·|,-]*""",
        RegexOption.IGNORE_CASE
    )

    private val trailingCount = Regex("""[\s·|,-]+\d+(\.\d+)?\s*[KMB]$""", RegexOption.IGNORE_CASE)
    private val trailingPercent = Regex("""[\s·|,-]+\d{1,3}\s*%$""")
    private val trailingAge = Regex(
        """[\s·|,-]+\d+\s*(seconds?|minutes?|mins?|hours?|hrs?|days?|weeks?|months?|years?|秒|分|時間|日|週間|週|ヶ月|か月|年)\s*(ago|前)$""",
        RegexOption.IGNORE_CASE
    )
    private val trailingClock = Regex("""[\s·|,-]+\d{1,2}:\d{2}(:\d{2})?$""")
    private val leadingCount = Regex(
        """^\s*\d+\s+(videos?|views?|photos?|pics?|images?|clips?)\b[\s·|,-]*""",
        RegexOption.IGNORE_CASE
    )
    // Some cards glue the view count straight onto the date: "… 6,819Nov 17, 2026".
    private val trailingDate = Regex(
        """[\s·|,-]*(?:[A-Z][a-z]{2,8}\s+\d{1,2},\s*\d{4}|\d{1,2}\s+[A-Z][a-z]{2,8}\s+\d{4})$"""
    )
    private val trailingGrouped = Regex("""[\s·|,-]*\d{1,3}(,\d{3})+$""")

    fun clean(raw: String): String {
        var text = raw.replace(Regex("\\s+"), " ").trim()
        var guard = 0
        while (guard++ < 6) {
            val before = text
            text = quality.replace(text, "")
            text = clock.replace(text, "")
            text = count.replace(text, "")
            text = percent.replace(text, "")
            text = age.replace(text, "")
            text = trailingPercent.replace(text, "")
            text = trailingCount.replace(text, "")
            text = trailingAge.replace(text, "")
            text = trailingClock.replace(text, "")
            text = leadingCount.replace(text, "")
            if (text.length > 10) {
                text = trailingDate.replace(text, "")
                text = trailingGrouped.replace(text, "")
            }
            text = text.trim(' ', '·', '|', '-', ',', ':', '　')
            if (text == before) break
        }
        return text.ifBlank { raw.trim() }
    }

    /** True for values that are metadata rather than a title. */
    fun isMetadataOnly(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.length < 2) return true
        if (clock.matches(trimmed) || percent.matches(trimmed)) return true
        return clean(trimmed).isBlank()
    }
}
