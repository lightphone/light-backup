package com.thelightphone.backup

// Runs BackupRunnerContractTest against a REAL Google Drive account - see
// GoogleDriveRemoteBackupContractTest / googleDriveTestAccessToken() for how to obtain a token
// and run these.
class GoogleBackupRunnerContractTest : BackupRunnerContractTest() {
    override fun createRemoteBackup(): RemoteBackup {
        val token = googleDriveTestAccessToken()
        val rootFolderPath = uniqueTestRootFolderPath("light-backup-runner-contract-tests")
        println("GoogleBackupRunnerContractTest root folder: $rootFolderPath")
        return GoogleDriveRemoteBackup(fakeGoogleTokenProvider(token), rootFolderPath)
    }
}
