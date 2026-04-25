package com.benjaminwan.ocrlibrary

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import android.util.Log
import com.benjaminwan.ocrlibrary.models.RecResult
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc.resize
import java.util.*

class Rec(private val ortEnv: OrtEnvironment, assetManager: AssetManager, modelName: String, keysName: String, private val sessionOptions: OrtSession.SessionOptions) {

    private val session by lazy {
        val model = assetManager.open(modelName, AssetManager.ACCESS_UNKNOWN).readBytes()
        ortEnv.createSession(model, sessionOptions)
    }

    private val keys by lazy {
        val reader = assetManager.open(keysName, AssetManager.ACCESS_UNKNOWN).bufferedReader()
        reader.lineSequence().toMutableList().apply {
            add(0, "#")
            add(" ")
        }.toList()
    }

    private fun scoreToTextLine(outputData: List<FloatArray>): RecResult {
        val sb = StringBuilder()
        val scores: MutableList<Float> = mutableListOf()
        var lastIndex = 0
        outputData.forEach {
            val max = it.withIndex().maxBy { it.value }
            if (max.index in 1 until keys.size && max.index != lastIndex) {
                sb.append(keys[max.index])
                scores.add(max.value)
            }
            lastIndex = max.index
        }
        return RecResult(sb.toString(), scores)
    }

    fun getRecResult(src: Mat): RecResult {
        // Reject degenerate crops; an empty tensor crashes libonnxruntime.
        if (src.cols() < 4 || src.rows() < 4 || src.channels() != 3) {
            Log.w(TAG, "skip rec: degenerate input ${src.cols()}x${src.rows()} ch=${src.channels()}")
            return RecResult("", emptyList())
        }

        val scale = dstHeight / src.rows().toDouble()
        val dstWidth = (src.cols() * scale).toInt().coerceAtLeast(MIN_REC_WIDTH)
        val srcResize = Mat()
        resize(src, srcResize, Size(dstWidth.toDouble(), dstHeight))
        val inputTensorValues = substractMeanNormalize(srcResize, meanValues, normValues)
        val inputShape = longArrayOf(1, srcResize.channels().toLong(), srcResize.rows().toLong(), srcResize.cols().toLong())
        val inputName = session.inputNames.iterator().next()
        OnnxTensor.createTensor(ortEnv, inputTensorValues, inputShape).use { inputTensor ->
            session.run(Collections.singletonMap(inputName, inputTensor)).use { output ->
                val onnxValue = output.first().value
                val values = onnxValue.value as Array<Array<FloatArray>>
                val outputData = values.flatMap { a -> a.flatMap { b -> listOf(b) } }
                return scoreToTextLine(outputData)
            }
        }
    }

    fun getRecResults(mats: List<Mat>): List<RecResult> = mats.map { getRecResult(it) }

    companion object {
        private const val TAG = "Rec"
        private const val dstHeight = 48.0
        private const val MIN_REC_WIDTH = 16
        private val meanValues = floatArrayOf(127.5F, 127.5F, 127.5F)
        private val normValues = floatArrayOf(1.0F / 127.5F, 1.0F / 127.5F, 1.0F / 127.5F)
    }

}
