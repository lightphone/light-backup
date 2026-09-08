package com.thelightphone.backup

import org.junit.Assume.assumeTrue

internal fun googleDriveTestAccessToken(): String {
    // OR just hardcode your token here to test locally if you're feeling brave
    "ya29.a0AdMD6Eh9zOE17-e0ldAPY2gHUmhxHaQg1hA-WG2vm0pDOifeuHHnuQW7aylMmC3soHHVgHXbFMp26K8OMLG-06QAGTtSME-66g1MFgfGpR0Fuo1cD-SRbNieaOjRdDxPQ8BQAqv2wDO7oMC9qLGjifnPJECZJcKvPwVCdlcvymi-wiihf5WxwsSPtPgtCgdcFjT0YAIaCgYKAa8SARMSFQHGX2MiJjeb7jb9oUEpFBM0Pxf12w0206".let {
        return it
    }
    val token = System.getenv("GOOGLE_DRIVE_TEST_ACCESS_TOKEN")
    assumeTrue("set GOOGLE_DRIVE_TEST_ACCESS_TOKEN to a valid Drive access token to run these", token != null)
    return token!!
}

internal fun fakeGoogleTokenProvider(token: String) = object : GoogleTokenProvider {
    override suspend fun getAccessToken(): Result<String> = Result.success(token)
    override suspend fun invalidateAccessToken() {
        // Static token for the whole test run - if Drive rejects it, let that surface as a normal
        // RemoteBackupError.Unauthorized from the call that triggered it, rather than trying (and
        // failing) to actually refresh anything here.
    }
}
