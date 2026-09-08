package com.thelightphone.backup

import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.junit.Test

class GoogleDriveRemoteBackupContractTest : RemoteBackupContractTest() {
    override fun createRemoteBackup(): RemoteBackup {
        val token = googleDriveTestAccessToken()
        val rootFolderPath = uniqueTestRootFolderPath("light-backup-contract-tests")
        println("GoogleDriveRemoteBackupContractTest root folder: $rootFolderPath")
        return GoogleDriveRemoteBackup(fakeAccessTokenProvider(token), rootFolderPath)
    }

    // Tests resumable upload chunking unique to Drive
    @Test
    fun uploadFile_spanningMultipleChunks_succeedsViaResumableUpload() = runBlocking {
        // Use Drive's minimum chunk size (256k), default is 8MB
        val token = googleDriveTestAccessToken()
        val chunkedBackup = GoogleDriveRemoteBackup(
            tokenProvider = fakeAccessTokenProvider(token),
            rootFolderPath = uniqueTestRootFolderPath("light-backup-contract-tests"),
            chunkSizeBytes = 256 * 1024,
        )
        val target = Path(chunkedBackup.rootFolderPath, "large-file-dir")
        chunkedBackup.createDirectory(target).getOrThrow()

        val bytes = ByteArray(600 * 1024) { (it % 256).toByte() }
        chunkedBackup.uploadFile(target, "big.bin", bytes.inputStream()).getOrThrow()
    }
}
