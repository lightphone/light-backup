package com.thelightphone.backup

import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock

// Where test files will get dumped
private const val TEST_ROOT_FOLDER_NAME = "light-backup-tests"

// Computed once per test JVM (Gradle runs all jvmTest classes in one process by default) so every
// folder from one test run groups under a common timestamp for manual cleanup.
private val testRunId = Clock.System.now().toEpochMilliseconds()

// createRemoteBackup() runs fresh per @Test method but `prefix` is a fixed constant per class - a
// bare "$testRunId/$prefix" path would be shared by every test method in that class for the whole
// suite run, so real-account contract tests would contaminate each other (e.g. one test's _meta
// write would be visible to another test's "no backup exists yet" assertion). The counter gives
// each call - i.e. each test method - its own isolated folder while keeping the shared testRunId
// prefix for grouping.
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
    override suspend fun invalidateAccessToken() {
        // Static token for the whole test run - if the provider rejects it, let that surface as a
        // normal RemoteBackupError.Unauthorized from the call that triggered it, rather than trying
        // (and failing) to actually refresh anything here.
    }
}
