package com.zektopic.cctvapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionEventTest {

    private fun event(
        score: Double? = 0.75,
        endTimeMs: Long? = 2_000L,
        snapshotFileName: String? = "abc_snapshot.jpg",
        clipFileName: String? = null
    ) = DetectionEvent(
        id = "abc",
        type = "person",
        score = score,
        startTimeMs = 1_000L,
        endTimeMs = endTimeMs,
        snapshotFileName = snapshotFileName,
        clipFileName = clipFileName,
        createdAtMs = 3_000L
    )

    @Test
    fun `round trips through json`() {
        val original = event()
        val restored = DetectionEvent.fromJsonObject(original.toJsonObject())
        assertEquals(original, restored)
    }

    @Test
    fun `round trips with every optional field absent`() {
        val original = event(score = null, endTimeMs = null, snapshotFileName = null)
        val restored = DetectionEvent.fromJsonObject(original.toJsonObject())

        assertEquals(original, restored)
        assertNull(restored.score)
        assertNull(restored.endTimeMs)
        assertNull(restored.snapshotFileName)
    }

    @Test
    fun `round trips with a clip`() {
        val original = event(clipFileName = "abc_clip.mp4")
        val restored = DetectionEvent.fromJsonObject(original.toJsonObject())
        assertEquals("abc_clip.mp4", restored.clipFileName)
        assertTrue(original.toJsonObject().getBoolean("has_clip"))
    }

    @Test
    fun `media flags reflect what is present`() {
        val json = event(snapshotFileName = "s.jpg", clipFileName = null).toJsonObject()
        assertTrue(json.getBoolean("has_snapshot"))
        assertFalse(json.getBoolean("has_clip"))
    }

    @Test
    fun `omits optional keys entirely rather than writing null`() {
        val json = event(score = null, endTimeMs = null, snapshotFileName = null).toJsonObject()
        assertFalse(json.has("score"))
        assertFalse(json.has("end_time"))
        assertFalse(json.has("snapshot"))
    }

    @Test
    fun `an entry missing its type falls back to unknown`() {
        val json = JSONObject().put("id", "x").put("start_time", 1L)
        assertEquals("unknown", DetectionEvent.fromJsonObject(json).type)
    }

    @Test(expected = org.json.JSONException::class)
    fun `an entry without an id is rejected`() {
        // EventStore relies on this to skip corrupt rows rather than serving a bad id.
        DetectionEvent.fromJsonObject(JSONObject().put("type", "motion"))
    }
}
