package com.thelightphone.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

// boundary testing for chunkWindows
class ChunkWindowsTest {
    @Test
    fun chunkWindows_withFromEqualToTo_returnsNoWindows() {
        val instant = Instant.parse("2026-09-12T00:00:00Z")

        assertEquals(emptyList(), chunkWindows(instant, instant))
    }

    @Test
    fun chunkWindows_withFromAfterTo_returnsNoWindows() {
        val from = Instant.parse("2026-09-12T12:00:00Z")
        val to = Instant.parse("2026-09-12T00:00:00Z")

        assertEquals(emptyList(), chunkWindows(from, to))
    }

    @Test
    fun chunkWindows_withSpanShorterThanChunkDuration_returnsOneWindowCoveringTheWholeSpan() {
        val from = Instant.parse("2026-09-12T00:00:00Z")
        val to = from + 5.hours

        val windows = chunkWindows(from, to, chunkDuration = 24.hours)

        assertEquals(listOf(BackupWindow(from, to)), windows)
    }

    @Test
    fun chunkWindows_withSpanAnExactMultipleOfChunkDuration_hasNoShortTrailingWindow() {
        val from = Instant.parse("2026-09-12T00:00:00Z")
        val to = from + 3.hours

        val windows = chunkWindows(from, to, chunkDuration = 1.hours)

        assertEquals(
            listOf(
                BackupWindow(from, from + 1.hours),
                BackupWindow(from + 1.hours, from + 2.hours),
                BackupWindow(from + 2.hours, from + 3.hours),
            ),
            windows,
        )
    }

    @Test
    fun chunkWindows_withARemainder_endsWithAShortFinalWindow() {
        val from = Instant.parse("2026-09-12T00:00:00Z")
        val to = from + 2.hours + 30.minutes

        val windows = chunkWindows(from, to, chunkDuration = 1.hours)

        assertEquals(
            listOf(
                BackupWindow(from, from + 1.hours),
                BackupWindow(from + 1.hours, from + 2.hours),
                BackupWindow(from + 2.hours, to),
            ),
            windows,
        )
    }

    @Test
    fun chunkWindows_windowsAreContiguousAndCoverTheWholeSpanExactlyOnce() {
        val from = Instant.parse("2026-09-01T00:00:00Z")
        val to = from + 30.hours

        val windows = chunkWindows(from, to, chunkDuration = 1.hours)

        assertEquals(from, windows.first().lowerBound)
        assertEquals(to, windows.last().upperBound)
        for (i in 1 until windows.size) {
            assertEquals(
                windows[i - 1].upperBound, windows[i].lowerBound,
                "expected window $i to start exactly where window ${i - 1} ended",
            )
        }
        assertTrue(windows.all { it.lowerBound < it.upperBound }, "expected every window to be non-empty")
    }
}