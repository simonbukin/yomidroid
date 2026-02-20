package com.yomidroid.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.util.Log
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * ONNX-based inference for manga-ocr (ViT encoder + BERT decoder).
 * Preprocesses cropped text region bitmaps and runs autoregressive decoding.
 */
class MangaOcrInference(
    encoderPath: String,
    decoderPath: String,
    vocabPath: String
) {
    companion object {
        private const val TAG = "MangaOcrInference"
        private const val IMAGE_SIZE = 224
        private const val MAX_TOKENS = 300
    }

    private val env = OrtEnvironment.getEnvironment()
    private val encoderSession = env.createSession(encoderPath, OrtSession.SessionOptions())
    private val decoderSession = env.createSession(decoderPath, OrtSession.SessionOptions())
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
            // Run encoder
            val encoderResult = encoderSession.run(mapOf(encoderInputName to inputTensor))
            encoderOutput = encoderResult.get(encoderOutputName).get() as OnnxTensor

            // Autoregressive decoding
            val tokenIds = mutableListOf(MangaOcrTokenizer.CLS_ID)

            for (step in 0 until MAX_TOKENS) {
                val nextToken = decoderStep(tokenIds, encoderOutput)
                if (nextToken == MangaOcrTokenizer.SEP_ID) break
                tokenIds.add(nextToken)
            }

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
        val scaled = Bitmap.createScaledBitmap(bitmap, IMAGE_SIZE, IMAGE_SIZE, true)
        val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE)
        scaled.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)
        if (scaled !== bitmap) scaled.recycle()

        // Convert to [1, 3, 224, 224] NCHW float tensor, normalized (pixel/255 - 0.5) / 0.5
        val buffer = FloatBuffer.allocate(3 * IMAGE_SIZE * IMAGE_SIZE)
        val channelSize = IMAGE_SIZE * IMAGE_SIZE

        // R channel
        for (i in 0 until channelSize) {
            val r = (pixels[i] shr 16 and 0xFF) / 255f
            buffer.put(i, (r - 0.5f) / 0.5f)
        }
        // G channel
        for (i in 0 until channelSize) {
            val g = (pixels[i] shr 8 and 0xFF) / 255f
            buffer.put(channelSize + i, (g - 0.5f) / 0.5f)
        }
        // B channel
        for (i in 0 until channelSize) {
            val b = (pixels[i] and 0xFF) / 255f
            buffer.put(2 * channelSize + i, (b - 0.5f) / 0.5f)
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
