package com.thelightphone.backup

import kotlin.test.Test

class OfflineBackupRunnerContractTest : BackupRunnerContractTest() {
    override fun createRemoteBackup(): RemoteBackup = InMemoryRemoteBackup()

    // just so Android Studio shows me the little shortcut start test buttons in this file
    @Test
    fun dummyTest() = Unit
}