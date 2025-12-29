# VNDict - Visual Novel Dictionary

Japanese OCR dictionary overlay for Android. Tap a floating button to capture the screen, recognize Japanese text with ML Kit, and look up words with Yomitan-style deinflection.

## Features

- Floating overlay button that works over any app
- Japanese text recognition using Google ML Kit
- Tappable text regions with character-level precision
- Yomitan-style longest-match lookup with full deinflection support
- Dictionary popup with readings and definitions
- Support for conjugated verbs, adjectives, and colloquial forms

## Building

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK 34

### Dictionary Setup

1. Download JMDict XML from [EDRDG](https://www.edrdg.org/wiki/index.php/JMdict-EDICT_Dictionary_Project):
   ```bash
   wget http://ftp.edrdg.org/pub/Nihongo/JMdict_e.gz
   gunzip JMdict_e.gz
   ```

2. Convert to SQLite:
   ```bash
   cd tools
   python3 convert_jmdict.py JMdict_e.xml ../app/src/main/assets/dictionary.db
   ```

3. (Optional) Add frequency data for better sorting

### Build APK

```bash
# Using Gradle wrapper
./gradlew assembleDebug

# APK will be at: app/build/outputs/apk/debug/app-debug.apk
```

Or open in Android Studio and click Run.

## Installation

### Via ADB

1. Enable Developer Options on your device:
   - Settings → About Phone → Tap "Build Number" 7 times

2. Enable USB Debugging:
   - Settings → Developer Options → USB Debugging ON

3. Connect device and install:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

### Permissions

The app requires:
- **Draw over other apps** - For the floating button and overlay
- **Screen capture** - To capture screenshots for OCR

Both permissions must be granted manually in Settings.

## Usage

1. Launch VNDict and grant overlay permission
2. Tap "Start Overlay" to begin
3. Grant screen capture permission when prompted
4. A floating button appears on screen
5. Navigate to your visual novel
6. Tap the floating button to capture and scan
7. Tap on highlighted text to look up words
8. Tap outside to dismiss the overlay

## Architecture

```
com.vndict/
├── MainActivity.kt          # Main UI, permission handling
├── CapturePermissionActivity.kt  # MediaProjection consent
├── service/
│   ├── OverlayService.kt    # Foreground service, FAB, screen capture
│   └── OverlayTextView.kt   # Text overlay with tap handling
├── ocr/
│   └── OcrResult.kt         # OCR result data class
├── dictionary/
│   ├── DictionaryEngine.kt  # Longest-match lookup
│   ├── DictionaryEntry.kt   # Entry data class
│   └── LanguageTransformer.kt  # Yomitan deinflection (full port)
└── data/
    └── DictionaryDatabase.kt  # Room database
```

## Deinflection

The deinflection system is a complete port of Yomitan's `japanese-transforms.js`, supporting:

- All verb conjugations (ichidan, godan, irregular)
- Adjective conjugations
- Polite forms (-ます, -ません)
- Te-form, ta-form, conditional
- Potential, passive, causative
- Negative forms
- Contractions (-ちゃう, -ちまう, etc.)
- Kansai dialect forms
- Slang sound changes

## License

- App code: MIT
- Deinflection rules: GPL-3.0 (ported from Yomitan)
- JMDict: Creative Commons Attribution-ShareAlike License

## Credits

- [Yomitan](https://github.com/yomitan/yomitan) - Deinflection algorithm and rules
- [JMdict](https://www.edrdg.org/jmdict/j_jmdict.html) - Japanese dictionary data
- [Google ML Kit](https://developers.google.com/ml-kit) - Japanese text recognition
