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
