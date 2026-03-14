package com.zektopic.cctvapp

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.ObjectDetector

data class LiteRtDetection(
    val label: String,
    val score: Float
)

class LiteRtObjectDetector(
    private val context: Context,
    private val modelAssetName: String = "detect.tflite"
) {
    private var detector: ObjectDetector? = null

    fun ensureInitialized(): Boolean {
        if (detector != null) return true
        return try {
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setMaxResults(5)
                .setScoreThreshold(0.45f)
                .build()
            detector = ObjectDetector.createFromFileAndOptions(context, modelAssetName, options)
            true
        } catch (e: Exception) {
            Log.w("LiteRtObjectDetector", "Could not initialize LiteRT model '$modelAssetName'. Place a COCO model in app/src/main/assets.", e)
            false
        }
    }

    fun detect(bitmap: Bitmap): List<LiteRtDetection> {
        val ready = ensureInitialized()
        if (!ready) return emptyList()

        val localDetector = detector ?: return emptyList()
        return try {
            val tensorImage = TensorImage.fromBitmap(bitmap)
            localDetector.detect(tensorImage).flatMap { detection ->
                detection.categories.map { category ->
                    LiteRtDetection(
                        label = category.label.orEmpty().lowercase(),
                        score = category.score
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("LiteRtObjectDetector", "Object detection failed", e)
            emptyList()
        }
    }

    fun isReady(): Boolean = detector != null
}
