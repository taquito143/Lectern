package com.lectern.app.tts

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.Normalizer
import java.util.zip.GZIPInputStream

/**
 * English text -> misaki phonemes for KittenTTS's English model, which learned English from
 * misaki's dictionaries (us_gold + us_silver) with CMUdict for the words misaki lacks.
 * Words in neither are tried without common suffixes, then as compounds of dictionary words
 * ("Netherfield" = nether + field), and only then spelled out. Same rules as english.js.
 */
class EnglishPhonemizer(context: Context) {

    private val dict: HashMap<String, String> = HashMap(200_000)
    private val cmu: HashMap<String, String> = HashMap(130_000)

    init {
        readTsvGz(context, "en_dict.tsv.gz", dict)
        readTsvGz(context, "cmu_misaki_us.tsv.gz", cmu)
    }

    // The APK packager stores *.gz assets decompressed and without ".gz", so the plain
    // name is tried first; the gzip one stays for builds that keep it.
    private fun readTsvGz(context: Context, name: String, into: HashMap<String, String>) {
        val stream = runCatching { context.assets.open(name.removeSuffix(".gz")) }.getOrNull()
            ?: GZIPInputStream(context.assets.open(name))
        stream.use { raw ->
            BufferedReader(InputStreamReader(raw, Charsets.UTF_8), 1 shl 16).forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) into[line.substring(0, tab)] = line.substring(tab + 1)
            }
        }
    }

    private fun lookup(word: String): String? =
        dict[word] ?: dict[word.lowercase()] ?: dict[word.lowercase().replaceFirstChar { it.uppercase() }]

    private fun stem(word: String): String? {
        lookup(word)?.let { return it }
        val w = word.lowercase()
        // possessive / plural
        if (w.endsWith("'s") || w.endsWith("’s")) (lookup(w.dropLast(2)) ?: cmu[w.dropLast(2)])?.let { return it + sibilantS(it) }
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
        return null
    }

    private fun known(word: String): String? = stem(word) ?: cmu[word.lowercase()]

    /**
     * Compounds missing from the dictionaries, as two or three dictionary words of 4+ letters
     * (shorter pieces like "ley" split names wrongly). Joined into one word; only the first
     * part keeps primary stress, as in a spoken compound.
     */
    private fun compound(word: String, depth: Int = 0): String? {
        val w = word.lowercase()
        if (w.length < 8) return null
        for (i in w.length - 4 downTo 4) {
            val head = known(w.substring(0, i)) ?: continue
            val tail = known(w.substring(i)) ?: (if (depth < 1) compound(w.substring(i), depth + 1) else null) ?: continue
            return head.trim() + tail.trim().replace('ˈ', 'ˌ')
        }
        return null
    }

    private fun sayWord(word: String): String {
        known(word)?.let { return it }
        if ('-' in word) return word.split('-').filter { it.isNotEmpty() }.joinToString(" ") { sayWord(it) }
        return compound(word) ?: spell(word)
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

    // Read out, and without the period, which the model would say as a sentence end.
    private val ABBREVIATIONS = mapOf("Mr" to "Mister", "Mrs" to "Missus", "Ms" to "Miz", "Dr" to "Doctor",
        "St" to "Saint", "Jr" to "Junior", "Sr" to "Senior", "Prof" to "Professor")

    private fun normalize(text: String): String {
        var t = Normalizer.normalize(text, Normalizer.Form.NFKC)
        t = Regex("\\b(Mr|Mrs|Ms|Dr|St|Jr|Sr|Prof)\\.(?=\\s)").replace(t) { ABBREVIATIONS.getValue(it.groupValues[1]) }
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
    private val VOWEL_START = Regex("^[ˈˌ]?[AIOWYaeiouæɑɐɒɔəɛɜɪʊʌᵊᵻ]")

    fun phonemize(text: String): String {
        val t = normalize(text)
        val sb = StringBuilder()
        var last = 0
        fun gap(s: String) { for (ch in s) sb.append(if (ch in PUNCT) ch else ' ') }
        val words = TOKEN.findAll(t).toList()
        for ((k, m) in words.withIndex()) {
            gap(t.substring(last, m.range.first))
            val w = m.value.replace('’', '\'')
            // misaki's function-word rules: "a" is ɐ, "the" is ði before a vowel and ðə otherwise.
            val ps = when {
                w == "a" -> "ɐ"
                w.equals("the", ignoreCase = true) ->
                    if (words.getOrNull(k + 1)?.let { VOWEL_START.containsMatchIn(sayWord(it.value)) } == true) "ði" else "ðə"
                else -> sayWord(w)
            }
            sb.append(ps).append(' ')
            last = m.range.last + 1
        }
        gap(t.substring(last))
        return sb.toString()
            .replace(Regex("\\s+"), " ")
            .replace(Regex(" ([,.;:!?…)”])"), "$1")
            .trim()
    }
}
