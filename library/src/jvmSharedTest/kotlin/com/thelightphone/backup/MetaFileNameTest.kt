package com.thelightphone.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Instant

// metaFileNameFor/parseMetaFileName are what let getMostRecentBackupDates() determine a path's
// last-backup time from a directory listing alone (see RemoteBackup.kt) - no real cloud account
// needed to verify the encode/decode round-trip and the sortability it depends on.
class MetaFileNameTest {
    @Test
    fun metaFileNameFor_roundTripsThroughParseMetaFileName() {
        val instant = Instant.parse("2026-09-12T10:15:30Z")

        val name = metaFileNameFor(instant)

        assertEquals(instant, parseMetaFileName(name))
    }

    @Test
    fun metaFileNameFor_truncatesToWholeSeconds() {
        val instant = Instant.parse("2026-09-12T10:15:30.987654321Z")

        val name = metaFileNameFor(instant)

        assertEquals(Instant.parse("2026-09-12T10:15:30Z"), parseMetaFileName(name))
    }

    @Test
    fun metaFileNameFor_containsNoColons() {
        val name = metaFileNameFor(Instant.parse("2026-09-12T10:15:30Z"))

        assertEquals(-1, name.indexOf(':'), "expected no colons in $name - not every provider allows them in filenames")
    }

    @Test
    fun metaFileNameFor_sortsLexicographicallyInChronologicalOrder() {
        val earlier = metaFileNameFor(Instant.parse("2026-09-12T10:15:30Z"))
        val later = metaFileNameFor(Instant.parse("2026-09-12T10:15:31Z"))
        val muchLater = metaFileNameFor(Instant.parse("2026-09-13T00:00:00Z"))

        val sorted = listOf(later, muchLater, earlier).sorted()

        assertEquals(listOf(earlier, later, muchLater), sorted)
    }

    @Test
    fun parseMetaFileName_onGarbageInput_returnsNullRatherThanThrowing() {
        assertNull(parseMetaFileName("not-a-timestamp"))
        assertNull(parseMetaFileName("checksums.sha256"))
        assertNull(parseMetaFileName(""))
    }

    @Test
    fun metaFileNameFor_calledTwiceForTheSameInstant_producesDistinctNames() {
        val instant = Instant.parse("2026-09-12T10:15:30Z")

        val first = metaFileNameFor(instant)
        val second = metaFileNameFor(instant)

        // A retry (or two paths finishing in the same second) must not silently overwrite an
        // earlier _meta entry for the same path.
        assertNotEquals(first, second, "expected two calls for the same instant to not collide")
        assertEquals(instant, parseMetaFileName(first))
        assertEquals(instant, parseMetaFileName(second))
    }
}
