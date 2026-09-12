package com.thelightphone.backup

import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock

// Where test files will get dumped
private const val TEST_ROOT_FOLDER_NAME = "light-backup-tests"

// Computed once per test JVM (Gradle runs all jvmTest classes in one process by default) so every
// folder from one test run groups under a common timestamp for manual cleanup.
private val testRunId = Clock.System.now().toEpochMilliseconds()

private val callCounter = AtomicInteger()
internal fun uniqueTestRootFolderPath(prefix: String) =
    "$TEST_ROOT_FOLDER_NAME/$testRunId/$prefix-${callCounter.incrementAndGet()}"

internal fun sha256Hex(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

// A static-token RemoteAccessTokenProvider for contract tests - works the same regardless of
// provider (Google, Dropbox, ...) since token caching/refresh isn't what these tests exercise.
internal fun fakeAccessTokenProvider(token: String) = object : RemoteAccessTokenProvider {
    override suspend fun getAccessToken(): Result<String> = Result.success(token)
    override suspend fun invalidateAccessToken() {}
}
