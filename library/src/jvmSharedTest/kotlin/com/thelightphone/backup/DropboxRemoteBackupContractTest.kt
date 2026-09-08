package com.thelightphone.backup

import kotlin.test.Test

// Runs RemoteBackupContractTest against a REAL Dropbox account when DROPBOX_TEST_ACCESS_TOKEN is
// set - see dropboxTestAccessToken() for how to obtain and pass one. Each run creates its own
// timestamped root folder under light-backup-tests/ and does NOT delete it afterward - inspect or
// clean up manually in the Dropbox UI.
class DropboxRemoteBackupContractTest : RemoteBackupContractTest() {
    override fun createRemoteBackup(): RemoteBackup {
        val token = dropboxTestAccessToken()
        val rootFolderPath = uniqueTestRootFolderPath("light-backup-contract-tests")
        println("DropboxRemoteBackupContractTest root folder: $rootFolderPath")
        return DropboxRemoteBackup(fakeAccessTokenProvider(token), rootFolderPath)
    }

    // just so Android Studio shows me the little shortcut start test buttons in this file
    @Test
    fun dummyTest() = Unit
}
