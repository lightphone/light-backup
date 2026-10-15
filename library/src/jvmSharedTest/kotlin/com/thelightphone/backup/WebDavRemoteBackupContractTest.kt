package com.thelightphone.backup

import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

// Runs RemoteBackupContractTest  against a REAL WebDAV server, see webDavTestRemoteBackup()
class WebDavRemoteBackupContractTest : RemoteBackupContractTest() {
    override fun createRemoteBackup(): RemoteBackup {
        val rootFolderPath = uniqueTestRootFolderPath("light-backup-contract-tests")
        println("WebDavRemoteBackupContractTest root folder: $rootFolderPath")
        return webDavTestRemoteBackup(rootFolderPath)
    }

    @Test
    fun uploadFile_largerThanInMemoryLimit_roundTripsThroughTempFile() = runBlocking {
        val remoteBackup = webDavTestRemoteBackup(
            uniqueTestRootFolderPath("light-backup-contract-tests"),
            inMemoryUploadLimitBytes = 16,
        )
        val target = Path(remoteBackup.rootFolderPath, "large-upload")
        remoteBackup.createDirectory(target).getOrThrow()
        val content = "spilled to a temp file before uploading\n".repeat(100)

        remoteBackup.uploadFile(target, "large.txt", content.byteInputStream()).getOrThrow()

        assertEquals(content, remoteBackup.downloadFile(target, "large.txt").getOrThrow().bufferedReader().readText())
    }

    @Test
    fun verify_withValidCredentials_succeeds() = runBlocking {
        webDavTestRemoteBackup("unused").verify().getOrThrow()
    }

    @Test
    fun verify_withWrongPassword_returnsUnauthorized() = runBlocking {
        val url = System.getenv("WEBDAV_TEST_URL")
        webDavTestRemoteBackup("unused") // skips the test if unconfigured
        val remoteBackup = WebDavRemoteBackup(
            url,
            WebDavCredentials.Basic(System.getenv("WEBDAV_TEST_USERNAME").orEmpty(), "definitely-wrong"),
            "unused",
            allowInsecureHttp = true,
        )
        assertIs<RemoteBackupError.Unauthorized>(remoteBackup.verify().exceptionOrNull())
        Unit
    }
}
