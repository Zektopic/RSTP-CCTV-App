package com.zektopic.cctvapp

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.imagedescription.ImageDescriber
import com.google.mlkit.genai.imagedescription.ImageDescription
import com.google.mlkit.genai.imagedescription.ImageDescriberOptions
import com.google.mlkit.genai.imagedescription.ImageDescriptionRequest
import java.util.concurrent.TimeUnit

/**
 * Turns an event snapshot into a one-line description ("a person standing near a car")
 * using Gemini Nano through ML Kit's GenAI APIs.
 *
 * This is strictly a garnish on top of detection, never a replacement for it: Gemini Nano
 * returns prose, not labels and scores, so it cannot decide whether an event fired. It only
 * makes an event that already fired readable.
 *
 * Availability is narrow and this class is built to be invisible when the feature is
 * missing. Three independent things must hold, and any of them failing yields null:
 *  - API 26+. The ML Kit GenAI libraries declare minSdk 26 while this app supports 24.
 *  - AICore present on the device. It ships on a limited set of hardware.
 *  - The feature actually downloaded. Model delivery is handled by AICore, not by us.
 *
 * Nothing here is on the critical path. Callers treat a null caption as "no caption".
 */
class EventCaptioner(private val context: Context) {

    @Volatile
    private var unavailable = false

    @Volatile
    private var describer: ImageDescriber? = null

    /** Cheap pre-check so callers can skip work entirely on unsupported devices. */
    fun isPossiblySupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !unavailable

    /**
     * Describes [bitmap], or returns null if captioning is unavailable or slow.
     *
     * Blocking by design: this runs on the detection worker thread, which is already off
     * the main thread, and a bounded wait is simpler to reason about than another
     * callback hop. The timeout matters -- inference latency depends on device hardware,
     * and an event should be stored promptly whether or not a caption arrives.
     */
    fun caption(bitmap: Bitmap): String? {
        // The SDK_INT check is repeated inline rather than left to isPossiblySupported():
        // lint does not follow it across a function boundary, and this is precisely the
        // guard that keeps API 24 and 25 devices away from a minSdk-26 library.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        if (unavailable) return null
        return try {
            captionInternal(bitmap)
        } catch (t: Throwable) {
            // Throwable: a missing AICore raises errors that are not Exceptions, and a
            // caption is never worth taking down the detection loop for.
            markUnavailable("Image captioning failed", t)
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun captionInternal(bitmap: Bitmap): String? {
        val client = describer ?: ImageDescription
            .getClient(ImageDescriberOptions.builder(context).build())
            .also { describer = it }

        val status = client.checkFeatureStatus().get(STATUS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (status != FeatureStatus.AVAILABLE) {
            // DOWNLOADABLE/DOWNLOADING are not treated as failures worth latching off --
            // AICore may finish fetching the model later. UNAVAILABLE means this device
            // will never support it, so stop asking.
            if (status == FeatureStatus.UNAVAILABLE) {
                markUnavailable("Gemini Nano is not available on this device", null)
            }
            return null
        }

        val request = ImageDescriptionRequest.builder(bitmap).build()
        val result = client.runInference(request)
            .get(INFERENCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return result.description.trim().ifEmpty { null }
    }

    private fun markUnavailable(message: String, cause: Throwable?) {
        unavailable = true
        Log.i(TAG, "$message. Event captions are disabled.", cause)
        close()
    }

    fun close() {
        runCatching { describer?.close() }
        describer = null
    }

    private companion object {
        const val TAG = "EventCaptioner"
        const val STATUS_TIMEOUT_SECONDS = 5L
        const val INFERENCE_TIMEOUT_SECONDS = 20L
    }
}
