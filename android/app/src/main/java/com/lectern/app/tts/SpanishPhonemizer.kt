package com.lectern.app.tts

import java.text.Normalizer

/**
 * Spanish text -> IPA phonemes for Kokoro's Spanish voices.
 * Port of the web app's spanish.js: lenited β ð ɣ between vowels, ˈ before the stressed
 * vowel (eSpeak NG "es" style, which the voices were trained on). The accent decides c/z
 * (s or θ) and ll/y (ʝ, ʃ or ʎ).
 */
object SpanishPhonemizer {

    enum class Accent(val label: String) {
        LATAM("Latinoamérica"),
        RIOPLATENSE("Rioplatense"),
        SPAIN("España"),
    }

    @Volatile var accent: Accent = Accent.LATAM

    private val ceta get() = if (accent == Accent.SPAIN) "θ" else "s"
    private val yeismo get() = when (accent) { Accent.SPAIN -> "ʎ"; Accent.RIOPLATENSE -> "ʃ"; Accent.LATAM -> "ʝ" }
    private val yConsonant get() = if (accent == Accent.RIOPLATENSE) "ʃ" else "ʝ"

    /* ---------------- Numbers and abbreviations ---------------- */

    private val ONES = listOf(
        "cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez",
        "once", "doce", "trece", "catorce", "quince", "dieciséis", "diecisiete", "dieciocho", "diecinueve", "veinte",
        "veintiuno", "veintidós", "veintitrés", "veinticuatro", "veinticinco", "veintiséis", "veintisiete", "veintiocho", "veintinueve",
    )
    private val TENS = listOf("", "", "", "treinta", "cuarenta", "cincuenta", "sesenta", "setenta", "ochenta", "noventa")
    private val HUNDREDS = listOf(
        "", "ciento", "doscientos", "trescientos", "cuatrocientos", "quinientos",
        "seiscientos", "setecientos", "ochocientos", "novecientos",
    )

    private fun below1000(n: Int): String {
        if (n == 100) return "cien"
        val h = n / 100
        val r = n % 100
        val parts = mutableListOf<String>()
        if (h > 0) parts += HUNDREDS[h]
        if (r < 30) {
            if (r > 0 || h == 0) parts += ONES[r]
        } else {
            parts += TENS[r / 10] + if (r % 10 > 0) " y " + ONES[r % 10] else ""
        }
        return parts.joinToString(" ")
    }

    // "uno" shortens to "un" before mil / millón (veintiún mil, un millón)
    private fun apocope(s: String) = s.replace(Regex("veintiuno$"), "veintiún").replace(Regex("uno$"), "un")

    fun numberToWords(n: Long): String {
        if (n < 0) return n.toString()
        if (n >= 1_000_000_000_000L) return n.toString().map { ONES[it - '0'] }.joinToString(" ")
        if (n < 1000) return below1000(n.toInt())
        if (n < 1_000_000) {
            val th = (n / 1000).toInt()
            val head = if (th == 1) "mil" else apocope(below1000(th)) + " mil"
            return if (n % 1000 > 0) head + " " + below1000((n % 1000).toInt()) else head
        }
        val mi = n / 1_000_000
        val head = if (mi == 1L) "un millón" else apocope(numberToWords(mi)) + " millones"
        return if (n % 1_000_000 > 0) head + " " + numberToWords(n % 1_000_000) else head
    }

    private val ROMAN = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
    private fun romanToNumber(s: String): Long {
        var total = 0L
        for (i in s.indices) {
            val v = ROMAN[s[i]] ?: 0
            val next = if (i + 1 < s.length) ROMAN[s[i + 1]] ?: 0 else 0
            total += if (v < next) -v else v
        }
        return total
    }

    private val ABBREVIATIONS = listOf(
        Regex("\\bSr\\.") to "señor", Regex("\\bSra\\.") to "señora", Regex("\\bSrta\\.") to "señorita",
        Regex("\\bDr\\.") to "doctor", Regex("\\bDra\\.") to "doctora", Regex("\\bUd\\.") to "usted", Regex("\\bUds\\.") to "ustedes",
        Regex("\\bD\\.(?= [A-ZÁÉÍÓÚÑ])") to "don", Regex("\\bDña\\.") to "doña",
        Regex("\\bpág\\.", RegexOption.IGNORE_CASE) to "página",
        Regex("\\betc\\.", RegexOption.IGNORE_CASE) to "etcétera",
        Regex("\\bn[º°]\\s?", RegexOption.IGNORE_CASE) to "número ",
        Regex("\\bdepto\\.", RegexOption.IGNORE_CASE) to "departamento",
        Regex("\\bdpto\\.", RegexOption.IGNORE_CASE) to "departamento",
        Regex("\\bAv\\.") to "avenida",
        Regex("\\bTel\\.", RegexOption.IGNORE_CASE) to "teléfono",
        Regex("\\bhs\\.?(?=\\s|$)") to "horas",
        Regex("\\bU\\.\\s?F\\.") to "unidad funcional",
        Regex("\\bs/n\\b", RegexOption.IGNORE_CASE) to "sin número",
        Regex("\\bEE\\.\\s?UU\\.") to "Estados Unidos",
        Regex("@") to " arroba ",
        Regex("%") to " por ciento",
    )

    private val MONTHS = listOf(
        "enero", "febrero", "marzo", "abril", "mayo", "junio",
        "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
    )
    private val ORDINALS = listOf(
        "", "primero", "segundo", "tercero", "cuarto", "quinto",
        "sexto", "séptimo", "octavo", "noveno", "décimo",
    )

    /** Amount like "102.026,50" or "1,500.75" -> (whole, cents). */
    private fun parseAmount(a: String): Pair<Long, Int>? {
        val m = Regex("^(\\d{1,3}(?:[.,]\\d{3})*|\\d+)(?:([.,])(\\d{1,2}))?$").find(a) ?: return null
        val whole = m.groupValues[1].replace(Regex("[.,]"), "").toLongOrNull() ?: return null
        val cents = m.groupValues[3].takeIf { it.isNotEmpty() }?.padEnd(2, '0')?.toInt() ?: 0
        return whole to cents
    }

    private fun money(whole: Long, cents: Int, one: String, many: String): String {
        val w = if (whole == 1L) "un $one" else "${apocope(numberToWords(whole))} $many"
        return if (cents > 0) "$w con ${numberToWords(cents.toLong())} centavos" else w
    }

    private val CURRENCY = Regex("(US\\$|U\\\$S|USD|u\\\$s|\\$|€)\\s?(\\d[\\d.,]*\\d|\\d)")
    private val DATE = Regex("\\b(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4}|\\d{2})\\b")
    private val TIME = Regex("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b")
    private val ORDINAL = Regex("\\b(\\d{1,2})\\s?([º°ª])")

    private fun expandSpecial(text: String): String {
        var t = text
        // Money: $ 102.026,50 -> ciento dos mil veintiséis pesos con cincuenta centavos
        t = CURRENCY.replace(t) { m ->
            val (whole, cents) = parseAmount(m.groupValues[2]) ?: return@replace m.value
            when (m.groupValues[1]) {
                "€" -> money(whole, cents, "euro", "euros")
                "$" -> money(whole, cents, "peso", "pesos")
                else -> money(whole, cents, "dólar", "dólares")
            }
        }
        // Dates: 11/09/2026 -> once de septiembre de dos mil veintiséis
        t = DATE.replace(t) { m ->
            val d = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt()
            var y = m.groupValues[3].toInt()
            if (m.groupValues[3].length == 2) y += 2000
            if (d in 1..31 && mo in 1..12) "${if (d == 1) "primero" else numberToWords(d.toLong())} de ${MONTHS[mo - 1]} de ${numberToWords(y.toLong())}"
            else m.value
        }
        // Times: 10:30 -> diez y treinta
        t = TIME.replace(t) { m ->
            val h = m.groupValues[1].toLong()
            val mi = m.groupValues[2].toLong()
            if (mi == 0L) numberToWords(h) else "${numberToWords(h)} y ${numberToWords(mi)}"
        }
        // Ordinals: 3º piso, 1ª vez
        t = ORDINAL.replace(t) { m ->
            val n = m.groupValues[1].toInt()
            val w = ORDINALS.getOrNull(n)?.takeIf { it.isNotEmpty() } ?: return@replace numberToWords(n.toLong())
            if (m.groupValues[2] == "ª") w.dropLast(1) + "a" else w
        }
        return t
    }

    private fun digits(b: String) = b.map { ONES[it - '0'] }.joinToString(" ")

    private fun normalize(text: String): String {
        var t = Normalizer.normalize(text, Normalizer.Form.NFC)
        for ((re, word) in ABBREVIATIONS) t = re.replace(t, word)
        t = expandSpecial(t)
        // Roman numerals after words that usually take them: "capítulo IV", "siglo XX"
        t = Regex("\\b(cap[ií]tulo|parte|libro|tomo|siglo|acto|escena)\\s+([IVXLCDM]+)\\b", RegexOption.IGNORE_CASE)
            .replace(t) { m -> "${m.groupValues[1]} ${numberToWords(romanToNumber(m.groupValues[2].uppercase()))}" }
        t = Regex("\\b\\d{1,3}(?:\\.\\d{3})+\\b").replace(t) { it.value.replace(".", "") } // 1.000.000
        t = Regex("(\\d+)[,.](\\d+)").replace(t) { m ->
            val a = m.groupValues[1].toLongOrNull() ?: 0
            val b = m.groupValues[2]
            "${numberToWords(a)} coma ${if (b.length <= 2) numberToWords(b.toLong()) else digits(b)}"
        }
        t = Regex("\\d+").replace(t) { m -> m.value.toLongOrNull()?.let { numberToWords(it) } ?: digits(m.value) }
        return t
            .replace(Regex("[«»“”„]"), "\"")
            .replace(Regex("[‘’`´']"), "")
            .replace(Regex("[¿¡]"), "")
            .replace(Regex("[–―]"), "—")
            .replace("...", "…")
            .replace(Regex("[\\[{]"), "(")
            .replace(Regex("[\\]}]"), ")")
            .replace("&", " y ")
    }

    /* ---------------- Words ---------------- */

    // Function words that carry no stress of their own (stressed forms carry an accent: él, tú, qué...).
    private val UNSTRESSED = ("a al con de del e el en la las le les lo los me mi mis nos os por que se si sin " +
        "su sus te tu tus y o u ni un como cuando donde mientras pero para porque aunque cual cuyo cuya").split(" ").toSet()

    private val ACCENTED = mapOf('á' to "a", 'é' to "e", 'í' to "i", 'ó' to "o", 'ú' to "u")
    private fun isVowelLetter(c: Char?) = c != null && c in "aeiouáéíóúü"
    private fun front(c: Char?) = c != null && c in "eiéí"

    private class Seg(
        val p: String,
        val v: Boolean = false,
        val acc: Boolean = false,
        val weak: Boolean = false,
        val final: Boolean = false,
    ) { var glide = false }

    private val MEXICO = Regex("^(m[eé]xic|oaxac|texa)")

    private fun segment(word: String): List<Seg> {
        val w = word.lowercase()
        val out = mutableListOf<Seg>()
        var i = 0
        while (i < w.length) {
            val c = w[i]
            val n = w.getOrNull(i + 1)
            when {
                c in ACCENTED -> out += Seg(ACCENTED.getValue(c), v = true, acc = true)
                c in "aeo" -> out += Seg(c.toString(), v = true)
                c == 'i' || c == 'u' || c == 'ü' -> out += Seg(if (c == 'i') "i" else "u", v = true, weak = true)
                c == 'y' -> when {
                    // vowel when alone or at the end after a vowel (y, hoy, rey, muy); consonant otherwise
                    w.length == 1 -> out += Seg("i", v = true, weak = true)
                    i == w.length - 1 && isVowelLetter(w.getOrNull(i - 1)) -> out += Seg("i", v = true, weak = true, final = true)
                    else -> out += Seg(yConsonant)
                }
                c == 'c' -> if (n == 'h') { out += Seg("ʧ"); i++ } else out += Seg(if (front(n)) ceta else "k")
                c == 'q' -> { out += Seg("k"); if (n == 'u') i++ }
                c == 'g' -> when {
                    n == 'u' && front(w.getOrNull(i + 2)) -> { out += Seg("ɡ"); i++ }
                    else -> out += Seg(if (front(n)) "x" else "ɡ")
                }
                c == 'l' -> if (n == 'l') { out += Seg(yeismo); i++ } else out += Seg("l")
                c == 'r' -> if (n == 'r') { out += Seg("r"); i++ }
                    else out += Seg(if (i == 0 || w[i - 1] in "lns") "r" else "ɾ")
                // México, Oaxaca, Texas keep the old /x/ spelling
                c == 'x' -> if (MEXICO.containsMatchIn(w) && i <= 3) out += Seg("x") else { out += Seg("k"); out += Seg("s") }
                c == 'h' -> {}
                c == 'j' -> out += Seg("x")
                c == 'z' -> out += Seg(ceta)
                c == 'v' || c == 'b' -> out += Seg("b")
                c == 'ñ' -> out += Seg("ɲ")
                c == 'w' -> out += Seg("w")
                c == 'k' -> out += Seg("k")
                c == 'ç' -> out += Seg("s")
                c in "abcdefghijklmnopqrstuvwxyz" -> out += Seg(c.toString())
                c in "àâäèêëîïôöùûœæ" -> out += Seg(Normalizer.normalize(c.toString(), Normalizer.Form.NFD).take(1))
            }
            i++
        }
        return out
    }

    private val PENULT_END = Regex("[aeiouns]$")

    private fun wordToPhonemes(word: String): String {
        val segs = segment(word)
        if (segs.isEmpty()) return ""

        // Vowel runs: unaccented i/u next to another vowel become glides (j, w), except
        // in "iu"/"ui" where the second one keeps the syllable (ciudad, ruido).
        var i = 0
        while (i < segs.size) {
            if (!segs[i].v) { i++; continue }
            var j = i
            while (j + 1 < segs.size && segs[j + 1].v) j++
            val run = segs.subList(i, j + 1)
            if (run.size > 1) {
                run.forEachIndexed { k, s ->
                    if (!s.weak) return@forEachIndexed
                    val prev = run.getOrNull(k - 1)
                    val next = run.getOrNull(k + 1)
                    val strongNeighbour = (prev != null && !prev.weak) || (next != null && !next.weak)
                    if (strongNeighbour || (next != null && next.weak && !next.final) || s.final) s.glide = true
                }
                if (run.all { it.glide }) run.last().glide = false
            }
            i = j + 1
        }
        val nuclei = segs.indices.filter { segs[it].v && !segs[it].glide }

        // Stress: written accent wins; otherwise penultimate when the word ends in a vowel, n or s.
        val lower = word.lowercase()
        var stressed = segs.indexOfFirst { it.acc }
        if (stressed < 0 && nuclei.isNotEmpty() && lower !in UNSTRESSED) {
            val penult = PENULT_END.containsMatchIn(lower) && nuclei.size > 1
            stressed = nuclei[nuclei.size - if (penult) 2 else 1]
        }

        val sb = StringBuilder()
        segs.forEachIndexed { k, s ->
            if (k == stressed) sb.append('ˈ')
            sb.append(if (s.glide) (if (s.p == "i") "j" else "w") else s.p)
        }
        return sb.toString()
    }

    /* ---------------- Phrases ---------------- */

    private const val PUNCT = ";:,.!?—…\"()"

    // b d g are stops after a pause or a nasal (and d after l); between other sounds they soften.
    private fun lenite(s: String): String {
        val len = mapOf('b' to 'β', 'd' to 'ð', 'ɡ' to 'ɣ')
        var prev: Char? = null
        val out = StringBuilder()
        for (ch in s) {
            if (ch in PUNCT) { prev = null; out.append(ch); continue }
            if (ch == ' ' || ch == 'ˈ') { out.append(ch); continue }
            if (ch in len && prev != null && prev !in "mnɲŋ" && !(ch == 'd' && prev == 'l')) out.append(len.getValue(ch))
            else out.append(ch)
            prev = ch
        }
        return out.toString()
    }

    private val LETTERS = Regex("\\p{L}+")

    fun phonemize(text: String): String {
        val t = normalize(text)
        val sb = StringBuilder()
        var last = 0
        for (m in LETTERS.findAll(t)) {
            for (ch in t.substring(last, m.range.first)) sb.append(if (ch in PUNCT) ch else ' ')
            sb.append(wordToPhonemes(m.value))
            last = m.range.last + 1
        }
        for (ch in t.substring(last)) sb.append(if (ch in PUNCT) ch else ' ')
        val out = sb.toString()
            .replace(Regex("\\s+"), " ")
            .replace(Regex(" ([,.;:!?…)])"), "$1")
            .replace(Regex("n(?= ?ˈ?[pbm])"), "m") // un beso -> um beso
            .replace(Regex("n(?= ?ˈ?[kɡx])"), "ŋ") // tengo, un gato
            .trim()
        return lenite(out)
    }

    /* ---------------- Rough language check ---------------- */

    private val ES_WORDS = ("el la los las que de y en un una es por con para no se del al lo como más pero sus le ya o fue este ha sí porque esta son entre cuando muy sin sobre también me hasta hay donde quien desde todo nos durante todos uno les ni contra otros ese eso ante ellos e esto mí antes algunos qué unos yo otro otras otra él tanto esa estos mucho quienes nada muchos cual poco ella estar estas algunas algo nosotros mi mis tú te ti tu tus ellas nosotras vosotros os mío mía tuyo suyo era había estaba dijo").split(" ").toSet()
    private val EN_WORDS = ("the of and to in is was that he it for on with as his i be at by had not but from or have they which you she her an were are this all their there been one so if we when who would said my me him what no could them into out more up").split(" ").toSet()

    fun looksSpanish(text: String): Boolean {
        var es = 0
        var en = 0
        for (m in LETTERS.findAll(text.lowercase())) {
            if (m.value in ES_WORDS) es++
            if (m.value in EN_WORDS) en++
        }
        if (Regex("[ñ¿¡áéíóú]", RegexOption.IGNORE_CASE).containsMatchIn(text)) es += 2
        return es > en * 1.5 && es >= 3
    }
}
