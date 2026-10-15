package com.thelightphone.backup

import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Offline tests for WebDavRemoteBackup's own logic - see WebDav*ContractTest for the server-backed ones.
class WebDavRemoteBackupTest {
    @Test
    fun webDavUrl_appendsSegmentsUnderTheBasePath() {
        val base = Url("https://cloud.example.com/remote.php/dav/files/alice/")
        assertEquals(
            "https://cloud.example.com/remote.php/dav/files/alice/Backups/2026-09-04T00-00-00Z/",
            webDavUrl(base, listOf("Backups", "2026-09-04T00-00-00Z"), trailingSlash = true).toString(),
        )
        assertEquals(
            "https://cloud.example.com/remote.php/dav/files/alice/Backups/a.txt",
            webDavUrl(base, listOf("Backups", "a.txt"), trailingSlash = false).toString(),
        )
    }

    @Test
    fun webDavUrl_sameResultWithOrWithoutTrailingSlashOnBase() {
        assertEquals(
            webDavUrl(Url("https://nas.local/dav/"), listOf("x"), trailingSlash = true),
            webDavUrl(Url("https://nas.local/dav"), listOf("x"), trailingSlash = true),
        )
        assertEquals("https://nas.local/x/", webDavUrl(Url("https://nas.local"), listOf("x"), trailingSlash = true).toString())
        assertEquals("https://nas.local/dav/", webDavUrl(Url("https://nas.local/dav"), emptyList(), trailingSlash = true).toString())
    }

    // dav4jvm decides which PROPFIND entries are members of a collection by comparing rawSegments
    // with the hrefs it parses, so ours must look exactly like a parsed Url's.
    @Test
    fun webDavUrl_hasTheSameRawSegmentsAsTheEquivalentParsedUrl() {
        val built = webDavUrl(Url("http://127.0.0.1:8099/dav"), listOf("light-backup-tests"), trailingSlash = true)
        assertEquals(Url("http://127.0.0.1:8099/dav/light-backup-tests/").rawSegments, built.rawSegments)
    }

    @Test
    fun webDavUrl_encodesEachSegmentAsASingleSegment() {
        val url = webDavUrl(Url("https://nas.local/dav/"), listOf("a b", "c/d", "..", "é?#"), trailingSlash = false)
        assertEquals(listOf("dav", "a b", "c/d", "..", "é?#"), url.segments)
        assertEquals("https://nas.local/dav/a%20b/c%2Fd/../%C3%A9%3F%23", url.toString())
    }

    @Test
    fun constructor_rejectsPlainHttpUnlessAllowed() {
        val credentials = WebDavCredentials.Basic("u", "p")
        assertFailsWith<IllegalArgumentException> { WebDavRemoteBackup("http://nas.local/dav", credentials, "root") }
        assertFailsWith<IllegalArgumentException> { WebDavRemoteBackup("ftp://nas.local/dav", credentials, "root") }
        assertFailsWith<IllegalArgumentException> { WebDavRemoteBackup("nas.local", credentials, "root") }
        WebDavRemoteBackup("http://nas.local/dav", credentials, "root", allowInsecureHttp = true)
        WebDavRemoteBackup("https://nas.local", credentials, "root")
    }
}
