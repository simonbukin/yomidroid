# Yomidroid

Japanese OCR dictionary overlay for Android. Capture any screen, recognize Japanese text, and look up words with Yomitan-style deinflection.

> **Heads up! this is mostly a personal-use app.** It's mostly vibecoded, optimized for what I happen to need, and lived-in daily on an [AYN Thor](https://www.ayntec.com/) for playing retro Japanese games. If it works for you too, great! If it doesn't, that's probably expected. Issues and PRs are welcome but not promised, I've got a full time job :)

## Features

### Core
- Floating overlay button works over any app
- Japanese OCR via ML Kit or manga-ocr 
- AnkiDroid export for flashcard creation
- Longest-match lookup with full deinflection support
- Yomitan dictionary compatible. Bring your own!
- Dictionary popup with readings, definitions, frequency ranking
- Lookup history with screenshots

### In-app workspace

The app organizes around five tabs in the bottom nav:

- **Now** — the current scan workspace. Three sub-tabs over a shared editable sentence:
- **Lookup** (decoupled mode only) — dictionary entries from your last tap, mirrored from the overlay.
- **Parse** — Kuromoji morphology, bunsetsu chips, DOJG grammar matches with GameGengo / JLPTSensei links.
- **Translate** — Natural / Literal / Leipzig gloss, powered by Gemini Flash with on-device Kuromoji as the offline fallback.
- **Search** — universal search. Type any Japanese (a word, a conjugated form, a grammar pattern) and get dictionary entries + grammar library hits side-by-side.
- **Library** — browse 428 JLPT N5–N1 grammar points with video and reference links.
- **History** — every word lookup with screenshot, source app, time filters, Anki export per row.
- **Settings** — permissions, modes, recognition, translation backend, Anki export, colors, hardware controls.

### Modes

Two operating modes, toggled from Settings or from a Quick Settings tile:

- **Coupled (default)** — dictionary entries appear in the overlay popup right where you tapped.
- **Decoupled** — overlay popup is suppressed and lookups appear in-app. This is meant for dual screen devices playing single screen games (eg. PSP VN on top screen, dictionary on bottom)

Two Quick Settings tiles ship with the app: **Yomidroid** (toggle the floating button) and **Decoupled** (toggle decoupled mode). Drag down twice from the top of the screen and add them to your QS panel.

## Install

1. Go to the **[latest release](https://github.com/simonbukin/yomidroid/releases/latest)** and download the `yomidroid-*.apk` attached to it.
2. Install it on your device:
   - **Via adb:** `adb install -r yomidroid-*.apk`
   - **On-device:** transfer the APK to your phone, tap it in your file manager, and accept the *"Install unknown apps"* prompt for that app.

Releases are debug-signed and sideload-only — they can't upgrade a Play Store install.

## First-Time Setup

1. **Grant overlay permission** when prompted (*Display over other apps*).
2. **Enable the accessibility service** at *Settings → Accessibility → Yomidroid*. The floating button appears once this is on.
3. **Import dictionaries.** The app ships without any built-in dictionary. Open *Settings → Dictionaries* and either:
   - Tap **Import Yomitan Dictionaries** to pick Yomitan-format `.zip` files you already have, or
   - Choose from the **Recommended** list to download common dictionaries directly — Jitendex (general), JMnedict (names/places), KANJIDIC, pitch accents, and frequency data.
4. **(Optional) Configure translation.** Open *Settings → Translation Backend* to set up Gemini Flash (paste an API key) or an on-device LLM. The Translate sub-tab still works without a backend: it'll show a Kuromoji-only morpheme breakdown.

Once at least one dictionary is loaded, lookups work end-to-end.

## Build from Source

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK 34

### Build & Install

```bash
# Build debug APK
./gradlew assembleDebug

# Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open in Android Studio and click Run.

## Usage

1. Open the app you want to read (game, visual novel, manga reader, etc.).
2. Tap the floating button to capture and scan.
3. Tap highlighted text to look up words.
4. Tap outside the popup to dismiss the overlay.

To translate or parse the sentence you just tapped, open the app and head to **Now**. The sentence is already loaded, and you can edit it, tap **Translate** or **Analyze**, and you're done.

To look up something you remember from earlier without re-OCRing (or perhaps the OCR didn't scan correctly), use the **Search** tab. It queries the dictionary and the grammar library at the same time.

## Attribution

### Recommended Dictionaries

The app ships without bundled dictionaries. The in-app *Recommended* importer pulls from:

- [Jitendex](https://github.com/stephenmk/stephenmk.github.io) - General-purpose Japanese-English dictionary
- [JMnedict](https://www.edrdg.org/enamdict/enamdict_doc.html) - Names and places (CC BY-SA)
- [Innocent Corpus](https://github.com/FooSoft/yomichan) - Frequency rankings from 5000+ visual novels
- KANJIDIC and pitch-accent data from the standard Yomitan ecosystem

Any Yomitan-format `.zip` works via the manual import flow.

### Grammar Data
- [GameGengo](https://www.youtube.com/@GameGengo) - JLPT grammar video timestamps
- [JLPTSensei](https://jlptsensei.com) - Grammar definitions
- DOJG references via [kenrick95/itazuraneko](https://github.com/kenrick95/itazuraneko)

### Core Libraries
- [Yomitan](https://github.com/yomitan/yomitan) - Deinflection algorithm (GPL-3.0)
- [Kuromoji](https://github.com/atilika/kuromoji) - Japanese morphological analysis
- [llama.cpp](https://github.com/ggml-org/llama.cpp) - On-device LLM inference (MIT)
- [Google ML Kit](https://developers.google.com/ml-kit) - OCR and translation
- [RapidOCR](https://github.com/RapidAI/RapidOCR)/PaddleOCR - Alternative OCR (Apache 2.0)
- [OpenCV](https://opencv.org/) - Image processing (Apache 2.0)
- [ONNX Runtime](https://onnxruntime.ai/) - ML model inference (MIT)
- [AnkiDroid API](https://github.com/ankidroid/Anki-Android) - Flashcard export

## License

MIT License (app code)
GPL-3.0 (deinflection rules ported from Yomitan)

See [LICENSE](LICENSE) for details.
