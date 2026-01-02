# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build and install on connected device
./gradlew installDebug

# Run lint
./gradlew lint

# Run tests
./gradlew test

# Clean build
./gradlew clean
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

### Dictionary Setup (first-time only)

The app bundles a multi-dictionary database with Jitendex (general terms) and JMnedict (names/places).

```bash
cd tools/data

# Download Jitendex (Yomitan format)
curl -L -o jitendex.zip "https://github.com/stephenmk/stephenmk.github.io/releases/latest/download/jitendex-yomitan.zip"

# Download JMnedict (names/places)
curl -L -o JMnedict.xml.gz "http://ftp.edrdg.org/pub/Nihongo/JMnedict.xml.gz"
gunzip JMnedict.xml.gz

# Convert to unified SQLite database
cd ..
python3 convert_dictionaries.py \
    --jitendex data/jitendex.zip \
    --jmnedict data/JMnedict.xml \
    --output ../app/src/main/assets/dictionary.db

# Download Innocent Corpus frequency (5000+ VNs)
curl -L -o innocent_corpus.zip "https://github.com/FooSoft/yomichan/raw/dictionaries/innocent_corpus.zip"

# Re-run with frequency data
python3 convert_dictionaries.py \
    --jitendex data/jitendex.zip \
    --jmnedict data/JMnedict.xml \
    --frequency data/innocent_corpus.zip \
    --output ../app/src/main/assets/dictionary.db
```

The resulting database contains ~1M entries (292K Jitendex + 748K JMnedict) with 149K frequency rankings from the Innocent Corpus. APK size: ~178MB.

## Architecture Overview

Yomidroid is a Japanese OCR dictionary overlay for Android that captures screen content, recognizes Japanese text via ML Kit, and performs Yomitan-style dictionary lookups with deinflection support.

### Core Components

**YomidroidAccessibilityService** (`service/YomidroidAccessibilityService.kt`)
- Central coordinator running as an AccessibilityService
- Manages screen capture, OCR pipeline, and overlay UI
- Creates and positions the floating action button (CursorFabView)
- Handles tap events on recognized text to trigger lookups

**DictionaryEngine** (`dictionary/DictionaryEngine.kt`)
- Implements longest-match-first lookup algorithm
- Uses LanguageTransformer to generate deinflection variants
- Queries DictionaryDb for each variant, sorts by match length then score

**LanguageTransformer** (`dictionary/LanguageTransformer.kt`)
- Complete port of Yomitan's Japanese deinflection rules
- Handles all verb/adjective conjugations, contractions, dialect forms

**DictionaryDb** (`data/DictionaryDb.kt`)
- Read-only SQLite access to merged dictionary data (Jitendex + JMnedict)
- Thread-safe lazy singleton pattern
- Auto-copies from assets on first launch
- Supports source attribution (source, name_type, frequency_rank columns)

**OverlayTextView** (`service/OverlayTextView.kt`)
- Custom View rendering screenshot, highlighted text regions, and dictionary popups
- Handles tap detection for word lookup at character-level precision

### Data Flow

1. User taps FAB → AccessibilityService captures screenshot
2. ML Kit OCR extracts Japanese text with bounding boxes
3. OverlayTextView displays screenshot with highlighted text regions
4. User taps highlighted text → DictionaryEngine performs lookup
5. Dictionary popup shown with reading/definitions
6. Optional: Export to AnkiDroid

### Key Patterns

- **Singleton databases**: Both DictionaryDb and Room AppDatabase use thread-safe lazy singletons
- **Coroutine-based async**: Database queries on Dispatchers.IO, UI updates via Compose state
- **Custom View rendering**: OverlayTextView and CursorFabView draw directly on Canvas

## Tech Stack

- **Language**: Kotlin 1.9.20
- **UI**: Jetpack Compose with Material3
- **OCR**: Google ML Kit (text-recognition-japanese)
- **Database**: SQLite (dictionary), Room (lookup history)
- **Target SDK**: 34 (Android 14), Min SDK: 26

## Licensing Note

The deinflection rules in LanguageTransformer.kt are GPL-3.0 (ported from Yomitan). App code is MIT.
