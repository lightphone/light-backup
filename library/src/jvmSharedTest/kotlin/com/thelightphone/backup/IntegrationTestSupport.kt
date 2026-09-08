package com.thelightphone.backup

import java.security.MessageDigest
import kotlin.time.Clock

// Where test files will get dumped
private const val TEST_ROOT_FOLDER_NAME = "light-backup-tests"

// Computed once per test JVM (Gradle runs all jvmTest classes in one process by default)
private val testRunId = Clock.System.now().toEpochMilliseconds()
internal fun uniqueTestRootFolderPath(prefix: String) = "$TEST_ROOT_FOLDER_NAME/$testRunId/$prefix"

internal fun sha256Hex(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}
