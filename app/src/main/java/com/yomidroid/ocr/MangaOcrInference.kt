package com.yomidroid.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * ONNX-based inference for manga-ocr (ViT encoder + BERT decoder).
 * Preprocesses cropped text region bitmaps and runs autoregressive decoding.
 *
 * [bilinearScaling] controls how the crop is resampled to 224×224. For inked
 * print/manga, bilinear matches the model's training distribution. For pixel
 * fonts (retro emulators), `false` (NEAREST) preserves the pixel grid that
 * bilinear blurs into mush.
 */
class MangaOcrInference(
    encoderPath: String,
    decoderPath: String,
    vocabPath: String,
    var bilinearScaling: Boolean = true
) {
    companion object {
        private const val TAG = "MangaOcrInference"
        private const val IMAGE_SIZE = 224
        // Game/manga lines fit well under 64 tokens. Cap hallucinations from
        // out-of-distribution input (was 300 — up to 300 sequential decoder
        // steps with no KV cache).
        private const val MAX_TOKENS = 64
    }

    private val env = OrtEnvironment.getEnvironment()
    private val sessionOptions = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        // No XNNPACK — its ARM64 SIMD kernels SIGSEGV on certain inputs.
        // See OcrLibrary/.../OcrEngine.kt for the same workaround.
    }
    private val encoderSession = env.createSession(encoderPath, sessionOptions)
    private val decoderSession = env.createSession(decoderPath, sessionOptions)
    private val tokenizer = MangaOcrTokenizer(vocabPath)

    // Discover tensor names from sessions
    private val encoderInputName = encoderSession.inputNames.first()
    private val encoderOutputName = encoderSession.outputNames.first()
    private val decoderInputNames = decoderSession.inputNames.toList()
    private val decoderOutputName = decoderSession.outputNames.first()

    init {
        Log.d(TAG, "Encoder input: $encoderInputName, output: $encoderOutputName")
        Log.d(TAG, "Decoder inputs: $decoderInputNames, output: $decoderOutputName")
    }

    /**
     * Recognize text from a cropped bitmap region.
     * @param bitmap Cropped text region bitmap
     * @return Recognized text string
     */
    fun recognize(bitmap: Bitmap): String {
        val inputTensor = preprocessImage(bitmap)
        var encoderOutput: OnnxTensor? = null
        try {
            val encStart = SystemClock.elapsedRealtime()
            val encoderResult = encoderSession.run(mapOf(encoderInputName to inputTensor))
            encoderOutput = encoderResult.get(encoderOutputName).get() as OnnxTensor
            val encMs = SystemClock.elapsedRealtime() - encStart

            val decStart = SystemClock.elapsedRealtime()
            val tokenIds = mutableListOf(MangaOcrTokenizer.CLS_ID)
            var steps = 0
            for (step in 0 until MAX_TOKENS) {
                val nextToken = decoderStep(tokenIds, encoderOutput)
                steps = step + 1
                if (nextToken == MangaOcrTokenizer.SEP_ID) break
                tokenIds.add(nextToken)
            }
            val decMs = SystemClock.elapsedRealtime() - decStart
            Log.d(TAG, "encoder ms=$encMs decoder ms=$decMs steps=$steps")

            return tokenizer.decode(tokenIds)
        } finally {
            inputTensor.close()
            encoderOutput?.close()
        }
    }

    private fun decoderStep(tokenIds: List<Int>, encoderHidden: OnnxTensor): Int {
        val idsArray = tokenIds.map { it.toLong() }.toLongArray()
        val idsTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(idsArray),
            longArrayOf(1, idsArray.size.toLong())
        )

        try {
            // Build decoder inputs — find which input name is for IDs vs encoder hidden states
            val inputs = buildDecoderInputs(idsTensor, encoderHidden)
            val result = decoderSession.run(inputs)
            val logits = result.get(decoderOutputName).get() as OnnxTensor

            try {
                // logits shape: [1, seq_len, vocab_size] — take argmax of last position
                val logitsData = logits.floatBuffer
                val shape = logits.info.shape
                val vocabSize = shape[shape.size - 1].toInt()
                val seqLen = shape[shape.size - 2].toInt()

                // Skip to last position logits
                val offset = (seqLen - 1) * vocabSize
                var maxIdx = 0
                var maxVal = Float.NEGATIVE_INFINITY
                for (i in 0 until vocabSize) {
                    val v = logitsData.get(offset + i)
                    if (v > maxVal) {
                        maxVal = v
                        maxIdx = i
                    }
                }
                return maxIdx
            } finally {
                logits.close()
            }
        } finally {
            idsTensor.close()
        }
    }

    private fun buildDecoderInputs(
        idsTensor: OnnxTensor,
        encoderHidden: OnnxTensor
    ): Map<String, OnnxTensor> {
        // The decoder has two inputs: input_ids and encoder_hidden_states
        // Map them by name matching
        val inputs = mutableMapOf<String, OnnxTensor>()
        for (name in decoderInputNames) {
            when {
                name.contains("input_ids") || name.contains("decoder") -> inputs[name] = idsTensor
                name.contains("encoder") || name.contains("hidden") -> inputs[name] = encoderHidden
                else -> {
                    // Fallback: first input is ids, second is encoder states
                    if (inputs.isEmpty()) inputs[name] = idsTensor
                    else inputs[name] = encoderHidden
                }
            }
        }
        return inputs
    }

    private fun preprocessImage(bitmap: Bitmap): OnnxTensor {
        // Match kha-white/manga-ocr's preprocessor exactly:
        //   img.convert('L').convert('RGB')               # grayscale -> RGB
        //   ViTImageProcessor(size=224, image_mean=[0.5]*3, image_std=[0.5]*3)
        // ViTImageProcessor with int size=224 stretches to (224, 224) with PIL
        // BILINEAR — no aspect-preserving letterbox. Earlier letterbox attempt
        // was wrong; the model was trained on stretched square crops.
        val scaled = Bitmap.createScaledBitmap(bitmap, IMAGE_SIZE, IMAGE_SIZE, bilinearScaling)
        val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE)
        scaled.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)
        if (scaled !== bitmap) scaled.recycle()

        val channelSize = IMAGE_SIZE * IMAGE_SIZE
        val buffer = FloatBuffer.allocate(3 * channelSize)
        for (i in 0 until channelSize) {
            val px = pixels[i]
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            // Rec. 601 luma; PIL "L" mode uses the same coefficients.
            val gray = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            val norm = (gray - 0.5f) / 0.5f
            buffer.put(i, norm)
            buffer.put(channelSize + i, norm)
            buffer.put(2 * channelSize + i, norm)
        }
        buffer.rewind()

        return OnnxTensor.createTensor(
            env,
            buffer,
            longArrayOf(1, 3, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong())
        )
    }

    fun close() {
        encoderSession.close()
        decoderSession.close()
        env.close()
        Log.d(TAG, "MangaOcrInference closed")
    }
}
