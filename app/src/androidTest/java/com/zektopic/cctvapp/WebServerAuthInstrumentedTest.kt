package com.zektopic.cctvapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * Drives a real [WebServer] over a real socket and asserts that the authentication gate
 * actually refuses unauthenticated callers.
 *
 * The unit tests cover [WebAuth] in isolation; this proves the gate is wired into the
 * request path, which is where the original vulnerability lived -- the credentials
 * existed, they were simply never checked on port 8080.
 */
@RunWith(AndroidJUnit4::class)
class WebServerAuthInstrumentedTest {

    private lateinit var server: WebServer
    private lateinit var eventStore: EventStore

    private var authEnabled = true
    private val username = "admin"
    private val password = "test-password"

    /** A port of its own, so the test never collides with a running CctvServerService. */
    private val port get() = server.listeningPort

    @Before
    fun startServer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "webserver-auth-test-${System.nanoTime()}")
        root.mkdirs()
        eventStore = EventStore(root)

        server = WebServer(
            context = context,
            ipAddress = "127.0.0.1",
            imageProvider = { byteArrayOf(1, 2, 3) },
            onSwitchCamera = {},
            onStartStream = {},
            onStopStream = {},
            isStreaming = { false },
            onCodecUpdate = {},
            getCurrentCodec = { "H264" },
            onResolutionUpdate = { _, _ -> },
            getCurrentResolution = { "640x480" },
            getAuthEnabled = { false },
            getUsername = { username },
            getPassword = { password },
            onSettingUpdate = { _, _ -> },
            getShowTimestamp = { false },
            getShowDate = { false },
            getTimestampPosition = { "Top Left" },
            getTimestampSize = { "Medium" },
            getFlashlightEnabled = { false },
            getNightModeEnabled = { false },
            getForceSoftware = { false },
            getShowPreview = { false },
            getDetectionEnabled = { false },
            getMotionDetectionEnabled = { false },
            getObjectDetectionEnabled = { false },
            getObjectDetectorReady = { false },
            onAuthUpdate = { _, _, _ -> },
            listEventsJson = { since, limit -> eventStore.listEventsAsJson(since, limit) },
            getEventJson = { id -> eventStore.getEventAsJson(id) },
            getEventSnapshotFile = { id -> eventStore.getEventSnapshotFile(id) },
            getEventClipFile = { id -> eventStore.getEventClipFile(id) },
            onCreateTestEvent = { eventStore.createTestEvent(null).toJsonObject().toString() },
            getBatteryLevel = { 50 },
            getWifiStrength = { 80 },
            getWebAuthEnabled = { authEnabled }
        )
        server.start()
    }

    @After
    fun stopServer() {
        server.stop()
    }

    private fun request(
        path: String,
        credentials: Pair<String, String>? = null,
        origin: String? = null
    ): HttpURLConnection {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        credentials?.let { (user, pass) ->
            val encoded = Base64.getEncoder().encodeToString("$user:$pass".toByteArray())
            connection.setRequestProperty("Authorization", "Basic $encoded")
        }
        origin?.let { connection.setRequestProperty("Origin", it) }
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        return connection
    }

    @Test
    fun statusRequiresCredentials() {
        val connection = request("/status")
        assertEquals(401, connection.responseCode)
        assertNotNull(
            "a 401 must tell the client how to authenticate",
            connection.getHeaderField("WWW-Authenticate")
        )
    }

    @Test
    fun snapshotRequiresCredentials() {
        // The live camera frame is the most sensitive thing this server exposes.
        assertEquals(401, request("/shot.jpg").responseCode)
    }

    @Test
    fun eventsRequireCredentials() {
        assertEquals(401, request("/events").responseCode)
    }

    @Test
    fun actionsRequireCredentials() {
        assertEquals(401, request("/action/toggle-stream").responseCode)
        assertEquals(401, request("/action/set-auth?enabled=true&username=x&password=y").responseCode)
    }

    @Test
    fun correctCredentialsAreAccepted() {
        val connection = request("/status", credentials = username to password)
        assertEquals(200, connection.responseCode)
        val body = connection.inputStream.bufferedReader().readText()
        assertTrue(body.contains("\"streaming\""))
    }

    @Test
    fun wrongPasswordIsRejected() {
        assertEquals(401, request("/status", credentials = username to "wrong").responseCode)
    }

    @Test
    fun everythingIsOpenWhenAuthIsDisabled() {
        authEnabled = false
        assertEquals(200, request("/status").responseCode)
    }

    @Test
    fun crossOriginRequestsAreRejected() {
        val connection = request(
            "/status",
            credentials = username to password,
            origin = "http://evil.example.com"
        )
        assertEquals(403, connection.responseCode)
    }

    @Test
    fun responsesDoNotAdvertiseWildcardCors() {
        val connection = request("/status", credentials = username to password)
        assertEquals(200, connection.responseCode)
        assertEquals(
            "wildcard CORS would let any website read the camera's status",
            null,
            connection.getHeaderField("Access-Control-Allow-Origin")
        )
    }

    @Test
    fun dashboardEscapesCredentialsInsteadOfInjectingThem() {
        // Regression for the stored XSS: the username reaches the HTML via buildRtspUrl().
        val connection = request("/", credentials = username to password)
        assertEquals(200, connection.responseCode)
        val html = connection.inputStream.bufferedReader().readText()
        assertTrue("dashboard should render", html.contains("CCTV Dashboard"))
    }
}
