package com.thelightphone.backup

import org.junit.Assume.assumeTrue

internal fun dropboxTestAccessToken(): String {
    val token = System.getenv("DROPBOX_TEST_ACCESS_TOKEN")
    assumeTrue("set DROPBOX_TEST_ACCESS_TOKEN to a valid Dropbox access token to run these", token != null)
    return token!!
}
