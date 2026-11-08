# Lectern for Android

Native Kotlin version of Lectern. Open a PDF, EPUB, DOCX, TXT or HTML file and
listen to it read aloud. The book never leaves the device. The web version is in
the repository root.

Two voice engines:

- **Phone voice** (default): Android's text-to-speech engine, usually Google's.
  Reads instantly in any language the phone has installed.
- **Kokoro-82M**: a more natural voice generated on the phone (92 MB download).
  Spanish and English only. On older phones it is slower than reading speed, so
  it prepares audio ahead and keeps it in a cache.

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
| `tts/EnglishPhonemizer.kt` | English text to Kokoro phonemes with misaki's dictionaries (`assets/en_dict.tsv.gz`). |
| `tts/KokoroEngine.kt` | Runs `model_quantized.onnx` (92 MB) on ONNX Runtime. |
| `tts/ModelFiles.kt` | Downloads the model and voices from Hugging Face the first time. |
| `player/Reader.kt` | Book state, position, settings, both engines' playback. |
| `player/SystemSpeech.kt` | The phone's text-to-speech engine: voices, languages, queued sentences. |
| `player/AudioCache.kt` | Kokoro audio kept on disk (up to 400 MB), generated ahead and reused. |
| `player/PlaybackService.kt` | Foreground service: notification, lock-screen and headphone controls, wake lock. |
| `ui/` | Jetpack Compose screens: Biblioteca, Reproductor, Lectura, Ajustes. Design: "Terminal Void Audio" (pixel fonts, acid lime, sharp edges). |

- Reading position is saved per book; the library lists opened books.
- Speed, voice and a sleep timer (15/30/60 min or end of chapter) are in the player bar.
- A Spanish book switches to a Spanish voice automatically, and vice versa. The
  reading language can also be set by hand (any language the phone's engine has).
- Light or dark follows the phone, or can be fixed in Ajustes. The accent color
  comes from the wallpaper on Android 12+, or is the design's lime.
- Kokoro's Spanish accent: Latin American (default), Río de la Plata or Spain.

## Limits

- Scanned PDFs have no text (they need OCR). DRM-protected EPUBs can't be read.
- English words missing from the dictionary are spelled out letter by letter.
- On slower phones generation can be slower than playback; the status line says so.

## Credits

Kokoro-82M (Apache-2.0, hexgrad), misaki dictionaries (Apache-2.0, hexgrad),
ONNX Runtime (MIT), PdfBox-Android (Apache-2.0), jsoup (MIT). Fonts (SIL Open
Font License): Press Start 2P, Silkscreen, JetBrains Mono, Space Grotesk.
