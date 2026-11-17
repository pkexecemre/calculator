package com.simplecalculator.app.data.source.hentai

import okhttp3.OkHttpClient

/** Iwara's AI sister site uses the same public API with a different origin. */
class IwaraAiSource(http: OkHttpClient) : IwaraSource(
    http = http,
    id = "iwai",
    displayName = "Iwara.ai",
    web = "https://www.iwara.ai"
)
