package com.thelightphone.backup

import kotlin.test.Test

// Runs BackupRunnerContractTest against a REAL Dropbox account - see
// DropboxRemoteBackupContractTest / dropboxTestAccessToken() for how to obtain a token and run
// these.
class DropboxBackupRunnerContractTest : BackupRunnerContractTest() {
    override fun createRemoteBackup(): RemoteBackup {
        val token = dropboxTestAccessToken()
        val rootFolderPath = uniqueTestRootFolderPath("light-backup-runner-contract-tests")
        println("DropboxBackupRunnerContractTest root folder: $rootFolderPath")
        return DropboxRemoteBackup(fakeAccessTokenProvider(token), rootFolderPath)
    }

    // just so Android Studio shows me the little shortcut start test buttons in this file
    @Test
    fun dummyTest() = Unit
}
