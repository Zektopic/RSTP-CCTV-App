package com.zektopic.cctvapp

/**
 * Which MediaCodec implementation the encoder should be built from.
 *
 * This replaces a single "Force Software Codec" boolean. That boolean could express
 * "software" and "whatever turns up first", but not "hardware, and fail loudly if there
 * isn't one" -- which is the case that matters when a device has a working hardware
 * encoder that RootEncoder's first-compatible search passes over for a software one.
 *
 * RootEncoder's `CodecUtil.CodecType` has exactly four values and this maps onto them
 * one to one. The mapping lives in [codecTypeName] rather than referencing CodecType
 * directly, so this file stays free of Android and library imports and the migration and
 * fallback rules can be unit tested on the JVM.
 */
enum class EncoderImplementation(val storedValue: String) {

    /**
     * `FIRST_COMPATIBLE_FOUND` -- the previous behaviour whenever the old switch was off.
     */
    AUTO("auto"),

    /** `HARDWARE` -- only a hardware-accelerated encoder will do. */
    HARDWARE("hardware"),

    /**
     * `SOFTWARE` -- what the old switch turned on.
     *
     * Slower and hotter, and it is the way out of a device whose hardware encoder
     * produces a corrupt or green stream.
     */
    SOFTWARE("software"),

    /**
     * `CBR_PRIORITY` -- prefer an encoder that can hold a constant bitrate.
     *
     * Worth having for a camera feeding an NVR over a fixed link, where a predictable
     * bitrate matters more than spending bits where the picture needs them.
     */
    CBR_PRIORITY("cbr");

    /** The `CodecUtil.CodecType` constant this corresponds to. */
    val codecTypeName: String
        get() = when (this) {
            AUTO -> "FIRST_COMPATIBLE_FOUND"
            HARDWARE -> "HARDWARE"
            SOFTWARE -> "SOFTWARE"
            CBR_PRIORITY -> "CBR_PRIORITY"
        }

    companion object {
        val DEFAULT = AUTO

        /** Parses a stored value, falling back to [DEFAULT] for anything unrecognised. */
        fun fromStored(value: String?): EncoderImplementation =
            entries.firstOrNull { it.storedValue == value } ?: DEFAULT

        /**
         * Translates the retired `force_software` boolean.
         *
         * Existing installs have only that flag, and reading it as `false -> AUTO`
         * rather than `false -> HARDWARE` is what keeps them streaming exactly as they
         * did before the setting gained a third and fourth option.
         */
        fun fromLegacyForceSoftware(forceSoftware: Boolean): EncoderImplementation =
            if (forceSoftware) SOFTWARE else AUTO
    }
}

/**
 * Which encoder implementations a device actually has for one codec.
 *
 * Probed from `MediaCodecList` at runtime rather than assumed from a model allowlist:
 * an allowlist cannot be verified for hardware nobody here owns and goes stale the
 * moment a vendor ships a firmware update.
 */
data class CodecSupport(
    val hardware: Boolean,
    val software: Boolean
) {
    /** Whether this codec can be encoded at all, by any implementation. */
    val available: Boolean get() = hardware || software

    /** Whether this codec can be encoded using [implementation]. */
    fun supports(implementation: EncoderImplementation): Boolean = when (implementation) {
        EncoderImplementation.HARDWARE -> hardware
        EncoderImplementation.SOFTWARE -> software
        // Both of these search across everything the device has and settle for what
        // they find, so either kind of encoder satisfies them.
        EncoderImplementation.AUTO, EncoderImplementation.CBR_PRIORITY -> available
    }

    companion object {
        val NONE = CodecSupport(hardware = false, software = false)
    }
}
