# Lectern

Upload a PDF, EPUB, DOCX, TXT or HTML file and listen to it read aloud. Everything
runs in the browser: text extraction and speech (Kokoro, 82M parameters, via
`kokoro-js`). There is no server and nothing is uploaded.

## Files

| File | What it does |
| --- | --- |
| `index.html` | Page, styles and layout |
| `app.js` | UI, model loading, audio playback queue, saving audio |
| `parsers.js` | PDF / EPUB / DOCX / TXT / HTML to sections, then to sentences |

## Run it on your PC

The page uses ES modules, so it must be served over http (double-clicking
`index.html` will not work). From this folder, either:

```
npx serve .
# or
python -m http.server 8000
```

then open the address it prints (for example http://localhost:8000). Use a
current Chrome, Edge, Firefox or Safari. An internet connection is needed the
first time (libraries from jsDelivr, voice model from Hugging Face).

## Put it online

It is a static site: drag this folder into Netlify Drop, or use Cloudflare
Pages, GitHub Pages or Vercel. No build step, no environment variables.

## How it works

1. The file is parsed in the browser. PDFs lose repeated headers, footers and
   page numbers; words hyphenated across line breaks are rejoined. Chapters come
   from the EPUB table of contents, the PDF outline, or "Chapter N" headings,
   with a fallback to blocks of 10 pages.
2. Text is split into sentences. Click any sentence to start reading from it.
3. On the first Play, the voice model downloads and is cached by the browser:
   about 330 MB with WebGPU (fast), about 90 MB on CPU (slower).
4. Sentences are generated about 40 seconds ahead of the playhead and queued in
   the Web Audio API, so playback starts after a few seconds, not after the whole
   book is processed.
5. The reading position is remembered per file in localStorage.

"Save audio" (the download icon per section, or "Save selected as audio") makes
16-bit mono WAV files; several sections are packed in a ZIP. A full book is large
(about 3 MB per minute of audio).

## Limits

- Scanned PDFs (images of pages) have no text. They need OCR first.
- DRM-protected EPUBs (Kindle, Kobo, Adobe DRM) cannot be read.
- Kokoro in the browser is English-only here. Other languages will sound wrong.
- On a slow CPU, generation can be slower than playback and you will hear pauses.
  The status line shows the current speed. WebGPU or saving audio first fixes this.
- Multi-column PDFs and tables are read in the order the PDF stores them, which
  is not always the visual order.

## Changing things

- Library versions and the model id are pinned at the top of `app.js`.
- Voices: edit the `VOICES` list in `app.js` (ids come from Kokoro's voice list).
- Pause lengths between sentences and paragraphs: `GAP_SENTENCE` and
  `GAP_PARAGRAPH` in `app.js`.
- Colors and fonts: the CSS variables at the top of `index.html`.

## Credits

Kokoro-82M (Apache-2.0) by hexgrad, `kokoro-js` and Transformers.js by Hugging
Face, pdf.js by Mozilla, JSZip. Sample text: Jane Austen, *Pride and Prejudice*
(public domain). Inspired by plusuncold/autiobooks.
