// parsers.js
// Turns uploaded files into sections of clean paragraphs, and paragraphs into
// speakable units (sentences). No UI and no network code lives here, so it
// can be tested in Node.
//
// Shape returned by parseFile():
//   { title: string, sections: [{ title: string, paras: string[] }] }

const MAX_SECTION_CHARS = 14000; // plain-text files are cut into parts about this size
const PDF_PAGES_PER_PART = 10; // used when a PDF has no usable outline

/* ------------------------------------------------------------------ */
/* Entry point                                                         */
/* ------------------------------------------------------------------ */

export async function parseFile(file, libs, onProgress = () => {}) {
  const ext = (file.name.split('.').pop() || '').toLowerCase();
  const baseTitle = file.name.replace(/\.[^.]+$/, '');
  const buf = await file.arrayBuffer();
  let book;

  switch (ext) {
    case 'pdf':
      book = await parsePdf(buf, await libs.pdfjs(), onProgress);
      break;
    case 'epub':
      book = await parseEpub(buf, await libs.JSZip());
      break;
    case 'docx':
      book = await parseDocx(buf, await libs.JSZip());
      break;
    case 'html':
    case 'htm':
      book = parseHtml(decodeText(buf));
      break;
    case 'txt':
    case 'md':
    case 'markdown':
      book = parseText(decodeText(buf), ext !== 'txt');
      break;
    default:
      throw new Error(`.${ext} files aren't supported. Use PDF, EPUB, DOCX, TXT or HTML.`);
  }

  book.title = (book.title || baseTitle).trim() || 'Untitled Document';
  book.sections = book.sections.filter((s) => s.paras.join('').trim().length > 0);
  if (!book.sections.length) {
    throw new Error('No readable text found in this file.');
  }
  return book;
}

function decodeText(buf) {
  const bytes = new Uint8Array(buf);
  // Strip a UTF-8 BOM, then decode; fall back to Latin-1 if the bytes are not valid UTF-8.
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes).replace(/^\uFEFF/, '');
  } catch {
    return new TextDecoder('windows-1252').decode(bytes);
  }
}

/* ------------------------------------------------------------------ */
/* Text cleanup                                                        */
/* ------------------------------------------------------------------ */

export function cleanInline(s) {
  return s
    .normalize('NFKC')
    .replace(/\u00AD/g, '')
    .replace(/[\u200B-\u200D\uFEFF]/g, '')
    .replace(/\[\d{1,3}\]/g, '')
    .replace(/https?:\/\/\S+/g, 'link')
    .replace(/[\r\n\t]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

const HEADING_RE = /^(chapter|part|book|section|prologue|epilogue|preface|introduction|foreword|appendix)\b[\s\dIVXLCivxlc.:–—-]*.{0,80}$/i;

function looksLikeHeading(line) {
  if (line.length > 90) return false;
  if (/[.!?]$/.test(line) && !/^(chapter|part|book)\b/i.test(line)) return false;
  return HEADING_RE.test(line);
}

// paras: [{ text, heading }]  ->  sections
function groupIntoSections(paras, fallbackPrefix = 'Part') {
  const sections = [];
  let cur = null;
  let size = 0;
  const open = (title) => {
    cur = { title, paras: [] };
    sections.push(cur);
    size = 0;
  };

  for (const p of paras) {
    if (!p.text) continue;
    if (p.heading) {
      // A heading right after another empty heading just renames it.
      if (cur && cur.paras.length === 0) cur.title = p.text;
      else open(p.text);
      cur.paras.push(p.text); // headings are read aloud too
      size += p.text.length;
      continue;
    }
    if (!cur || size > MAX_SECTION_CHARS) open(`${fallbackPrefix} ${sections.length + 1}`);
    cur.paras.push(p.text);
    size += p.text.length;
  }
  return sections;
}

/* ------------------------------------------------------------------ */
/* TXT / Markdown                                                      */
/* ------------------------------------------------------------------ */

export function parseText(raw, markdown = false) {
  let text = raw.replace(/\r\n?/g, '\n').replace(/\t/g, ' ');
  if (markdown) {
    text = text
      .replace(/```[\s\S]*?```/g, ' ')
      .replace(/`([^`]*)`/g, '$1')
      .replace(/!\[[^\]]*\]\([^)]*\)/g, '')
      .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
      .replace(/^\s{0,3}>\s?/gm, '')
      .replace(/^\s*[-*+]\s+/gm, '')
      .replace(/(\*\*|__)(.*?)\1/g, '$2')
      .replace(/(\*|_)(.*?)\1/g, '$2');
  }
  const blocks = text.split(/\n\s*\n/);
  const paras = [];
  for (const block of blocks) {
    const mdHead = markdown && /^\s{0,3}#{1,3}\s+/.test(block);
    const line = cleanInline(block.replace(/^\s{0,3}#{1,6}\s+/, '').replace(/\n/g, ' '));
    if (!line) continue;
    paras.push({ text: line, heading: mdHead || looksLikeHeading(line) });
  }
  return { title: '', sections: groupIntoSections(paras) };
}

/* ------------------------------------------------------------------ */
/* HTML (also used for EPUB pages)                                     */
/* ------------------------------------------------------------------ */

const SKIP_TAGS = new Set(['SCRIPT', 'STYLE', 'NOSCRIPT', 'SVG', 'IFRAME', 'HEAD', 'TEMPLATE', 'BUTTON', 'FORM']);
const BLOCK_TAGS = new Set([
  'P', 'DIV', 'LI', 'BLOCKQUOTE', 'SECTION', 'ARTICLE', 'TR', 'PRE', 'DD', 'DT',
  'FIGCAPTION', 'TABLE', 'UL', 'OL', 'HR', 'ASIDE', 'HEADER', 'FOOTER', 'MAIN', 'BODY',
]);
const HEADING_TAGS = new Set(['H1', 'H2', 'H3', 'H4', 'H5', 'H6']);

export function htmlToParas(root) {
  const out = [];
  let buf = '';
  const flush = (heading = false) => {
    const t = cleanInline(buf);
    buf = '';
    if (t) out.push({ text: t, heading });
  };
  const walk = (node) => {
    if (node.nodeType === 3) {
      buf += node.nodeValue;
      return;
    }
    if (node.nodeType !== 1) return;
    const tag = node.tagName.toUpperCase();
    if (SKIP_TAGS.has(tag)) return;
    if (node.hasAttribute && (node.hasAttribute('hidden') || node.getAttribute('aria-hidden') === 'true')) return;
    if (tag === 'SUP' && (node.textContent || '').trim().length <= 4) return; // footnote refs
    if (tag === 'BR') {
      buf += ' ';
      return;
    }
    if (HEADING_TAGS.has(tag)) {
      flush();
      for (const c of node.childNodes) walk(c);
      flush(true);
      return;
    }
    const block = BLOCK_TAGS.has(tag);
    if (block) flush();
    for (const c of node.childNodes) walk(c);
    if (block) flush();
  };
  walk(root);
  flush();
  return out;
}

export function parseHtml(html) {
  const doc = new DOMParser().parseFromString(html, 'text/html');
  const title = doc.querySelector('title')?.textContent?.trim() || '';
  const paras = htmlToParas(doc.body || doc.documentElement);
  return { title, sections: groupIntoSections(paras) };
}

/* ------------------------------------------------------------------ */
/* EPUB                                                                */
/* ------------------------------------------------------------------ */

const xmlDoc = (s) => new DOMParser().parseFromString(s, 'application/xml');
const dirname = (p) => (p.includes('/') ? p.slice(0, p.lastIndexOf('/') + 1) : '');
const safeDecode = (s) => {
  try {
    return decodeURIComponent(s);
  } catch {
    return s;
  }
};

function resolvePath(base, href) {
  const parts = (dirname(base) + safeDecode(href.split('#')[0])).split('/');
  const out = [];
  for (const p of parts) {
    if (p === '..') out.pop();
    else if (p && p !== '.') out.push(p);
  }
  return out.join('/');
}

async function readZipText(zip, path) {
  const f = zip.file(path) || zip.file(safeDecode(path));
  return f ? f.async('string') : null;
}

export async function parseEpub(buf, JSZip) {
  const zip = await JSZip.loadAsync(buf);
  const containerXml = await readZipText(zip, 'META-INF/container.xml');
  if (!containerXml) throw new Error("This EPUB is missing its container file, so it can't be read.");
  const opfPath = xmlDoc(containerXml).getElementsByTagName('rootfile')[0]?.getAttribute('full-path');
  const opfText = opfPath && (await readZipText(zip, opfPath));
  if (!opfText) throw new Error("This EPUB's package file is missing or unreadable.");
  const opf = xmlDoc(opfText);

  // Encrypted (DRM) books have an encryption.xml that lists encrypted resources.
  const enc = await readZipText(zip, 'META-INF/encryption.xml');
  if (enc && /EncryptedData/.test(enc) && !/obfuscation/i.test(enc)) {
    throw new Error('This EPUB is protected by DRM and cannot be read. Use a DRM-free copy.');
  }

  const title = opf.getElementsByTagNameNS('*', 'title')[0]?.textContent?.trim() || '';

  const manifest = new Map();
  for (const it of opf.getElementsByTagName('item')) {
    manifest.set(it.getAttribute('id'), {
      href: it.getAttribute('href'),
      type: it.getAttribute('media-type') || '',
      props: it.getAttribute('properties') || '',
    });
  }
  const spine = [...opf.getElementsByTagName('itemref')]
    .filter((r) => r.getAttribute('linear') !== 'no')
    .map((r) => manifest.get(r.getAttribute('idref')))
    .filter((m) => m && /x?html|xml/.test(m.type));

  // Chapter titles from the table of contents (EPUB3 nav, falling back to NCX).
  const tocTitles = new Map();
  const navItem = [...manifest.values()].find((m) => m.props.split(/\s+/).includes('nav'));
  if (navItem) {
    const navPath = resolvePath(opfPath, navItem.href);
    const navHtml = await readZipText(zip, navPath);
    if (navHtml) {
      const nav = new DOMParser().parseFromString(navHtml, 'text/html');
      const toc =
        [...nav.querySelectorAll('nav')].find((n) => /toc/.test(n.getAttribute('epub:type') || n.getAttribute('type') || '')) ||
        nav.querySelector('nav');
      for (const a of toc ? toc.querySelectorAll('a[href]') : []) {
        const key = resolvePath(navPath, a.getAttribute('href'));
        if (!tocTitles.has(key)) tocTitles.set(key, cleanInline(a.textContent || ''));
      }
    }
  }
  if (!tocTitles.size) {
    const ncxItem = [...manifest.values()].find((m) => /ncx/.test(m.type));
    if (ncxItem) {
      const ncxPath = resolvePath(opfPath, ncxItem.href);
      const ncxText = await readZipText(zip, ncxPath);
      if (ncxText) {
        for (const np of xmlDoc(ncxText).getElementsByTagName('navPoint')) {
          const label = np.getElementsByTagName('text')[0]?.textContent || '';
          const src = np.getElementsByTagName('content')[0]?.getAttribute('src');
          if (src) {
            const key = resolvePath(ncxPath, src);
            if (!tocTitles.has(key)) tocTitles.set(key, cleanInline(label));
          }
        }
      }
    }
  }

  const sections = [];
  for (const item of spine) {
    const path = resolvePath(opfPath, item.href);
    const html = await readZipText(zip, path);
    if (!html) continue;
    const doc = new DOMParser().parseFromString(html, 'text/html');
    const paras = htmlToParas(doc.body || doc.documentElement);
    const chars = paras.reduce((n, p) => n + p.text.length, 0);
    if (chars < 40) continue; // covers, blank pages, image-only pages

    const tocTitle = tocTitles.get(path);
    const firstHeading = paras.find((p) => p.heading)?.text;
    const sectionTitle = tocTitle || firstHeading;
    const texts = paras.map((p) => p.text);

    // A file with no title of its own is a continuation of the previous one.
    if (!sectionTitle && sections.length) {
      sections[sections.length - 1].paras.push(...texts);
    } else {
      sections.push({ title: sectionTitle || `Section ${sections.length + 1}`, paras: texts });
    }
  }
  return { title, sections };
}

/* ------------------------------------------------------------------ */
/* DOCX                                                                */
/* ------------------------------------------------------------------ */

export async function parseDocx(buf, JSZip) {
  const zip = await JSZip.loadAsync(buf);
  const xml = await readZipText(zip, 'word/document.xml');
  if (!xml) throw new Error("This doesn't look like a Word (.docx) file.");
  const doc = xmlDoc(xml);
  const paras = [];
  for (const p of doc.getElementsByTagNameNS('*', 'p')) {
    let text = '';
    const walk = (n) => {
      for (const c of n.childNodes) {
        if (c.nodeType !== 1) continue;
        const name = c.localName;
        if (name === 't') text += c.textContent;
        else if (name === 'tab' || name === 'br') text += ' ';
        else walk(c);
      }
    };
    walk(p);
    const t = cleanInline(text);
    if (!t) continue;
    const style = p.getElementsByTagNameNS('*', 'pStyle')[0]?.getAttribute('w:val') || '';
    paras.push({ text: t, heading: /^(heading|title)/i.test(style) || looksLikeHeading(t) });
  }
  return { title: '', sections: groupIntoSections(paras) };
}

/* ------------------------------------------------------------------ */
/* PDF                                                                 */
/* ------------------------------------------------------------------ */

const median = (a) => {
  if (!a.length) return 0;
  const s = [...a].sort((x, y) => x - y);
  return s[Math.floor(s.length / 2)];
};

// pdf.js text items -> visual lines ({ text, x, y, h, w })
export function itemsToLines(items) {
  const lines = [];
  let cur = null;
  for (const it of items) {
    if (typeof it.str !== 'string') continue;
    const x = it.transform[4];
    const y = it.transform[5];
    const h = it.height || Math.abs(it.transform[3]) || 10;
    if (!it.str.trim() && !cur) continue;
    const sameLine = cur && Math.abs(y - cur.y) < h * 0.5;
    if (!sameLine) {
      if (cur && cur.text.trim()) lines.push(cur);
      cur = { text: '', x, y, h, w: 0, end: x };
    }
    const gap = x - cur.end;
    if (cur.text && gap > h * 0.15 && !cur.text.endsWith(' ') && !it.str.startsWith(' ')) cur.text += ' ';
    cur.text += it.str;
    cur.end = x + (it.width || 0);
    cur.w = cur.end - cur.x;
    if (it.hasEOL) {
      if (cur.text.trim()) lines.push(cur);
      cur = null;
    }
  }
  if (cur && cur.text.trim()) lines.push(cur);
  return lines.map((l) => ({ ...l, text: l.text.replace(/\s+/g, ' ').trim() })).filter((l) => l.text);
}

const pageNumberLike = (t) => /^\s*(page\s*)?(\d{1,4}|[ivxlc]{1,7})\s*$/i.test(t);
const runKey = (t) => t.replace(/\d+/g, '#').trim().toLowerCase();

// Drop headers, footers and page numbers that repeat from page to page.
export function stripRunningLines(pages) {
  const n = pages.length;
  const counts = new Map();
  const edge = (lines) => [...lines.slice(0, 2), ...lines.slice(-2)];
  if (n >= 4) {
    for (const lines of pages) {
      for (const k of new Set(edge(lines).map((l) => runKey(l.text)))) counts.set(k, (counts.get(k) || 0) + 1);
    }
  }
  const threshold = Math.max(3, Math.ceil(n * 0.3));
  return pages.map((lines) => {
    const edges = new Set(edge(lines));
    return lines.filter((l) => {
      if (!edges.has(l)) return true;
      if (pageNumberLike(l.text)) return false;
      return !(n >= 4 && (counts.get(runKey(l.text)) || 0) >= threshold);
    });
  });
}

// Lines of one page -> paragraphs (arrays of line strings)
export function linesToParagraphs(lines) {
  if (!lines.length) return [];
  const gaps = [];
  for (let i = 1; i < lines.length; i++) {
    const d = Math.abs(lines[i - 1].y - lines[i].y);
    if (d > 0) gaps.push(d);
  }
  const lineGap = median(gaps) || lines[0].h * 1.2;
  const left = median(lines.map((l) => l.x));
  const maxW = Math.max(...lines.map((l) => l.w));

  const paras = [];
  let cur = [];
  lines.forEach((l, i) => {
    const prev = lines[i - 1];
    let newPara = !prev;
    if (prev) {
      const gap = Math.abs(prev.y - l.y);
      const bigGap = gap > lineGap * 1.45;
      const indented = l.x - left > l.h * 0.8;
      const prevShortEnd = prev.w < maxW * 0.6 && /[.!?:"'”’)]$/.test(prev.text);
      newPara = bigGap || indented || prevShortEnd;
    }
    if (newPara && cur.length) {
      paras.push(cur);
      cur = [];
    }
    cur.push(l.text);
  });
  if (cur.length) paras.push(cur);
  return paras;
}

export function joinLines(lines) {
  let out = '';
  for (const raw of lines) {
    const line = raw.trim();
    if (!out) out = line;
    else if (/[a-z]-$/.test(out) && /^[a-z]/.test(line)) out = out.slice(0, -1) + line; // de-hyphenate
    else out += ' ' + line;
  }
  return cleanInline(out);
}

async function outlineSections(pdf) {
  try {
    const outline = await pdf.getOutline();
    if (!outline || outline.length < 2) return null;
    const marks = [];
    for (const it of outline) {
      let dest = it.dest;
      if (typeof dest === 'string') dest = await pdf.getDestination(dest);
      if (!Array.isArray(dest)) continue;
      const page = await pdf.getPageIndex(dest[0]);
      marks.push({ title: cleanInline(it.title || ''), page });
    }
    const uniq = marks.filter((m, i) => m.title && (i === 0 || m.page > marks[i - 1].page));
    return uniq.length >= 2 ? uniq : null;
  } catch {
    return null;
  }
}

export async function parsePdf(buf, pdfjs, onProgress = () => {}) {
  const pdf = await pdfjs.getDocument({ data: new Uint8Array(buf), isEvalSupported: false }).promise;
  const n = pdf.numPages;
  const pages = [];
  for (let p = 1; p <= n; p++) {
    const page = await pdf.getPage(p);
    const content = await page.getTextContent();
    pages.push(itemsToLines(content.items));
    onProgress(p, n);
  }
  const chars = pages.reduce((a, ls) => a + ls.reduce((b, l) => b + l.text.length, 0), 0);
  if (chars < 30 * n) {
    throw new Error(
      "This PDF has almost no selectable text. It's probably a scan. Run it through OCR first, then upload it again."
    );
  }

  const cleaned = stripRunningLines(pages);

  // paragraphs per page; join a paragraph that continues onto the next page
  const pageParas = cleaned.map((lines) => linesToParagraphs(lines).map(joinLines).filter(Boolean));
  for (let i = 1; i < pageParas.length; i++) {
    const prev = pageParas[i - 1];
    const cur = pageParas[i];
    if (prev.length && cur.length && !/[.!?:"'”’)]$/.test(prev[prev.length - 1]) && /^[a-z]/.test(cur[0])) {
      prev[prev.length - 1] += ' ' + cur.shift();
    }
  }

  const textOf = (from, to) => pageParas.slice(from, to).flat();
  const marks = await outlineSections(pdf);
  let sections = [];
  if (marks) {
    if (marks[0].page > 0) sections.push({ title: 'Front matter', paras: textOf(0, marks[0].page) });
    marks.forEach((m, i) => {
      const end = i + 1 < marks.length ? marks[i + 1].page : n;
      sections.push({ title: m.title, paras: textOf(m.page, end) });
    });
  } else {
    // No outline: look for "Chapter 1"-style headings in the text itself.
    const flat = textOf(0, n).map((text) => ({ text, heading: looksLikeHeading(text) }));
    const byHeading = groupIntoSections(flat);
    const titled = byHeading.filter((s) => !/^Part \d+$/.test(s.title)).length;
    if (titled >= 2) {
      sections = byHeading;
    } else {
      for (let s = 0; s < n; s += PDF_PAGES_PER_PART) {
        const e = Math.min(n, s + PDF_PAGES_PER_PART);
        sections.push({ title: `Pages ${s + 1}–${e}`, paras: textOf(s, e) });
      }
    }
  }

  let title = '';
  try {
    title = ((await pdf.getMetadata()).info?.Title || '').trim();
  } catch {}
  return { title, sections };
}

/* ------------------------------------------------------------------ */
/* Speakable units                                                     */
/* ------------------------------------------------------------------ */

let segmenter;
function sentences(text) {
  if (typeof Intl !== 'undefined' && Intl.Segmenter) {
    segmenter ||= (() => {
      try {
        return new Intl.Segmenter('en-u-ss-standard', { granularity: 'sentence' });
      } catch {
        return new Intl.Segmenter('en', { granularity: 'sentence' });
      }
    })();
    return [...segmenter.segment(text)].map((s) => s.segment.trim()).filter(Boolean);
  }
  return text.split(/(?<=[.!?…]["')\]”’]*)\s+(?=["'“‘(\[]?[A-Z0-9])/).map((s) => s.trim()).filter(Boolean);
}

function splitLong(s, max) {
  if (s.length <= max) return [s];
  const out = [];
  let rest = s;
  while (rest.length > max) {
    let cut = -1;
    for (const m of rest.matchAll(/[,;:—–]\s+/g)) {
      const end = m.index + m[0].length;
      if (end <= max) cut = end;
      else break;
    }
    if (cut < max * 0.4) cut = rest.lastIndexOf(' ', max); // no good clause break
    if (cut <= 0) cut = max;
    out.push(rest.slice(0, cut).trim());
    rest = rest.slice(cut).trim();
  }
  if (rest) out.push(rest);
  return out;
}

// paras -> [{ text, para, last }]   (last = final unit of its paragraph)
export function splitIntoUnits(paras, { min = 28, max = 320 } = {}) {
  const units = [];
  paras.forEach((para, pi) => {
    const parts = sentences(para).flatMap((s) => splitLong(s, max));
    const merged = [];
    for (const part of parts) {
      const prev = merged[merged.length - 1];
      if (prev && prev.length < min && prev.length + 1 + part.length <= max) merged[merged.length - 1] = prev + ' ' + part;
      else merged.push(part);
    }
    // a short trailing fragment joins the unit before it
    if (merged.length > 1 && merged[merged.length - 1].length < min) {
      const tail = merged.pop();
      if (merged[merged.length - 1].length + 1 + tail.length <= max) merged[merged.length - 1] += ' ' + tail;
      else merged.push(tail);
    }
    merged.forEach((text, i) => {
      if (/[\p{L}\p{N}]/u.test(text)) units.push({ text, para: pi, last: i === merged.length - 1 });
    });
    // the last speakable unit of the paragraph ends it (even if a trailing fragment was dropped)
    if (units.length && units[units.length - 1].para === pi) units[units.length - 1].last = true;
  });
  return units;
}

/* ------------------------------------------------------------------ */
/* Small helpers shared with the UI                                    */
/* ------------------------------------------------------------------ */

export const WORDS_PER_MINUTE = 160;

export function countWords(paras) {
  let n = 0;
  for (const p of paras) n += p.split(/\s+/).filter(Boolean).length;
  return n;
}

export function formatDuration(seconds) {
  const m = Math.max(1, Math.round(seconds / 60));
  if (m < 60) return `${m} min`;
  const h = Math.floor(m / 60);
  return `${h} h ${String(m % 60).padStart(2, '0')} min`;
}
