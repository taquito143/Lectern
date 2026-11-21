# Lectern for Android

Native Kotlin version of Lectern. Open a PDF, EPUB, DOCX, TXT or HTML file and
listen to it read aloud. The book never leaves the device. The web version is in
the repository root.

Two voice engines:

- **Phone voice** (default): Android's text-to-speech engine, usually Google's.
  Reads instantly in any language the phone has installed.
- **KittenTTS 2 nano**: a more natural voice generated on the phone (about 20 MB
  per language, downloaded the first time). Spanish and English only. It prepares
  audio ahead and keeps it in a cache, so slower phones pause less.

## Build

Open this folder (`android/`) in Android Studio and press Run, or from a terminal:

```
gradlew.bat assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
Requirements: Android Studio (its bundled JDK 21), Android SDK 36. Phones with
Android 8.0 (API 26) or newer; 64-bit ARM phones and the x86_64 emulator.

## How it works

| Folder | What it does |
| --- | --- |
| `text/Parsers.kt` | PDF (PdfBox), EPUB, DOCX, TXT/Markdown, HTML (jsoup) to sections, then to sentences. Port of `parsers.js`. |
| `tts/SpanishPhonemizer.kt` | Spanish text to IPA: accents, money, dates, ordinals. Same rules as `spanish.js`. |
| `tts/EnglishPhonemizer.kt` | English text to misaki phonemes: misaki's dictionaries and CMUdict (`assets/*.tsv.gz`), compounds read as their parts. Same rules as `english.js`. |
| `tts/KittenEngine.kt` | Runs KittenTTS 2 nano (one 17 MB int8 model per language) on ONNX Runtime. Same pipeline as `kitten.js`. |
| `tts/ModelFiles.kt` | Downloads each language's model and voices from Hugging Face the first time. |
| `player/Reader.kt` | Book state, position, settings, both engines' playback. |
| `player/SystemSpeech.kt` | The phone's text-to-speech engine: voices, languages, queued sentences. |
| `player/AudioCache.kt` | KittenTTS audio kept on disk (up to 400 MB), generated ahead and reused. |
| `player/PlaybackService.kt` | Foreground service: notification, lock-screen and headphone controls, wake lock. |
| `ui/` | Jetpack Compose screens: Biblioteca, Reproductor, Lectura, Ajustes. Design: "Terminal Void Audio" (pixel fonts, acid lime, sharp edges). |

- Reading position is saved per book; the library lists opened books.
- Speed, voice and a sleep timer (15/30/60 min or end of chapter) are in the player bar.
- A Spanish book switches to a Spanish voice automatically, and vice versa. The
  reading language can also be set by hand (any language the phone's engine has).
- Light or dark follows the phone, or can be fixed in Ajustes. The accent color
  comes from the wallpaper on Android 12+, or is the design's lime.
- KittenTTS's Spanish accent: Latin American (default), Río de la Plata or Spain.

## Limits

- Scanned PDFs have no text (they need OCR). DRM-protected EPUBs can't be read.
- English words missing from both dictionaries, and not compounds of dictionary
  words, are spelled out letter by letter (the web version asks espeak-ng instead).
- On slower phones generation can be slower than playback; the status line says so.

## Credits

KittenTTS 2 nano (Apache-2.0, KittenML), misaki dictionaries (Apache-2.0, hexgrad),
CMUdict (BSD, via KittenML),
ONNX Runtime (MIT), PdfBox-Android (Apache-2.0), jsoup (MIT). Fonts (SIL Open
Font License): Press Start 2P, Silkscreen, JetBrains Mono, Space Grotesk.
