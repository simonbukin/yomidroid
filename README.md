# Yomidroid

Japanese OCR dictionary overlay for Android. Capture any screen, recognize Japanese text, and look up words with Yomitan-style deinflection.

## Features

### Core
- Floating overlay button works over any app
- Japanese OCR via ML Kit or RapidOCR
- Tappable text with character-level precision
- Longest-match lookup with full deinflection (1M+ dictionary entries)
- Dictionary popup with readings, definitions, frequency ranking

### Tools
- **Grammar Library** - 428 JLPT N5-N2 grammar points with GameGengo video links
- **Grammar Analyzer** - Morphological analysis with Kuromoji
- **Translation** - Multi-backend: remote API, on-device LLM (llama.cpp), ML Kit

### Integration
- AnkiDroid export for flashcard creation
- Lookup history with screenshots

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

Once at least one dictionary is loaded, lookups work end-to-end.

## Build from Source

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK 34
- Python 3 (for dictionary conversion)

### Dictionary Setup (first-time only)

The app bundles a multi-dictionary database with Jitendex (general terms) and JMnedict (names/places).

```bash
cd tools/data

# Download Jitendex (Yomitan format)
curl -L -o jitendex.zip "https://github.com/stephenmk/stephenmk.github.io/releases/latest/download/jitendex-yomitan.zip"

# Download JMnedict (names/places)
curl -L -o JMnedict.xml.gz "http://ftp.edrdg.org/pub/Nihongo/JMnedict.xml.gz"
gunzip JMnedict.xml.gz

# Download Innocent Corpus frequency (5000+ VNs)
curl -L -o innocent_corpus.zip "https://github.com/FooSoft/yomichan/raw/dictionaries/innocent_corpus.zip"

# Convert to unified SQLite database
cd ..
python3 convert_dictionaries.py \
    --jitendex data/jitendex.zip \
    --jmnedict data/JMnedict.xml \
    --frequency data/innocent_corpus.zip \
    --output ../app/src/main/assets/dictionary.db
```

Result: ~1M entries (292K Jitendex + 748K JMnedict) with 149K frequency rankings. APK size: ~178MB.

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

### Tools

Access via the **Tools** tab in the bottom navigation:

- **Grammar Analyzer** - Paste text for morphological breakdown
- **Grammar Library** - Browse JLPT grammar with video links
- **Translation** - Translate text using various backends

## Attribution

### Dictionary Data
- [Jitendex](https://github.com/stephenmk/stephenmk.github.io) - Primary dictionary (~292K entries)
- [JMnedict](https://www.edrdg.org/enamdict/enamdict_doc.html) - Names and places (~748K entries, CC BY-SA)
- [Innocent Corpus](https://github.com/FooSoft/yomichan) - Frequency rankings from 5000+ visual novels

### Grammar Data
- [GameGengo](https://www.youtube.com/@GameGengo) - JLPT grammar video timestamps
- [JLPTSensei](https://jlptsensei.com) - Grammar definitions
- DOJG references via [kenrick95/itazuraneko](https://github.com/kenrick95/itazuraneko)

### Core Libraries
- [Yomitan](https://github.com/yomitan/yomitan) - Deinflection algorithm (GPL-3.0)
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
