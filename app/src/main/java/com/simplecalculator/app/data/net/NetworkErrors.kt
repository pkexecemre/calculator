package com.simplecalculator.app.data.net

/** Turns verbose JVM TLS/DNS exceptions into one actionable source-health line. */
object NetworkErrors {

    fun describe(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.toList()
        val root = chain.lastOrNull() ?: error
        val text = chain.joinToString(" / ") { t ->
            (t.message ?: t.javaClass.simpleName).trim()
        }
        return when {
            text.contains("Trust anchor", ignoreCase = true) ||
                text.contains("CertPathValidator", ignoreCase = true) ||
                text.contains("SSLHandshakeException", ignoreCase = true) ->
                "TLS certificate not trusted (VPN/ad-block certificate?)"
            text.contains("CertificateExpired", ignoreCase = true) ->
                "TLS certificate expired (check the device date)"
            text.contains("NotYetValid", ignoreCase = true) ->
                "TLS certificate not valid yet (check the device date)"
            text.contains("UnknownHost", ignoreCase = true) ->
                "DNS lookup failed"
            else -> text.ifBlank { root.javaClass.simpleName }
        }.take(160)
    }
}
