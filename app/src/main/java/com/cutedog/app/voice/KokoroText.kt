package com.cutedog.app.voice

import java.io.DataInputStream
import java.io.InputStream
import java.util.Locale

/** Mandarin word pronunciations include contextual readings and tone sandhi from the model lexicon. */
internal class KokoroText(input: InputStream) {
    data class Phrase(val ids: LongArray, val speed: Float, val pauseMillis: Int)
    private val words: Array<String>
    private val phones: Array<ByteArray>

    init {
        DataInputStream(input.buffered()).use { stream ->
            val size = stream.readInt()
            require(size in 1..350000)
            words = Array(size) { "" }
            phones = Array(size) { index ->
                val keySize = stream.readUnsignedShort()
                val count = stream.readUnsignedByte()
                val key = ByteArray(keySize).also(stream::readFully)
                words[index] = String(key, Charsets.UTF_8)
                ByteArray(count).also(stream::readFully)
            }
        }
    }

    fun phrases(text: String): List<Phrase> {
        val normalized = numbers(text).lowercase(Locale.ROOT)
        val output = mutableListOf<Phrase>()
        val tokens = ArrayList<Long>(120)
        fun flush(mark: Char) {
            if (tokens.isEmpty()) return
            val id = when (mark) { '?' -> 6L; '!' -> 5L; ',' -> 3L; ':' -> 2L; ';' -> 1L; else -> 4L }
            tokens.add(id)
            val speed = when (mark) { '!' -> 1.07f; '?' -> 1.04f; else -> 1.055f }
            val pause = when (mark) { ',', ':', ';' -> 65; else -> 110 }
            output += Phrase(longArrayOf(0) + tokens.toLongArray() + longArrayOf(0), speed, pause)
            tokens.clear()
        }
        var i = 0
        while (i < normalized.length) {
            val c = normalized[i]
            val mark = when (c) {
                '。', '.', '\n', '…' -> '.'; '！','!' -> '!'; '？','?' -> '?'
                '，',',','、' -> ','; '：',':' -> ':'; '；',';' -> ';'; else -> null
            }
            if (mark != null) {
                if (mark in ",:;" && tokens.size < 75) {
                    if (tokens.isNotEmpty() && tokens.last() !in 1L..6L) tokens += when(mark) { ':' -> 2L; ';' -> 1L; else -> 3L }
                } else flush(mark)
                i++
                continue
            }
            if (c.isWhitespace()) { i++; continue }
            var end = minOf(i + 14, normalized.length)
            var found = -1
            while (end > i) {
                found = words.binarySearch(normalized.substring(i, end))
                if (found >= 0) break
                end--
            }
            if (found < 0) { i += Character.charCount(Character.codePointAt(normalized, i)); continue }
            if (tokens.size + phones[found].size > 120) flush(',')
            // The first of two adjacent Mandarin third tones becomes a rising tone.
            if (c in '\u3400'..'\u9fff' && tokens.lastOrNull() == 169L) {
                val firstTone = phones[found].firstOrNull { (it.toInt() and 255) in 169..173 }
                if (firstTone != null && (firstTone.toInt() and 255) == 169) tokens[tokens.lastIndex] = 172L
            }
            for (id in phones[found]) tokens += (id.toInt() and 255).toLong()
            i = end
        }
        flush('.')
        return output
    }

    companion object {
        private const val DIGITS = "零一二三四五六七八九"
        private val number = Regex("[0-9]+(?:\\.[0-9]+)?")
        private fun whole(digits: String): String {
            if (digits.length == 1) return DIGITS[digits[0] - '0'].toString()
            if (digits.length > 4 || digits[0] == '0') return digits.map { DIGITS[it-'0'] }.joinToString("")
            return buildString {
                var zero = false
                for ((i, c) in digits.withIndex()) {
                    val n = c - '0'
                    if (n == 0) { zero = isNotEmpty(); continue }
                    if (zero) append('零')
                    zero = false
                    val place = digits.lastIndex - i
                    if (!(n == 1 && place == 1 && isEmpty())) append(DIGITS[n])
                    if (place > 0) append(" 十百千"[place])
                }
            }
        }
        private fun numbers(text: String): String = number.replace(text) { match ->
            val parts = match.value.split('.')
            val year = text.getOrNull(match.range.last + 1) == '年' && parts[0].length == 4
            val integral = if (year) parts[0].map { DIGITS[it-'0'] }.joinToString("") else whole(parts[0])
            integral + if (parts.size == 2) "点" + parts[1].map { DIGITS[it-'0'] }.joinToString("") else ""
        }
    }
}
