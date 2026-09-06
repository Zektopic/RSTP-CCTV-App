package com.zektopic.cctvapp

import com.pedro.encoder.utils.CodecUtil

/**
 * Asks the device which video encoders it actually has.
 *
 * The codec picker previously offered H.264, H.265 and AV1 on every device. On hardware
 * with no AV1 encoder -- which is most of it -- choosing AV1 meant `prepareVideo`
 * failing and the service quietly falling back to H.264, with nothing in the UI to say
 * why the setting appeared not to stick.
 *
 * This is deliberately a runtime probe rather than a table of device models. A model
 * allowlist cannot be verified for hardware nobody on the project owns, and it goes
 * stale as soon as a vendor ships new firmware. `MediaCodecList` is the device telling
 * us about itself, and it is right by construction.
 */
object CodecCapabilities {

    /** Codec names as stored in preferences, in the order the pickers show them. */
    val CODECS = listOf("H264", "H265", "AV1")

    private fun mimeFor(codec: String): String? = when (codec.uppercase()) {
        "H264" -> CodecUtil.H264_MIME
        "H265" -> CodecUtil.H265_MIME
        "AV1" -> CodecUtil.AV1_MIME
        else -> null
    }

    /**
     * What this device can encode, keyed by the names in [CODECS].
     *
     * Every lookup is wrapped: `MediaCodecList` is one of the more reliably broken
     * corners of the platform on cheap hardware, and a camera server must not fail to
     * start because it could not enumerate codecs. An unreadable device reports
     * [CodecSupport.NONE], which the UI renders as "unknown" rather than blocking a
     * codec the device may well handle.
     */
    fun probe(): Map<String, CodecSupport> = CODECS.associateWith { support(it) }

    fun support(codec: String): CodecSupport {
        val mime = mimeFor(codec) ?: return CodecSupport.NONE
        return try {
            CodecSupport(
                hardware = CodecUtil.getAllHardwareEncoders(mime).isNotEmpty(),
                software = CodecUtil.getAllSoftwareEncoders(mime).isNotEmpty()
            )
        } catch (t: Throwable) {
            android.util.Log.w("CodecCapabilities", "Could not enumerate encoders for $codec", t)
            CodecSupport.NONE
        }
    }

    /** Renders [probe] as the JSON object /status embeds. */
    fun toJson(support: Map<String, CodecSupport>): String =
        support.entries.joinToString(prefix = "{", postfix = "}") { (codec, s) ->
            """"$codec":{"hardware":${s.hardware},"software":${s.software}}"""
        }
}
