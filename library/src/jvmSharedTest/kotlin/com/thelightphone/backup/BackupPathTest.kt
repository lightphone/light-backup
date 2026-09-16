package com.thelightphone.backup

import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupPathTest {
    private fun labeled(label: String) = BackupPath("com.example", Path("local"), label)

    @Test
    fun hasValidLabel_acceptsAPlainSingleSegmentLabel() {
        assertTrue(labeled("photos").hasValidLabel())
        assertTrue(labeled("my-tool.v2").hasValidLabel())
    }

    @Test
    fun hasValidLabel_rejectsEmpty() {
        assertFalse(labeled("").hasValidLabel())
    }

    @Test
    fun hasValidLabel_rejectsDotAndDotDot() {
        assertFalse(labeled(".").hasValidLabel())
        assertFalse(labeled("..").hasValidLabel())
    }

    @Test
    fun hasValidLabel_rejectsPathSeparators() {
        assertFalse(labeled("../other-tool").hasValidLabel())
        assertFalse(labeled("a/b").hasValidLabel())
        assertFalse(labeled("a\\b").hasValidLabel())
        assertFalse(labeled("/etc").hasValidLabel())
    }
}
