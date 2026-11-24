package com.simplecalculator.app.data.source.generic

/**
 * Decoders for the JS "packer" wrappers used by tube sites to hide playlist
 * URLs. Three layers appear in the wild and are handled in order:
 *
 *  1. `eval(function(p,a,c,k,e,d){…}('…',radix,count,'a|b|c'.split('|'),0,{}))`
 *  2. the same wrapper wrapped in `Cookie.Run("<base64>")`
 *  3. a custom charset cipher: `eval(function(h,u,n,t,e,r){…}(…))`
 */
object JsPacker {

    private const val ALPHA = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ+/"
    private const val MARKER = "eval(function(p,a,c,k,e,d)"
    private const val CUSTOM_MARKER = "eval(function(h,u,n,t,e,r)"

    /** All decoded layers, concatenated; empty when nothing matched. */
    fun unpackAll(html: String): String {
        val output = StringBuilder(unpackStandard(html))
        // Cookie.Run(base64) usually wraps another standard packer.
        var text = output.toString()
        var guard = 0
        while (guard++ < 3) {
            val cookie = unpackCookie(text)
            if (cookie.isEmpty()) break
            output.append('\n').append(cookie)
            val nested = unpackStandard(cookie)
            if (nested.isNotBlank() && !text.contains(nested)) {
                output.append('\n').append(nested)
                text = nested
            } else {
                text = cookie
            }
        }
        val custom = unpackCustom(output.toString())
        if (custom.isNotEmpty()) output.append('\n').append(custom)
        return output.toString()
    }

    fun unpackStandard(html: String): String {
        val out = StringBuilder()
        var from = 0
        while (true) {
            val start = html.indexOf(MARKER, from)
            if (start < 0) break
            val args = html.indexOf("}('", start)
            if (args < 0) break
            val payload = readQuoted(html, args + 2) ?: break
            val radix = readNumber(html, payload.second) ?: break
            val count = readNumber(html, radix.second) ?: break
            val words = readQuoted(html, count.second) ?: break
            val dictionary = words.first.split("|")
            var text = payload.first
            for (index in count.first - 1 downTo 0) {
                if (index < dictionary.size && dictionary[index].isNotEmpty()) {
                    text = Regex("""\b${Regex.escape(baseN(index, radix.first))}\b""")
                        .replace(text, Regex.escapeReplacement(dictionary[index]))
                }
            }
            out.append(text).append('\n')
            from = words.second
        }
        return out.toString()
    }

    /** `Cookie.Run("chunk"+"chunk")` -> base64 -> nested packer. */
    private fun unpackCookie(text: String): String {
        val marker = text.indexOf("Cookie.Run(")
        if (marker < 0) return ""
        val end = text.indexOf(')', marker)
        if (end < 0) return ""
        val argument = text.substring(marker + 11, end)
        val decoded = Regex("""["']([A-Za-z0-9+/=]{16,})["']""").findAll(argument)
            .joinToString("") { it.groupValues[1] }
        if (decoded.isEmpty()) return ""
        val source = runCatching {
            String(java.util.Base64.getDecoder().decode(decoded), Charsets.UTF_8)
        }.getOrNull() ?: return ""
        return if (source.contains(MARKER)) unpackStandard(source) else source
    }

    /** Custom charset cipher; returns the JS it decrypts to. */
    private fun unpackCustom(text: String): String {
        val start = text.indexOf(CUSTOM_MARKER)
        if (start < 0) return ""
        val argsStart = text.indexOf("}(", start)
        if (argsStart < 0) return ""
        var index = argsStart + 2
        val payload = readQuoted(text, index) ?: return ""
        index = payload.second
        val radix = readNumber(text, index) ?: return ""
        index = radix.second
        val charset = readQuoted(text, index) ?: return ""
        index = charset.second
        val subtract = readNumber(text, index) ?: return ""
        index = subtract.second
        val base = readNumber(text, index) ?: return ""

        val delimiter = charset.first[base.first]
        val bytes = ArrayList<Byte>()
        var i = 0
        val data = payload.first
        while (i < data.length) {
            val chunk = StringBuilder()
            while (i < data.length && data[i] != delimiter) {
                chunk.append(data[i])
                i++
            }
            i++
            var digits = chunk.toString()
            for (j in charset.first.indices) {
                digits = digits.replace(charset.first[j].toString(), j.toString())
            }
            val value = baseToLong(digits, base.first) - subtract.first
            if (value in 0..255) bytes.add(value.toInt().toByte()) else if (value > 255) {
                // Non-latin payloads are not produced by these players.
                break
            }
        }
        return runCatching { String(bytes.toByteArray(), Charsets.UTF_8) }.getOrDefault("")
    }

    private fun baseToLong(digits: String, radix: Int): Long {
        val alphabet = if (radix <= ALPHA.length) ALPHA.substring(0, radix) else ALPHA
        var total = 0L
        for (ch in digits) {
            val digit = alphabet.indexOf(ch)
            if (digit < 0) continue
            total = total * radix + digit
        }
        return total
    }

    private fun baseN(value: Int, radix: Int): String {
        if (radix <= 0) return value.toString()
        if (value < radix) return ALPHA[value % ALPHA.length].toString()
        return baseN(value / radix, radix) + ALPHA[value % radix % ALPHA.length]
    }

    private fun readQuoted(text: String, from: Int): Pair<String, Int>? {
        var index = from
        while (index < text.length && text[index] != '\'' && text[index] != '"') index++
        if (index >= text.length) return null
        val quote = text[index]
        val value = StringBuilder()
        index++
        while (index < text.length) {
            val ch = text[index]
            when {
                ch == '\\' && index + 1 < text.length -> {
                    value.append(text[index + 1])
                    index += 2
                }
                ch == quote -> return value.toString() to index + 1
                else -> {
                    value.append(ch)
                    index++
                }
            }
        }
        return null
    }

    private fun readNumber(text: String, from: Int): Pair<Int, Int>? {
        var index = from
        while (index < text.length && !text[index].isDigit()) {
            if (text[index] != ',') return null
            index++
        }
        val start = index
        while (index < text.length && text[index].isDigit()) index++
        val value = text.substring(start, index).toIntOrNull() ?: return null
        return value to index
    }
}
