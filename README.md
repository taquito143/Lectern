# Lectern

Upload a PDF, EPUB, DOCX, TXT or HTML file and listen to it read aloud. Everything
runs in the browser: text extraction and speech ([KittenTTS 2 nano](https://github.com/KittenML/KittenTTS),
17M parameters per language, on ONNX Runtime Web). There is no server and nothing
is uploaded.

## Files

| File | What it does |
| --- | --- |
| `index.html` | Page, styles and layout |
| `app.js` | UI, model loading, audio playback queue, saving audio |
| `kitten.js` | Runs KittenTTS 2 nano: downloads and caches the model, voice packs, synthesis |
| `english.js` | English text to misaki phonemes (the alphabet KittenTTS was trained on) |
| `spanish.js` | Spanish text to IPA phonemes, in the eSpeak NG style KittenTTS's Spanish model was trained on |
| `parsers.js` | PDF / EPUB / DOCX / TXT / HTML to sections, then to sentences |
| `g2p/` | English pronunciation dictionaries: misaki (us_gold + us_silver) and CMUdict in misaki's alphabet |

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
first time (libraries from jsDelivr, voice models from Hugging Face).

## Put it online

It is a static site: drag this folder into Netlify Drop, or use Cloudflare
Pages, GitHub Pages or Vercel. No build step, no environment variables.

## How it works

1. The file is parsed in the browser. PDFs lose repeated headers, footers and
   page numbers; words hyphenated across line breaks are rejoined. Chapters come
   from the EPUB table of contents, the PDF outline, or "Chapter N" headings,
   with a fallback to blocks of 10 pages.
2. Text is split into sentences. Click any sentence to start reading from it.
3. On the first Play, the voice model for the book's language downloads and is
   kept in the browser's cache: about 17 MB of model plus 2 MB of voices (English
   also loads 2 MB of pronunciation dictionaries). Text becomes phonemes on the
   page (`english.js`, `spanish.js`), and the model runs with WebGPU when the
   browser has it, otherwise on the CPU.
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
- English and Spanish only (KittenTTS 2 nano also has German, French, Italian,
  Portuguese, Russian and Hindi models; they would need a phonemizer each).
  Spanish text is turned into phonemes by `spanish.js` (rules; Latin American,
  Río de la Plata or Spain accent, chosen next to the voice; reads money, dates,
  times and ordinals). A Spanish book switches to a Spanish voice automatically.
- English words missing from both dictionaries are guessed from common suffixes,
  then spelled out letter by letter.
- Without WebGPU, generation runs on one CPU thread (the page isn't
  cross-origin isolated) and can be slower than playback, so you will hear pauses.
  The status line shows the current speed. Saving audio first avoids this.
- ONNX Runtime Web is pinned to 1.22.0: from 1.29 its WebGPU backend cannot load
  this model.
- Multi-column PDFs and tables are read in the order the PDF stores them, which
  is not always the visual order.

## Android app

A native Android version (Kotlin, Jetpack Compose) is in [`android/`](android/). It
reads with the phone's own voice by default (instant, any installed language) or
with KittenTTS on the phone, keeps reading with the screen off, and has lock-screen
and headphone controls. See [`android/README.md`](android/README.md) to build it.

## Changing things

- Library versions are pinned at the top of `app.js` and `kitten.js`; the model
  is pinned to a Hugging Face commit in `kitten.js`.
- Voices: edit the `VOICES` list in `app.js` (`language:key`, keys from the
  model's `config.json`).
- Pause lengths between sentences and paragraphs: `GAP_SENTENCE` and
  `GAP_PARAGRAPH` in `app.js`.
- Colors and fonts: the "Terminal Void Audio" skin at the end of the `<style>` in
  `index.html` (light and dark variables, same design as the Android app). The
  theme follows the device unless set with the Theme button; the accent uses the
  system accent color where the browser supports `AccentColor`, otherwise lime.

## Credits

KittenTTS 2 nano (Apache-2.0) by KittenML, misaki dictionaries (Apache-2.0) by
hexgrad, CMUdict (BSD) via KittenML, ONNX Runtime Web by Microsoft, pdf.js by
Mozilla, JSZip. Sample text: Jane Austen, *Pride and Prejudice*
(public domain). Inspired by plusuncold/autiobooks.
