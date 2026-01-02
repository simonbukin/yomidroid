- id like to move towards a dual screen approach for this app (or at least a dual screen mode). what this means practically is the app itself, when open, should have a wealth of functionality, so if it is open on a secondary screen on a device that supprots it (eg the AYN Thor, which I will soon acquire), it will function as a sidekick for immersion purposes for Japanese. I think that there are a couple of good options here that we can consider: a grammar breakdown based on the current OCR image, showing sentences chunked and bunsetsued. we would need a grammar analyzer for this, so check this link and analyze what we can do (it must be on device, in app): https://tiffena.me/blog/japan/learn%20japanese/tools/#llms-as-personal-tutor. Next, it would be nice to have some games built in, pedagogically sound of course. Something like a sentence rearranger / shuffled bunsetsu, now arrange it correctly sort of thing, like duolingo or renshuu. a grammar library would also be really awesome to include. I can think of two potential, nice sources for this: GameGengo's Grammar videos (these are very long, detailed breakdowns of grammar written as a textbook from gaming examples. we would need to get the tranascript for the video and then get the description of the video to get timestamps, and match the transcript text to it so it is hotlinkable). The other source is the "a dictionary of japanese grammar" series. I have this downloaded offline here as dojg-data.json. take these ideas and run with them, and think of other cool ways we can engage the user pedagogically and act as teh best possible immersion helper for japanese.

---

## Feature Plan: Multi-Backend OCR System

### Background

The current ML Kit Japanese OCR works well for modern, clear text but struggles with:
- Pixelated text from PSP-era visual novels and retro games
- Stylized manga fonts
- Low-resolution or blurry screenshots

Research shows these alternatives perform better on challenging text:

| Engine | Accuracy on Pixelated | Offline | Free | Notes |
|--------|----------------------|---------|------|-------|
| **Google Lens** | Excellent | No | Yes (no API key) | Uses Chrome's internal API |
| **Google Cloud Vision** | Excellent | No | 1000/mo free | Official API, requires key |
| **Manga OCR** | Good | Yes | Yes | Specialized for Japanese manga |
| **PaddleOCR** | Moderate | Yes | Yes | General purpose, customizable |

### Implementation Phases

#### Phase 1: OcrEngine Interface
Create abstraction layer allowing pluggable OCR backends:

```kotlin
interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap): List<OcrResult>
    val name: String
    val requiresNetwork: Boolean
}

class MlKitOcrEngine : OcrEngine  // Current implementation
```

#### Phase 2: Google Lens Backend (Recommended First)
Port the [chrome-lens-py](https://github.com/bropines/chrome-lens-py) protocol to Kotlin:
- Uses Google's protobuf endpoint (`v1/crupload`)
- No API key or account required
- Japanese explicitly supported (`"ja"` language code)
- Returns text with character-level coordinates (maps to charBounds)
- Built-in rate limiting to avoid IP bans

Implementation approach:
1. Port protobuf message structures to Kotlin
2. Use OkHttp for HTTP requests
3. Parse response to extract text + bounding boxes
4. Map to existing `OcrResult` format

#### Phase 3: Manga OCR Backend (Offline)
Based on [kha-white/manga-ocr](https://github.com/kha-white/manga-ocr):
- Transformer model fine-tuned on manga text
- Handles vertical text, furigana, stylized fonts
- Run on-device via TensorFlow Lite or ONNX Runtime

Considerations:
- Model size ~100MB (increases APK significantly)
- Could use on-demand model download
- May need quantization for mobile performance

#### Phase 4: PaddleOCR Backend (Offline Alternative)
- More general-purpose than Manga OCR
- Supports [PaddleOCR-VL-For-Manga](https://huggingface.co/jzhang533/PaddleOCR-VL-For-Manga) fine-tuned model
- Multiple language support

#### Phase 5: Settings UI
Add OCR backend selection in Settings:
- Radio buttons for engine selection
- Network indicator for cloud-based options
- Test button to compare results
- Auto-fallback option (try cloud, fallback to on-device)

### Files to Create/Modify

| File | Action |
|------|--------|
| `ocr/OcrEngine.kt` | **Create** - Interface definition |
| `ocr/MlKitOcrEngine.kt` | **Create** - Current implementation refactored |
| `ocr/GoogleLensOcrEngine.kt` | **Create** - Chrome Lens API integration |
| `ocr/MangaOcrEngine.kt` | **Create** - TFLite/ONNX inference |
| `ui/settings/OcrSettingsScreen.kt` | **Create** - Settings UI |
| `service/YomidroidAccessibilityService.kt` | Modify - Use selected engine |
| `config/OcrConfig.kt` | **Create** - Engine selection storage |

### References
- [chrome-lens-ocr (JavaScript)](https://github.com/dimdenGD/chrome-lens-ocr) - Original implementation
- [chrome-lens-py (Python)](https://github.com/bropines/chrome-lens-py) - Python port with docs
- [manga-ocr](https://github.com/kha-white/manga-ocr) - Japanese manga OCR model
- [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) - Multi-language OCR
- [YomiNinja](https://github.com/matt-m-o/YomiNinja) - Reference implementation with multiple backends 
