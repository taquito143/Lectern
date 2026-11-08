package com.lectern.app.tts

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.Normalizer
import java.util.zip.GZIPInputStream

/**
 * English text -> Kokoro phonemes using misaki's pronunciation dictionaries
 * (us_gold + us_silver, the same lexicon Kokoro was trained with).
 * Words missing from the dictionary are tried without common suffixes, then spelled out.
 */
class EnglishPhonemizer(context: Context) {

    private val dict: HashMap<String, String> = HashMap(200_000)

    init {
        context.assets.open("en_dict.tsv.gz").use { raw ->
            BufferedReader(InputStreamReader(GZIPInputStream(raw), Charsets.UTF_8), 1 shl 16).forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) dict[line.substring(0, tab)] = line.substring(tab + 1)
            }
        }
    }

    private fun lookup(word: String): String? =
        dict[word] ?: dict[word.lowercase()] ?: dict[word.lowercase().replaceFirstChar { it.uppercase() }]

    private fun stem(word: String): String? {
        lookup(word)?.let { return it }
        val w = word.lowercase()
        // possessive / plural
        if (w.endsWith("'s") || w.endsWith("’s")) lookup(w.dropLast(2))?.let { return it + sibilantS(it) }
        if (w.endsWith("s'")) lookup(w.dropLast(1))?.let { return it }
        if (w.endsWith("ies")) lookup(w.dropLast(3) + "y")?.let { return it.dropLast(1) + "iz" }
        if (w.endsWith("es")) lookup(w.dropLast(2))?.let { return it + "ɪz" }
        if (w.endsWith("s")) lookup(w.dropLast(1))?.let { return it + sibilantS(it) }
        if (w.endsWith("ed")) {
            (lookup(w.dropLast(2)) ?: lookup(w.dropLast(1)))?.let { base ->
                val last = base.lastOrNull()
                return base + when (last) { 't', 'd' -> "ᵻd"; 'p', 'k', 'f', 's', 'ʃ', 'ʧ', 'θ' -> "t"; else -> "d" }
            }
        }
        if (w.endsWith("ing")) (lookup(w.dropLast(3)) ?: lookup(w.dropLast(3) + "e"))?.let { return it + "ɪŋ" }
        if (w.endsWith("ly")) lookup(w.dropLast(2))?.let { return it + "li" }
        // compounds written with a hyphen
        if ('-' in w) {
            val parts = w.split('-').filter { it.isNotEmpty() }.map { stem(it) ?: return null }
            return parts.joinToString(" ")
        }
        return null
    }

    private fun sibilantS(base: String): String = when (base.lastOrNull()) {
        's', 'z', 'ʃ', 'ʒ', 'ʧ', 'ʤ' -> "ᵻz"
        'p', 't', 'k', 'f', 'θ' -> "s"
        else -> "z"
    }

    private fun spell(word: String): String =
        word.filter { it.isLetter() }.mapNotNull { lookup(it.uppercase()) ?: lookup(it.toString()) }.joinToString(" ")

    private val ONES = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    private fun numberToWords(n: Long): String {
        if (n < 20) return ONES[n.toInt()]
        if (n < 100) return TENS[(n / 10).toInt()] + if (n % 10 > 0) " " + ONES[(n % 10).toInt()] else ""
        if (n < 1000) return ONES[(n / 100).toInt()] + " hundred" + if (n % 100 > 0) " " + numberToWords(n % 100) else ""
        for ((size, name) in listOf(1_000_000_000L to "billion", 1_000_000L to "million", 1000L to "thousand")) {
            if (n >= size) return numberToWords(n / size) + " $name" + if (n % size > 0) " " + numberToWords(n % size) else ""
        }
        return n.toString()
    }

    private fun normalize(text: String): String {
        var t = Normalizer.normalize(text, Normalizer.Form.NFKC)
        // Years read as pairs: 1813 -> eighteen thirteen
        t = Regex("\\b(1[1-9])(\\d{2})\\b").replace(t) { m ->
            val hi = m.groupValues[1].toLong()
            val lo = m.groupValues[2].toLong()
            numberToWords(hi) + " " + when { lo == 0L -> "hundred"; lo < 10 -> "oh " + numberToWords(lo); else -> numberToWords(lo) }
        }
        t = Regex("(\\d),(\\d{3})").replace(t, "$1$2")
        t = Regex("(\\d+)\\.(\\d+)").replace(t) { m ->
            numberToWords(m.groupValues[1].toLong()) + " point " + m.groupValues[2].map { ONES[it - '0'] }.joinToString(" ")
        }
        t = Regex("\\d+").replace(t) { m -> m.value.toLongOrNull()?.let { numberToWords(it) } ?: m.value }
        return t
            .replace("&", " and ")
            .replace("%", " percent")
            .replace(Regex("[–―]"), "—")
            .replace("...", "…")
            .replace(Regex("[«»„]"), "\"")
            .replace(Regex("[\\[{]"), "(")
            .replace(Regex("[\\]}]"), ")")
    }

    private val TOKEN = Regex("[\\p{L}][\\p{L}'’\\-]*[\\p{L}]|[\\p{L}]")
    private val PUNCT = ";:,.!?—…\"()“”"

    fun phonemize(text: String): String {
        val t = normalize(text)
        val sb = StringBuilder()
        var last = 0
        fun gap(s: String) { for (ch in s) sb.append(if (ch in PUNCT) ch else ' ') }
        for (m in TOKEN.findAll(t)) {
            gap(t.substring(last, m.range.first))
            val w = m.value.replace('’', '\'')
            sb.append(stem(w) ?: spell(w)).append(' ')
            last = m.range.last + 1
        }
        gap(t.substring(last))
        return sb.toString()
            .replace(Regex("\\s+"), " ")
            .replace(Regex(" ([,.;:!?…)”])"), "$1")
            .trim()
    }
}
