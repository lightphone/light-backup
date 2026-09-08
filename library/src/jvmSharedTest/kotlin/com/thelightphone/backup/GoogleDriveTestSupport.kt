package com.thelightphone.backup

import org.junit.Assume.assumeTrue

internal fun googleDriveTestAccessToken(): String {
    // OR just hardcode your token here to test locally if you're feeling brave
    val token = System.getenv("GOOGLE_DRIVE_TEST_ACCESS_TOKEN")
    assumeTrue("set GOOGLE_DRIVE_TEST_ACCESS_TOKEN to a valid Drive access token to run these", token != null)
    return token!!
}
