package com.zektopic.cctvapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the encoder probe against real hardware.
 *
 * [CodecSupportTest]'s JVM cousin can only check the shape of the result; whether
 * `MediaCodecList` answers sensibly is a question only a device can settle.
 *
 * Observed on a Galaxy M21 (SM-M215F, Exynos 9611, Android 11):
 *
 *     H264  hardware=true  software=true
 *     H265  hardware=true  software=true
 *     AV1   hardware=false software=false
 *
 * which is the case this whole probe exists for: the codec picker offered AV1 on that
 * device, and choosing it silently fell back to H.264 with nothing to say why.
 */
@RunWith(AndroidJUnit4::class)
class CodecCapabilitiesInstrumentedTest {

    @Test
    fun everyKnownCodecIsProbedWithoutThrowing() {
        val support = CodecCapabilities.probe()
        android.util.Log.i("CodecProbe", "DEVICE=${android.os.Build.MODEL} API=${android.os.Build.VERSION.SDK_INT}")
        support.forEach { (codec, s) ->
            android.util.Log.i("CodecProbe", "RESULT $codec hardware=${s.hardware} software=${s.software}")
        }
        assertTrue(support.keys.containsAll(CodecCapabilities.CODECS))
    }

    @Test
    fun h264CanBeEncoded() {
        // Every Android device that ships a camera is required to have an H.264
        // encoder. If the probe cannot find one, the probe is wrong -- not the device.
        val h264 = CodecCapabilities.support("H264")
        android.util.Log.i("CodecProbe", "H264 -> $h264")
        assertTrue("No H.264 encoder found; the probe is not reading MediaCodecList correctly", h264.available)
    }

    @Test
    fun theProbeAgreesWithItself() {
        // probe() and support() must not disagree -- the UI uses one and the service
        // the other, and a mismatch would show a codec as available while the stream
        // refused it.
        val probed = CodecCapabilities.probe()
        CodecCapabilities.CODECS.forEach {
            org.junit.Assert.assertEquals(probed[it], CodecCapabilities.support(it))
        }
    }
}
