package com.thelightphone.backup

import kotlin.test.Test

// Runs BackupRunnerContractTest against a REAL WebDAV server, see webDavTestRemoteBackup()
class WebDavBackupRunnerContractTest : BackupRunnerContractTest() {
    override fun createRemoteBackup(): RemoteBackup {
        val rootFolderPath = uniqueTestRootFolderPath("light-backup-runner-contract-tests")
        println("WebDavBackupRunnerContractTest root folder: $rootFolderPath")
        return webDavTestRemoteBackup(rootFolderPath)
    }

    // just so Android Studio shows me the little shortcut start test buttons in this file
    @Test
    fun dummyTest() = Unit
}
