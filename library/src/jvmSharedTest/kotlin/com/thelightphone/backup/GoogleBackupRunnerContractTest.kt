package com.thelightphone.backup

import kotlin.test.Test

// Runs BackupRunnerContractTest against a REAL Google Drive account - see
// GoogleDriveRemoteBackupContractTest / googleDriveTestAccessToken() for how to obtain a token
// and run these.
class GoogleBackupRunnerContractTest : BackupRunnerContractTest() {
    override fun createRemoteBackup(): RemoteBackup {
        val token = googleDriveTestAccessToken()
        val rootFolderPath = uniqueTestRootFolderPath("light-backup-runner-contract-tests")
        println("GoogleBackupRunnerContractTest root folder: $rootFolderPath")
        return GoogleDriveRemoteBackup(fakeAccessTokenProvider(token), rootFolderPath)
    }

    // just so Android Studio shows me the little shortcut start test buttons in this file
    @Test
    fun dummyTest() = Unit
}
