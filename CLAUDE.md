# CLAUDE.md

Guidance for Claude Code (claude.ai/code) when working in this repository.

## Build Commands

```bash
./gradlew assembleDebug      # Build debug APK
./gradlew installDebug       # Build + install on connected device (preferred)
./gradlew lint
./gradlew test
./gradlew clean
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

After `assembleDebug`, also run `installDebug` (or `adb install -r ...`) so the change is actually on-device. The repo CI builds the APK; manual on-device verification is up to the developer because there are no UI tests.

## What this app is

Yomidroid is a Japanese OCR + dictionary overlay for Android. It captures the screen via an AccessibilityService, runs Japanese OCR, and lets the user tap recognized text to get Yomitan-style dictionary lookups with deinflection. Daily-driven on an AYN Thor for retro Japanese games; mostly a personal-use app.

It also includes: an in-app workspace with grammar parsing (Kuromoji + DOJG), translation (Gemini Flash + on-device llama.cpp fallback), a grammar library (JLPT N5–N1), AnkiDroid export, lookup history with screenshots, and system TTS for any displayed Japanese text.

**Dictionaries are bring-your-own.** Nothing is bundled. Users import Yomitan-format `.zip` dictionaries through *Settings → Dictionaries* (manual import or the in-app *Recommended* downloader for Jitendex / JMnedict / KANJIDIC / pitch / frequency).

## Top-level packages (`app/src/main/java/com/yomidroid/`)

| Package | Purpose |
|---|---|
| `service/` | AccessibilityService, FAB, overlay views, QS tile services |
| `ocr/` | OCR engines (ML Kit, manga-ocr ONNX, RapidOCR) and the `OcrEngineFactory` |
| `dictionary/` | `DictionaryEngine`, `LanguageTransformer` (Yomitan deinflection), entry models, serialization |
| `data/` | `DictionaryDb` (multi-DB SQLite manager), Room `AppDatabase` for history, Yomitan zip import |
| `grammar/` | DOJG grammar resolver, Kuromoji morphology, JLPT grammar library |
| `translation/` | `TranslationService`, backends (Gemini Flash, on-device LLM, ML Kit fallback) |
| `llm/` | llama.cpp JNI bindings + on-device model management |
| `anki/` | AnkiDroid export via the official AnkiDroid API |
| `tts/` | `TtsManager` singleton wrapping system `TextToSpeech` |
| `config/` | User-facing config managers (dictionaries, OCR, input, colors, …) |
| `ui/` | All Compose screens — `now/`, `search/`, `history/`, `settings/`, `components/` |

## Architecture notes

- **AccessibilityService is the central coordinator.** `service/YomidroidAccessibilityService.kt` owns screen capture, OCR pipeline, and the overlay window. The floating button is `CursorFabView`; the captured-text view is `OverlayTextView` (Canvas-drawn).
- **Two operating modes**, toggled from Settings or QS tile:
  - *Coupled* (default) — dictionary popup appears in the overlay where the user tapped.
  - *Decoupled* — overlay popup is suppressed and lookups appear in the in-app **Now → Lookup** tab. Meant for dual-screen handhelds.
- **Dictionary popup** has two implementations:
  - `service/OverlayPopupWebView.kt` + `assets/popup/popup.html|js|css` — the primary path. WebView-rendered for rich pitch-accent SVGs, furigana, frequency badges, action buttons.
  - `service/OverlayTextView.kt` (Canvas) — fallback for in-place rendering. Keep parity with the WebView behavior when changing entry rendering.
- **The same WebView renderer is reused in-app** via `ui/components/DictionaryEntryWebView.kt` (History detail, Now → Lookup). The JS bridge (`YomidroidPopup`) handles `ankiExport`, `speak`, and content-height reporting.
- **Multi-dictionary**, not a merged blob. `data/DictionaryDb.kt` holds maps of dictId → SQLiteDatabase for term, frequency, pitch, and kanji databases, with a user-defined priority order. `DictionaryEngine` queries each variant produced by `LanguageTransformer` against the priority chain.
- **Singletons** for shared infra: `DictionaryDb.getInstance(context)`, `AppDatabase.getInstance(context)`, `TtsManager.getInstance(context)`, `MangaOcrModelManager.getInstance(context)`, etc. Mirror this pattern when adding new managers.
- **Coroutines.** DB and OCR work on `Dispatchers.IO`; UI updates via Compose state.

## TTS

- `tts/TtsManager.kt` is a singleton wrapping `android.speech.tts.TextToSpeech`.
- Tries `Locale.JAPAN` → `Locale.JAPANESE` → `Locale("ja", "JP")` and falls back to `setVoice` over `tts.voices` filtered by `locale.language == "ja"`.
- AndroidManifest has a `<queries><intent><action android:name="android.intent.action.TTS_SERVICE"/></intent></queries>` entry — required on Android 11+ for the engine to be visible.
- `speak(text, showErrorToast = true)` surfaces engine/locale failures to the user via Toast. Use the toast variant on direct user-tap entry points; logging-only is fine elsewhere.

## Data flow (lookup path)

1. User taps the FAB → `AccessibilityService` captures a screenshot.
2. The active OCR engine (ML Kit / manga-ocr / RapidOCR) extracts Japanese text + bounding boxes.
3. `OverlayTextView` paints the screenshot with highlighted text regions.
4. User taps a region → `DictionaryEngine.findAllMatches` (longest-match-first, deinflection-aware) → entries shown in `OverlayPopupWebView`.
5. From the popup: tap a speaker icon for TTS, the Anki star for export, or anywhere outside to dismiss.
6. The lookup is also recorded in Room `LookupHistoryEntity` with screenshot, source app, and (when available) sentence context.

## Tech Stack

- Kotlin 1.9.20, Jetpack Compose, Material3
- minSdk 26 (Android 8.0), compileSdk/targetSdk 34 (Android 14)
- NDK 26.1 + CMake for llama.cpp (arm64-v8a, x86_64)
- ML Kit (Japanese text recognition + offline translation), ONNX Runtime (manga-ocr / RapidOCR), OpenCV (image preprocessing)
- Room (history), SQLite (dictionaries)
- AnkiDroid API for flashcard export

## Licensing

- Deinflection rules in `LanguageTransformer.kt` are GPL-3.0 (ported from Yomitan).
- Everything else is MIT. See `LICENSE`.
