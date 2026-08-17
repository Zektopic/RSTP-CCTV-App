package com.zektopic.cctvapp

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

data class LiteRtDetection(
    val label: String,
    val score: Float
)

/**
 * On-device object detection over MediaPipe Tasks.
 *
 * Previously backed by `org.tensorflow:tensorflow-lite-task-vision`, which was frozen at
 * 0.4.4 in July 2023 and whose `libtask_vision_jni.so` has 4 KB LOAD segment alignment.
 * That fails Android's 16 KB page-size check -- on a 16 KB device the library does not
 * load at all. MediaPipe Tasks is the maintained successor and ships 16 KB-aligned
 * natives (verified with `llvm-readelf -l`: LOAD align 0x4000).
 *
 * The model is still a plain TFLite file in `assets/`, so the setup instructions are
 * unchanged -- it must carry TFLite Metadata, which the usual COCO SSD builds do.
 */
class LiteRtObjectDetector(
    private val context: Context,
    private val modelAssetName: String = "detect.tflite"
) {
    private var detector: ObjectDetector? = null

    /**
     * Set once loading has failed, so a missing or broken model costs one failed attempt
     * instead of an exception on every captured frame (this runs at up to 2 FPS).
     */
    @Volatile
    private var initializationFailed = false

    fun ensureInitialized(): Boolean {
        if (detector != null) return true
        if (initializationFailed) return false

        return try {
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder().setModelAssetPath(modelAssetName).build()
                )
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(5)
                .setScoreThreshold(0.45f)
                .build()
            detector = ObjectDetector.createFromOptions(context, options)
            true
        } catch (t: Throwable) {
            // Throwable, not Exception: a native library that is missing or wrongly
            // aligned raises UnsatisfiedLinkError, which is an Error. Catching only
            // Exception let that propagate and take the whole service down instead of
            // disabling object detection, which is the entire point of this latch.
            initializationFailed = true
            Log.w(
                TAG,
                "Could not initialize the detection model '$modelAssetName'. " +
                    "Place a COCO model in app/src/main/assets. Object detection is " +
                    "disabled until the app restarts.",
                t
            )
            false
        }
    }

    /** Clears the failure latch so a newly added model can be picked up without a reinstall. */
    fun retryInitialization() {
        initializationFailed = false
    }

    fun detect(bitmap: Bitmap): List<LiteRtDetection> {
        val ready = ensureInitialized()
        if (!ready) return emptyList()

        val localDetector = detector ?: return emptyList()
        return try {
            val image = BitmapImageBuilder(bitmap).build()
            localDetector.detect(image).detections().flatMap { detection ->
                detection.categories().map { category ->
                    LiteRtDetection(
                        label = category.categoryName().orEmpty().lowercase(),
                        score = category.score()
                    )
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Object detection failed", t)
            emptyList()
        }
    }

    fun isReady(): Boolean = detector != null

    /** Releases the native detector. Safe to call when initialization never succeeded. */
    fun close() {
        runCatching { detector?.close() }
        detector = null
    }

    private companion object {
        const val TAG = "ObjectDetector"
    }
}
