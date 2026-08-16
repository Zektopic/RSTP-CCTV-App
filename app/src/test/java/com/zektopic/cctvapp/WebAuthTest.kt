package com.zektopic.cctvapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Covers the dashboard's authentication gate. Before this existed, everything on
 * port 8080 -- the live camera, every setting, the stored credentials -- was reachable
 * by anyone on the network.
 */
class WebAuthTest {

    private fun basic(user: String, password: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())

    // --- parseBasicHeader ---

    @Test
    fun `parses a well formed basic header`() {
        val parsed = WebAuth.parseBasicHeader(basic("admin", "hunter2"))
        assertEquals(Pair("admin", "hunter2"), parsed)
    }

    @Test
    fun `scheme match is case insensitive`() {
        assertNotNull(WebAuth.parseBasicHeader("basic " + Base64.getEncoder().encodeToString("a:b".toByteArray())))
    }

    @Test
    fun `password may contain colons`() {
        val parsed = WebAuth.parseBasicHeader(basic("admin", "a:b:c"))
        assertEquals(Pair("admin", "a:b:c"), parsed)
    }

    @Test
    fun `empty password is preserved rather than dropped`() {
        assertEquals(Pair("admin", ""), WebAuth.parseBasicHeader(basic("admin", "")))
    }

    @Test
    fun `rejects malformed headers`() {
        assertNull(WebAuth.parseBasicHeader(null))
        assertNull(WebAuth.parseBasicHeader(""))
        assertNull(WebAuth.parseBasicHeader("Basic"))
        assertNull(WebAuth.parseBasicHeader("Basic "))
        assertNull(WebAuth.parseBasicHeader("Bearer abc123"))
        assertNull(WebAuth.parseBasicHeader("Basic !!!not-base64!!!"))
        // Valid base64, but no colon separator.
        assertNull(WebAuth.parseBasicHeader("Basic " + Base64.getEncoder().encodeToString("nocolon".toByteArray())))
    }

    // --- decodeBase64 ---
    // Hand-rolled because java.util.Base64 needs API 26 and this app supports API 24,
    // so it is checked against the JDK implementation rather than trusted.

    @Test
    fun `decodes the same bytes as the jdk implementation`() {
        val samples = listOf(
            "", "f", "fo", "foo", "foo:", "admin:hunter2",
            "user:p@ss:w0rd", "ünïcødé:päss", "a".repeat(100) + ":" + "b".repeat(37)
        )
        for (sample in samples) {
            val encoded = Base64.getEncoder().encodeToString(sample.toByteArray())
            val decoded = WebAuth.decodeBase64(encoded)
            if (sample.isEmpty()) continue // empty input is rejected by design
            assertEquals(sample, decoded?.toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `decodes every padding length`() {
        // 0, 1 and 2 padding characters respectively.
        assertEquals("abc", WebAuth.decodeBase64("YWJj")?.toString(Charsets.UTF_8))
        assertEquals("ab", WebAuth.decodeBase64("YWI=")?.toString(Charsets.UTF_8))
        assertEquals("a", WebAuth.decodeBase64("YQ==")?.toString(Charsets.UTF_8))
    }

    @Test
    fun `decodes bytes across the full range`() {
        val bytes = ByteArray(256) { it.toByte() }
        val encoded = Base64.getEncoder().encodeToString(bytes)
        org.junit.Assert.assertArrayEquals(bytes, WebAuth.decodeBase64(encoded))
    }

    @Test
    fun `rejects invalid base64`() {
        assertNull(WebAuth.decodeBase64(""))
        assertNull(WebAuth.decodeBase64("YWJ"))        // not a multiple of four
        assertNull(WebAuth.decodeBase64("YW J="))      // stray space
        assertNull(WebAuth.decodeBase64("YW*J"))       // character outside the alphabet
        assertNull(WebAuth.decodeBase64("Y=Wj"))       // padding in the middle
    }

    // --- constantTimeEquals ---

    @Test
    fun `constant time compare matches string equality`() {
        assertTrue(WebAuth.constantTimeEquals("secret", "secret"))
        assertTrue(WebAuth.constantTimeEquals("", ""))
        assertFalse(WebAuth.constantTimeEquals("secret", "secrew"))
        assertFalse(WebAuth.constantTimeEquals("secret", "secret "))
        assertFalse(WebAuth.constantTimeEquals("secret", ""))
    }

    @Test
    fun `constant time compare handles multi byte characters`() {
        assertTrue(WebAuth.constantTimeEquals("pässwörd", "pässwörd"))
        assertFalse(WebAuth.constantTimeEquals("pässwörd", "password"))
    }

    // --- isAuthorized ---

    @Test
    fun `correct credentials are accepted`() {
        assertTrue(WebAuth.isAuthorized(true, "admin", "s3cret", basic("admin", "s3cret")))
    }

    @Test
    fun `wrong password is rejected`() {
        assertFalse(WebAuth.isAuthorized(true, "admin", "s3cret", basic("admin", "wrong")))
    }

    @Test
    fun `wrong username is rejected`() {
        assertFalse(WebAuth.isAuthorized(true, "admin", "s3cret", basic("root", "s3cret")))
    }

    @Test
    fun `missing header is rejected when auth is on`() {
        assertFalse(WebAuth.isAuthorized(true, "admin", "s3cret", null))
    }

    @Test
    fun `everything is allowed when auth is disabled`() {
        assertTrue(WebAuth.isAuthorized(false, "admin", "s3cret", null))
    }

    @Test
    fun `auth cannot lock the user out when no credentials are configured`() {
        // Enabled but unconfigured must stay open, otherwise the owner has no way in.
        assertTrue(WebAuth.isAuthorized(true, "", "", null))
        assertTrue(WebAuth.isAuthorized(true, "admin", "", null))
        assertTrue(WebAuth.isAuthorized(true, "", "s3cret", null))
    }

    // --- isOriginAllowed (CSRF) ---

    @Test
    fun `requests without an origin are allowed`() {
        // curl, NVR software and scripts send no Origin header.
        assertTrue(WebAuth.isOriginAllowed(null, "192.168.1.5:8080"))
        assertTrue(WebAuth.isOriginAllowed("", "192.168.1.5:8080"))
        assertTrue(WebAuth.isOriginAllowed("null", "192.168.1.5:8080"))
    }

    @Test
    fun `same origin requests are allowed`() {
        assertTrue(WebAuth.isOriginAllowed("http://192.168.1.5:8080", "192.168.1.5:8080"))
        assertTrue(WebAuth.isOriginAllowed("http://192.168.1.5", "192.168.1.5:8080"))
    }

    @Test
    fun `cross origin requests are rejected`() {
        // The attack this blocks: a page on the internet driving a LAN camera.
        assertFalse(WebAuth.isOriginAllowed("http://evil.example.com", "192.168.1.5:8080"))
        assertFalse(WebAuth.isOriginAllowed("https://evil.example.com:8080", "192.168.1.5:8080"))
        assertFalse(WebAuth.isOriginAllowed("http://192.168.1.6:8080", "192.168.1.5:8080"))
    }

    @Test
    fun `origin that merely contains the host is rejected`() {
        assertFalse(WebAuth.isOriginAllowed("http://192.168.1.5.evil.com", "192.168.1.5:8080"))
        assertFalse(WebAuth.isOriginAllowed("http://evil.com/192.168.1.5", "192.168.1.5:8080"))
    }

    @Test
    fun `missing host header rejects a cross origin request`() {
        assertFalse(WebAuth.isOriginAllowed("http://evil.example.com", null))
    }

    // --- escapeHtml (XSS) ---

    @Test
    fun `escapes html metacharacters`() {
        assertEquals(
            "&lt;script&gt;alert(1)&lt;/script&gt;",
            WebAuth.escapeHtml("<script>alert(1)</script>")
        )
        assertEquals("&quot;&#39;&amp;", WebAuth.escapeHtml("\"'&"))
    }

    @Test
    fun `escapes the attribute breakout used by the rtsp url injection`() {
        // This is the exact payload that reached the dashboard through /action/set-auth.
        val escaped = WebAuth.escapeHtml("\"><script>alert(1)</script>")
        assertFalse(escaped.contains("<script"))
        assertFalse(escaped.contains("\""))
    }

    @Test
    fun `leaves ordinary text untouched`() {
        assertEquals("rtsp://192.168.1.5:8554/stream", WebAuth.escapeHtml("rtsp://192.168.1.5:8554/stream"))
    }

    // --- generatePassword ---

    @Test
    fun `generated passwords have the requested length and vary`() {
        val first = WebAuth.generatePassword(16)
        val second = WebAuth.generatePassword(16)
        assertEquals(16, first.length)
        assertEquals(16, second.length)
        assertFalse("two generated passwords should not collide", first == second)
    }

    @Test
    fun `generated passwords avoid ambiguous characters`() {
        val generated = (1..40).joinToString("") { WebAuth.generatePassword(16) }
        for (ambiguous in listOf('0', 'O', '1', 'l', 'I')) {
            assertFalse("must not contain '$ambiguous'", generated.contains(ambiguous))
        }
    }
}
