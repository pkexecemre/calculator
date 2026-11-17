package com.simplecalculator.app.data.source.hentai

/**
 * The remaining two sites are recorded here with their verified reachability but
 * no parser yet. They are deliberately not registered as sources: an empty feed
 * that looks like a bug is worse than a known gap.
 *
 *   hstream.moe  reachable, server-rendered, links are absolute and were not
 *                resolved into a stable card selector yet.
 *   hanime.tv    Cloudflare answers 403 to every non-browser request, including
 *                from the VPS; its API needs a browser-side challenge token.
 */
internal object PendingSources {
    val pending: List<Pair<String, String>> = listOf(
        "hstream.moe" to "card selector unresolved",
        "hanime.tv" to "Cloudflare 403 without a browser challenge"
    )
}
