package com.lectern.app.text

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale
import java.util.zip.ZipInputStream

class ParseException(message: String) : Exception(message)

/**
 * Files -> sections of clean paragraphs, and paragraphs -> speakable sentences.
 * Port of the web app's parsers.js.
 */
object Parsers {

    private const val MAX_SECTION_CHARS = 14_000
    private const val PDF_PAGES_PER_PART = 10

    fun parse(context: Context, name: String, bytes: ByteArray, onProgress: (Int, Int) -> Unit = { _, _ -> }): Book {
        val ext = name.substringAfterLast('.', "").lowercase()
        val baseTitle = name.substringBeforeLast('.')
        val book = when (ext) {
            "pdf" -> parsePdf(context, bytes, onProgress)
            "epub" -> parseEpub(bytes)
            "docx" -> parseDocx(bytes)
            "html", "htm", "xhtml" -> parseHtml(decodeText(bytes))
            "txt", "md", "markdown", "" -> parseText(decodeText(bytes), ext == "md" || ext == "markdown")
            else -> throw ParseException(".$ext files aren't supported. Use PDF, EPUB, DOCX, TXT or HTML.")
        }
        val sections = book.sections.filter { s -> s.paras.joinToString("").isNotBlank() }
        if (sections.isEmpty()) throw ParseException("No readable text found in this file.")
        return Book(book.title.trim().ifEmpty { baseTitle.trim() }.ifEmpty { "Untitled document" }, sections)
    }

    private fun decodeText(bytes: ByteArray): String {
        val s = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
        return s.removePrefix("﻿")
    }

    /* ---------------- Text cleanup ---------------- */

    fun cleanInline(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC)
        .replace("­", "")
        .replace(Regex("[​-‍﻿]"), "")
        .replace(Regex("\\[\\d{1,3}]"), "")
        .replace(Regex("https?://\\S+"), "link")
        .replace(Regex("[\r\n\t]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private val HEADING_RE = Regex(
        "^(chapter|part|book|section|prologue|epilogue|preface|introduction|foreword|appendix|" +
            "cap[ií]tulo|parte|libro|secci[oó]n|pr[oó]logo|ep[ií]logo|prefacio|introducci[oó]n|ap[eé]ndice)\\b[\\s\\dIVXLCivxlc.:–—-]*.{0,80}$",
        RegexOption.IGNORE_CASE,
    )
    private val STRONG_HEADING = Regex("^(chapter|part|book|cap[ií]tulo|parte|libro)\\b", RegexOption.IGNORE_CASE)

    private fun looksLikeHeading(line: String): Boolean {
        if (line.length > 90) return false
        if (Regex("[.!?]$").containsMatchIn(line) && !STRONG_HEADING.containsMatchIn(line)) return false
        return HEADING_RE.containsMatchIn(line)
    }

    private data class Para(val text: String, val heading: Boolean)

    private fun groupIntoSections(paras: List<Para>, prefix: String = "Part"): List<Section> {
        val titles = mutableListOf<String>()
        val bodies = mutableListOf<MutableList<String>>()
        var size = 0
        fun open(t: String) { titles.add(t); bodies.add(mutableListOf()); size = 0 }
        for (p in paras) {
            if (p.text.isEmpty()) continue
            if (p.heading) {
                // A heading right after another empty heading just renames it.
                if (bodies.isNotEmpty() && bodies.last().isEmpty()) titles[titles.lastIndex] = p.text else open(p.text)
                bodies.last() += p.text // headings are read aloud too
                size += p.text.length
                continue
            }
            if (bodies.isEmpty() || size > MAX_SECTION_CHARS) open("$prefix ${titles.size + 1}")
            bodies.last() += p.text
            size += p.text.length
        }
        return titles.indices.map { Section(titles[it], bodies[it]) }
    }

    /* ---------------- TXT / Markdown ---------------- */

    fun parseText(raw: String, markdown: Boolean = false): Book {
        var text = raw.replace(Regex("\r\n?"), "\n").replace('\t', ' ')
        if (markdown) {
            text = text
                .replace(Regex("```[\\s\\S]*?```"), " ")
                .replace(Regex("`([^`]*)`"), "$1")
                .replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "")
                .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
                .replace(Regex("(?m)^\\s{0,3}>\\s?"), "")
                .replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
                .replace(Regex("(\\*\\*|__)(.*?)\\1"), "$2")
                .replace(Regex("([*_])(.*?)\\1"), "$2")
        }
        val paras = mutableListOf<Para>()
        for (block in text.split(Regex("\n\\s*\n"))) {
            val mdHead = markdown && Regex("^\\s{0,3}#{1,3}\\s+").containsMatchIn(block)
            val line = cleanInline(block.replace(Regex("^\\s{0,3}#{1,6}\\s+"), "").replace('\n', ' '))
            if (line.isEmpty()) continue
            paras += Para(line, mdHead || looksLikeHeading(line))
        }
        return Book("", groupIntoSections(paras))
    }

    /* ---------------- HTML (also EPUB pages) ---------------- */

    private val SKIP_TAGS = setOf("script", "style", "noscript", "svg", "iframe", "head", "template", "button", "form")
    private val BLOCK_TAGS = setOf(
        "p", "div", "li", "blockquote", "section", "article", "tr", "pre", "dd", "dt",
        "figcaption", "table", "ul", "ol", "hr", "aside", "header", "footer", "main", "body",
    )
    private val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")

    private fun htmlToParas(root: Element): List<Para> {
        val out = mutableListOf<Para>()
        val buf = StringBuilder()
        fun flush(heading: Boolean = false) {
            val t = cleanInline(buf.toString())
            buf.setLength(0)
            if (t.isNotEmpty()) out += Para(t, heading)
        }
        fun walk(node: Node) {
            if (node is TextNode) { buf.append(node.wholeText); return }
            if (node !is Element) return
            val tag = node.normalName().substringAfter(':')
            if (tag in SKIP_TAGS) return
            if (node.hasAttr("hidden") || node.attr("aria-hidden") == "true") return
            if (tag == "sup" && node.text().trim().length <= 4) return // footnote refs
            if (tag == "br") { buf.append(' '); return }
            if (tag in HEADING_TAGS) {
                flush()
                node.childNodes().forEach { walk(it) }
                flush(true)
                return
            }
            val block = tag in BLOCK_TAGS
            if (block) flush()
            node.childNodes().forEach { walk(it) }
            if (block) flush()
        }
        walk(root)
        flush()
        return out
    }

    fun parseHtml(html: String): Book {
        val doc = Jsoup.parse(html)
        return Book(doc.title().trim(), groupIntoSections(htmlToParas(doc.body() ?: doc)))
    }

    /* ---------------- EPUB ---------------- */

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (!e.isDirectory) out[e.name] = zin.readBytes()
            }
        }
        return out
    }

    private fun safeDecode(s: String) = try { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { s }
    private fun dirname(p: String) = if ('/' in p) p.substring(0, p.lastIndexOf('/') + 1) else ""

    private fun resolvePath(base: String, href: String): String {
        val parts = (dirname(base) + safeDecode(href.substringBefore('#'))).split('/')
        val out = ArrayDeque<String>()
        for (p in parts) {
            if (p == "..") out.removeLastOrNull() else if (p.isNotEmpty() && p != ".") out.addLast(p)
        }
        return out.joinToString("/")
    }

    private fun Map<String, ByteArray>.text(path: String): String? =
        (this[path] ?: this[safeDecode(path)])?.let { decodeText(it) }

    private fun xml(s: String): Document = Jsoup.parse(s, "", Parser.xmlParser())

    /** Elements by local name, ignoring namespace prefixes (dc:title, opf:item...). */
    private fun Element.byLocal(name: String): List<Element> =
        getAllElements().filter { it.tagName().substringAfter(':').equals(name, ignoreCase = true) }

    fun parseEpub(bytes: ByteArray): Book {
        val zip = unzip(bytes)
        val container = zip.text("META-INF/container.xml")
            ?: throw ParseException("This EPUB is missing its container file, so it can't be read.")
        val opfPath = xml(container).byLocal("rootfile").firstOrNull()?.attr("full-path")
        val opfText = opfPath?.let { zip.text(it) }
            ?: throw ParseException("This EPUB's package file is missing or unreadable.")
        val opf = xml(opfText)

        val enc = zip.text("META-INF/encryption.xml")
        if (enc != null && "EncryptedData" in enc && !enc.contains("obfuscation", ignoreCase = true)) {
            throw ParseException("This EPUB is protected by DRM and cannot be read. Use a DRM-free copy.")
        }

        val title = opf.byLocal("title").firstOrNull()?.text()?.trim().orEmpty()

        data class Item(val href: String, val type: String, val props: String)
        val manifest = LinkedHashMap<String, Item>()
        for (it in opf.byLocal("item")) manifest[it.attr("id")] = Item(it.attr("href"), it.attr("media-type"), it.attr("properties"))
        val spine = opf.byLocal("itemref")
            .filter { it.attr("linear") != "no" }
            .mapNotNull { manifest[it.attr("idref")] }
            .filter { Regex("x?html|xml").containsMatchIn(it.type) }

        // Chapter titles from the table of contents (EPUB3 nav, falling back to NCX).
        val tocTitles = HashMap<String, String>()
        manifest.values.firstOrNull { "nav" in it.props.split(Regex("\\s+")) }?.let { navItem ->
            val navPath = resolvePath(opfPath, navItem.href)
            zip.text(navPath)?.let { navHtml ->
                val nav = Jsoup.parse(navHtml)
                val navs = nav.getElementsByTag("nav")
                val toc = navs.firstOrNull { Regex("toc").containsMatchIn(it.attr("epub:type") + it.attr("type")) } ?: navs.firstOrNull()
                toc?.select("a[href]")?.forEach { a ->
                    val key = resolvePath(navPath, a.attr("href"))
                    tocTitles.putIfAbsent(key, cleanInline(a.text()))
                }
            }
        }
        if (tocTitles.isEmpty()) {
            manifest.values.firstOrNull { "ncx" in it.type }?.let { ncxItem ->
                val ncxPath = resolvePath(opfPath, ncxItem.href)
                zip.text(ncxPath)?.let { ncx ->
                    for (np in xml(ncx).byLocal("navPoint")) {
                        val label = np.byLocal("text").firstOrNull()?.text().orEmpty()
                        val src = np.byLocal("content").firstOrNull()?.attr("src")
                        if (!src.isNullOrEmpty()) tocTitles.putIfAbsent(resolvePath(ncxPath, src), cleanInline(label))
                    }
                }
            }
        }

        val titles = mutableListOf<String>()
        val bodies = mutableListOf<MutableList<String>>()
        for (item in spine) {
            val path = resolvePath(opfPath, item.href)
            val html = zip.text(path) ?: continue
            val doc = Jsoup.parse(html)
            val paras = htmlToParas(doc.body() ?: doc)
            if (paras.sumOf { it.text.length } < 40) continue // covers, blank pages, image-only pages
            val sectionTitle = tocTitles[path]?.takeIf { it.isNotEmpty() } ?: paras.firstOrNull { it.heading }?.text
            val texts = paras.map { it.text }
            // A file with no title of its own is a continuation of the previous one.
            if (sectionTitle == null && bodies.isNotEmpty()) bodies.last() += texts
            else { titles += sectionTitle ?: "Section ${titles.size + 1}"; bodies.add(texts.toMutableList()) }
        }
        return Book(title, titles.indices.map { Section(titles[it], bodies[it]) })
    }

    /* ---------------- DOCX ---------------- */

    fun parseDocx(bytes: ByteArray): Book {
        val zip = unzip(bytes)
        val xmlText = zip.text("word/document.xml") ?: throw ParseException("This doesn't look like a Word (.docx) file.")
        val doc = xml(xmlText)
        val paras = mutableListOf<Para>()
        for (p in doc.getElementsByTag("w:p")) {
            val sb = StringBuilder()
            fun walk(e: Element) {
                for (c in e.children()) {
                    when (c.tagName()) {
                        "w:t" -> sb.append(c.wholeText())
                        "w:tab", "w:br" -> sb.append(' ')
                        "w:p" -> {} // nested paragraphs are visited on their own
                        else -> walk(c)
                    }
                }
            }
            walk(p)
            val t = cleanInline(sb.toString())
            if (t.isEmpty()) continue
            val style = p.getElementsByTag("w:pStyle").firstOrNull()?.attr("w:val").orEmpty()
            paras += Para(t, Regex("^(heading|title)", RegexOption.IGNORE_CASE).containsMatchIn(style) || looksLikeHeading(t))
        }
        return Book("", groupIntoSections(paras))
    }

    /* ---------------- PDF ---------------- */

    private val END_PUNCT = Regex("[.!?:\"'”’)]$")
    private val PAGE_NUMBER = Regex("^\\s*(page\\s*|p[aá]gina\\s*)?(\\d{1,4}|[ivxlc]{1,7})\\s*$", RegexOption.IGNORE_CASE)
    private fun runKey(t: String) = t.replace(Regex("\\d+"), "#").trim().lowercase()

    private fun joinLines(lines: List<String>): String {
        val out = StringBuilder()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (out.isEmpty()) out.append(line)
            else if (Regex("\\p{Ll}-$").containsMatchIn(out) && Regex("^\\p{Ll}").containsMatchIn(line)) {
                out.setLength(out.length - 1); out.append(line) // de-hyphenate
            } else out.append(' ').append(line)
        }
        return cleanInline(out.toString())
    }

    fun parsePdf(context: Context, bytes: ByteArray, onProgress: (Int, Int) -> Unit): Book {
        PDFBoxResourceLoader.init(context.applicationContext)
        val doc = try {
            PDDocument.load(bytes)
        } catch (e: Exception) {
            throw ParseException("This PDF couldn't be opened. It may be damaged or password-protected.")
        }
        doc.use { pdf ->
            val n = pdf.numberOfPages
            val stripper = PDFTextStripper().apply {
                sortByPosition = true
                paragraphStart = ""
                paragraphEnd = " "
                lineSeparator = "\n"
            }
            // Each page: paragraphs, each a list of lines
            val pages = ArrayList<List<List<String>>>(n)
            for (p in 1..n) {
                stripper.startPage = p
                stripper.endPage = p
                val text = stripper.getText(pdf)
                pages += text.split(' ').map { chunk -> chunk.split('\n').map { it.trim() }.filter { it.isNotEmpty() } }
                    .filter { it.isNotEmpty() }
                onProgress(p, n)
            }
            val chars = pages.sumOf { pg -> pg.sumOf { para -> para.sumOf { it.length } } }
            if (chars < 30 * n) {
                throw ParseException("This PDF has almost no selectable text. It's probably a scan. Run it through OCR first, then open it again.")
            }

            // Drop headers, footers and page numbers that repeat from page to page.
            fun edgeLines(pg: List<List<String>>): List<String> {
                val flat = pg.flatten()
                return (flat.take(2) + flat.takeLast(2))
            }
            val counts = HashMap<String, Int>()
            if (n >= 4) pages.forEach { pg -> edgeLines(pg).map(::runKey).toSet().forEach { counts[it] = (counts[it] ?: 0) + 1 } }
            val threshold = maxOf(3, (n * 0.3).toInt() + 1)
            val cleaned = pages.map { pg ->
                val flat = pg.flatten()
                val edgeIdx = (flat.indices.take(2) + flat.indices.toList().takeLast(2)).toSet()
                var k = 0
                pg.map { para ->
                    para.filter { line ->
                        val idx = k++
                        if (idx !in edgeIdx) true
                        else if (PAGE_NUMBER.matches(line)) false
                        else !(n >= 4 && (counts[runKey(line)] ?: 0) >= threshold)
                    }
                }.map(::joinLines).filter { it.isNotEmpty() }.toMutableList()
            }
            // Join a paragraph that continues onto the next page.
            for (i in 1 until cleaned.size) {
                val prev = cleaned[i - 1]
                val cur = cleaned[i]
                if (prev.isNotEmpty() && cur.isNotEmpty() && !END_PUNCT.containsMatchIn(prev.last()) && Regex("^\\p{Ll}").containsMatchIn(cur[0])) {
                    prev[prev.lastIndex] = prev.last() + " " + cur.removeAt(0)
                }
            }
            fun textOf(from: Int, to: Int) = cleaned.subList(from.coerceIn(0, n), to.coerceIn(0, n)).flatten()

            val marks = outline(pdf)
            val sections = mutableListOf<Section>()
            if (marks != null) {
                if (marks[0].second > 0) sections += Section("Front matter", textOf(0, marks[0].second))
                marks.forEachIndexed { i, (title, page) ->
                    val end = if (i + 1 < marks.size) marks[i + 1].second else n
                    sections += Section(title, textOf(page, end))
                }
            } else {
                val flat = textOf(0, n).map { Para(it, looksLikeHeading(it)) }
                val byHeading = groupIntoSections(flat)
                if (byHeading.count { !Regex("^Part \\d+$").matches(it.title) } >= 2) sections += byHeading
                else {
                    var s = 0
                    while (s < n) {
                        val e = minOf(n, s + PDF_PAGES_PER_PART)
                        sections += Section("Pages ${s + 1}–$e", textOf(s, e))
                        s += PDF_PAGES_PER_PART
                    }
                }
            }
            val title = runCatching { pdf.documentInformation?.title?.trim() }.getOrNull().orEmpty()
            return Book(title, sections)
        }
    }

    private fun outline(pdf: PDDocument): List<Pair<String, Int>>? = try {
        val root = pdf.documentCatalog.documentOutline
        val marks = mutableListOf<Pair<String, Int>>()
        var item: PDOutlineItem? = root?.firstChild
        while (item != null) {
            val page = item.findDestinationPage(pdf)
            if (page != null) marks += cleanInline(item.title.orEmpty()) to pdf.pages.indexOf(page)
            item = item.nextSibling
        }
        val uniq = marks.filterIndexed { i, m -> m.first.isNotEmpty() && m.second >= 0 && (i == 0 || m.second > marks[i - 1].second) }
        if (uniq.size >= 2) uniq else null
    } catch (e: Exception) {
        null
    }

    /* ---------------- Speakable units ---------------- */

    private fun sentences(text: String, locale: Locale): List<String> {
        val it = BreakIterator.getSentenceInstance(locale)
        it.setText(text)
        val out = mutableListOf<String>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            text.substring(start, end).trim().takeIf { s -> s.isNotEmpty() }?.let { s -> out += s }
            start = end
            end = it.next()
        }
        return out
    }

    private val CLAUSE = Regex("[,;:—–]\\s+")

    private fun splitLong(s: String, max: Int): List<String> {
        if (s.length <= max) return listOf(s)
        val out = mutableListOf<String>()
        var rest = s
        while (rest.length > max) {
            var cut = -1
            for (m in CLAUSE.findAll(rest)) {
                val end = m.range.last + 1
                if (end <= max) cut = end else break
            }
            if (cut < max * 0.4) cut = rest.lastIndexOf(' ', max) // no good clause break
            if (cut <= 0) cut = max
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }

    private val SPEAKABLE = Regex("[\\p{L}\\p{N}]")

    fun splitIntoSentences(book: Book, locale: Locale, min: Int = 28, max: Int = 320): List<Sentence> {
        val units = mutableListOf<Sentence>()
        book.sections.forEachIndexed { si, section ->
            section.paras.forEachIndexed { pi, para ->
                val parts = sentences(para, locale).flatMap { splitLong(it, max) }
                val merged = mutableListOf<String>()
                for (part in parts) {
                    val prev = merged.lastOrNull()
                    if (prev != null && prev.length < min && prev.length + 1 + part.length <= max) merged[merged.lastIndex] = "$prev $part"
                    else merged += part
                }
                // a short trailing fragment joins the unit before it
                if (merged.size > 1 && merged.last().length < min) {
                    val tail = merged.removeAt(merged.lastIndex)
                    if (merged.last().length + 1 + tail.length <= max) merged[merged.lastIndex] = merged.last() + " " + tail
                    else merged += tail
                }
                val before = units.size
                merged.forEach { t -> if (SPEAKABLE.containsMatchIn(t)) units += Sentence(t, si, pi, false) }
                if (units.size > before) units[units.lastIndex] = units.last().copy(last = true)
            }
        }
        return units
    }

    const val WORDS_PER_MINUTE = 160

    fun countWords(paras: List<String>) = paras.sumOf { p -> p.split(Regex("\\s+")).count { it.isNotEmpty() } }

    fun formatDuration(seconds: Double): String {
        val m = maxOf(1, Math.round(seconds / 60).toInt())
        if (m < 60) return "$m min"
        return "${m / 60} h ${(m % 60).toString().padStart(2, '0')} min"
    }
}
