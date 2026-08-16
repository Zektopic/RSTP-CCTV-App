package com.zektopic.cctvapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EventStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun store(
        retentionMs: Long = EventStore.DEFAULT_RETENTION_MS,
        maxEvents: Int = EventStore.DEFAULT_MAX_EVENTS
    ) = EventStore(tempFolder.root, retentionMs, maxEvents)

    private fun metadataFile() = File(File(tempFolder.root, "events"), "events.json")
    private fun mediaDir() = File(File(tempFolder.root, "events"), "media")

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x01)

    @Test
    fun `creates its directories and an empty index`() {
        store()
        assertTrue(metadataFile().exists())
        assertEquals("[]", metadataFile().readText())
    }

    @Test
    fun `stores an event and its snapshot`() {
        val eventStore = store()
        val event = eventStore.createDetectionEvent("motion", 0.42, jpeg)

        assertEquals("motion", event.type)
        assertEquals(0.42, event.score!!, 1e-9)
        assertNotNull(event.snapshotFileName)

        val snapshot = eventStore.getEventSnapshotFile(event.id)
        assertNotNull(snapshot)
        assertArrayEquals(jpeg, snapshot!!.readBytes())
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        org.junit.Assert.assertArrayEquals(expected, actual)
    }

    @Test
    fun `event without a snapshot records no file`() {
        val eventStore = store()
        val event = eventStore.createDetectionEvent("motion", null, null)

        assertNull(event.snapshotFileName)
        assertNull(eventStore.getEventSnapshotFile(event.id))
    }

    @Test
    fun `empty snapshot byte array is treated as absent`() {
        val eventStore = store()
        val event = eventStore.createDetectionEvent("motion", 0.1, ByteArray(0))
        assertNull(event.snapshotFileName)
    }

    @Test
    fun `unknown ids resolve to null rather than throwing`() {
        val eventStore = store()
        assertNull(eventStore.getEventAsJson("does-not-exist"))
        assertNull(eventStore.getEventSnapshotFile("does-not-exist"))
        assertNull(eventStore.getEventClipFile("does-not-exist"))
    }

    @Test
    fun `events are listed newest first`() {
        val eventStore = store()
        val first = eventStore.createDetectionEvent("motion", 0.1, null)
        Thread.sleep(5)
        val second = eventStore.createDetectionEvent("person", 0.9, null)

        val listed = eventStore.listRecentEvents(10)
        assertEquals(listOf(second.id, first.id), listed.map { it.id })
    }

    @Test
    fun `listing respects the limit`() {
        val eventStore = store()
        repeat(5) { eventStore.createDetectionEvent("motion", 0.5, null) }
        assertEquals(3, eventStore.listRecentEvents(3).size)
    }

    @Test
    fun `cleanup removes events past the retention window and their media`() {
        // One hour of retention, and an event stamped two hours ago.
        val eventStore = store(retentionMs = 60 * 60 * 1000L)
        val stale = eventStore.createDetectionEvent("motion", 0.5, jpeg)
        val staleSnapshot = File(mediaDir(), stale.snapshotFileName!!)
        assertTrue(staleSnapshot.exists())

        val removed = eventStore.cleanupExpired(
            nowMs = System.currentTimeMillis() + 2 * 60 * 60 * 1000L
        )

        assertEquals(1, removed)
        assertTrue(eventStore.listRecentEvents(10).isEmpty())
        assertFalse("snapshot should be deleted with its event", staleSnapshot.exists())
    }

    @Test
    fun `cleanup keeps events inside the retention window`() {
        val eventStore = store(retentionMs = 60 * 60 * 1000L)
        eventStore.createDetectionEvent("motion", 0.5, null)

        assertEquals(0, eventStore.cleanupExpired())
        assertEquals(1, eventStore.listRecentEvents(10).size)
    }

    @Test
    fun `the event count is capped and the oldest are dropped`() {
        // Time-based retention alone cannot stop a busy camera filling the device.
        val eventStore = store(maxEvents = 3)
        val ids = (1..6).map {
            Thread.sleep(2)
            eventStore.createDetectionEvent("motion", 0.5, null).id
        }

        val remaining = eventStore.listRecentEvents(50).map { it.id }
        assertEquals(3, remaining.size)
        assertEquals(ids.takeLast(3).reversed(), remaining)
    }

    @Test
    fun `capping deletes the media of dropped events`() {
        val eventStore = store(maxEvents = 2)
        val first = eventStore.createDetectionEvent("motion", 0.5, jpeg)
        val firstSnapshot = File(mediaDir(), first.snapshotFileName!!)
        Thread.sleep(2)
        eventStore.createDetectionEvent("motion", 0.5, jpeg)
        Thread.sleep(2)
        eventStore.createDetectionEvent("motion", 0.5, jpeg)

        assertFalse("dropped event's snapshot should be deleted", firstSnapshot.exists())
    }

    @Test
    fun `a corrupt index degrades to empty instead of throwing`() {
        val eventStore = store()
        eventStore.createDetectionEvent("motion", 0.5, null)

        // Simulates the truncation that an in-place write left behind on a process kill.
        metadataFile().writeText("[{\"id\":\"broken\"")

        assertTrue(eventStore.listRecentEvents(10).isEmpty())
        // And the store must still be writable afterwards.
        val recovered = eventStore.createDetectionEvent("person", 0.8, null)
        assertEquals(listOf(recovered.id), eventStore.listRecentEvents(10).map { it.id })
    }

    @Test
    fun `malformed entries are skipped without discarding valid ones`() {
        val eventStore = store()
        val good = eventStore.createDetectionEvent("motion", 0.5, null)

        val text = metadataFile().readText()
        metadataFile().writeText(text.replaceFirst("[", "[{\"no_id\":true},"))

        val listed = eventStore.listRecentEvents(10)
        assertEquals(listOf(good.id), listed.map { it.id })
    }

    @Test
    fun `writes leave no temporary file behind`() {
        val eventStore = store()
        eventStore.createDetectionEvent("motion", 0.5, null)
        assertFalse(File(File(tempFolder.root, "events"), "events.json.tmp").exists())
    }

    @Test
    fun `listEventsAsJson reports a count and honours since`() {
        val eventStore = store()
        eventStore.createDetectionEvent("motion", 0.5, null)
        Thread.sleep(5)
        val cutoff = System.currentTimeMillis()
        Thread.sleep(5)
        eventStore.createDetectionEvent("person", 0.7, null)

        val all = org.json.JSONObject(eventStore.listEventsAsJson(null, 100))
        assertEquals(2, all.getInt("count"))

        val recent = org.json.JSONObject(eventStore.listEventsAsJson(cutoff, 100))
        assertEquals(1, recent.getInt("count"))
        assertEquals("person", recent.getJSONArray("events").getJSONObject(0).getString("type"))
    }

    @Test
    fun `a test event is stored like any other`() {
        val eventStore = store()
        val event = eventStore.createTestEvent(jpeg)
        assertEquals("test", event.type)
        assertNotNull(eventStore.getEventSnapshotFile(event.id))
    }
}
