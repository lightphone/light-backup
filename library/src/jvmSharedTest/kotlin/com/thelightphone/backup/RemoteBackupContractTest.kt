package com.thelightphone.backup

import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

// Generic RemoteBackup contract, run against every REAL provider implementation
// Tests will be skipped if access token not provided for provider
// THIS WILL PUT FILES INTO YOUR STORAGE, USE WITH CAUTION
abstract class RemoteBackupContractTest {
    protected abstract fun createRemoteBackup(): RemoteBackup

    private lateinit var remoteBackup: RemoteBackup

    @Before
    fun setUpRemoteBackup() {
        remoteBackup = createRemoteBackup()
    }

    @Test
    fun createDirectoryThenUploadFile_succeeds() = runBlocking {
        val target = Path(remoteBackup.rootFolderPath, "path-a")
        remoteBackup.createDirectory(target).getOrThrow()

        val content = "hello from a contract test\n".toByteArray()
        remoteBackup.uploadFile(target, "hello.txt", content.inputStream()).getOrThrow()
    }

    @Test
    fun createDirectory_calledTwice_doesNotCreateADuplicateFolder() = runBlocking {
        val target = Path(remoteBackup.rootFolderPath, "idempotent-dir")
        remoteBackup.createDirectory(target).getOrThrow()
        remoteBackup.createDirectory(target).getOrThrow()

        val matches = remoteBackup.listSubdirectories(Path(remoteBackup.rootFolderPath))
            .getOrThrow()
            .count { it == "idempotent-dir" }
        assertEquals(1, matches)
    }

    @Test
    fun uploadFile_toDirectoryThatWasNeverCreated_returnsNotFound() = runBlocking {
        val target = Path(remoteBackup.rootFolderPath, "never-created")
        val result = remoteBackup.uploadFile(target, "x.txt", "x".byteInputStream())

        assertTrue(result.isFailure)
        assertIs<RemoteBackupError.NotFound>(result.exceptionOrNull())
        Unit
    }

    @Test
    fun getMostRecentBackupDate_beforeAnyBackupExists_returnsNullNotAnError() = runBlocking {
        val result = remoteBackup.getMostRecentBackupDate()

        assertTrue(result.isSuccess)
        assertNull(result.getOrThrow())
    }

    @Test
    fun getMostRecentBackupDate_afterWritingAMetaFile_reflectsItsCreationTime() = runBlocking {
        // RemoteBackup implementations only read from _meta - BackupRunner is what writes to it on
        // a successful run (see BackupRunnerContractTest) - this simulates that write directly to
        // test getMostRecentBackupDate() in isolation. Content has to be a real BackupSummary
        // (completedAt in particular) since getMostRecentBackupDate() reads that embedded
        // timestamp rather than any provider-assigned file metadata.
        val before = Clock.System.now() - 1.minutes // clock skew tolerance
        val metaDirectory = Path(remoteBackup.rootFolderPath, META_FOLDER_NAME)
        remoteBackup.createDirectory(metaDirectory).getOrThrow()
        val summary = Json.encodeToString(
            BackupSummary(
                directoryName = "2026-09-04T00-00-00Z",
                filesBackedUp = 0,
                paths = emptyList(),
                completedAt = Clock.System.now().toString(),
            ),
        )
        remoteBackup.uploadFile(metaDirectory, "2026-09-04T00-00-00Z", summary.byteInputStream()).getOrThrow()

        val mostRecent = remoteBackup.getMostRecentBackupDate().getOrThrow()

        assertNotNull(mostRecent)
        assertTrue(mostRecent >= before, "expected $mostRecent to be after $before")
    }

    @Test
    fun downloadFile_returnsWhatWasUploaded() = runBlocking {
        val target = Path(remoteBackup.rootFolderPath, "download-check")
        remoteBackup.createDirectory(target).getOrThrow()
        remoteBackup.uploadFile(target, "hello.txt", "hello\n".byteInputStream()).getOrThrow()

        val downloaded = remoteBackup.downloadFile(target, "hello.txt").getOrThrow().bufferedReader().readText()

        assertEquals("hello\n", downloaded)
    }

    @Test
    fun listFiles_reflectsUploadedFiles() = runBlocking {
        val target = Path(remoteBackup.rootFolderPath, "list-check")
        remoteBackup.createDirectory(target).getOrThrow()
        remoteBackup.uploadFile(target, "a.txt", "a".byteInputStream()).getOrThrow()
        remoteBackup.uploadFile(target, "b.txt", "b".byteInputStream()).getOrThrow()

        val files = remoteBackup.listFiles(target).getOrThrow()

        assertTrue(files.containsAll(listOf("a.txt", "b.txt")), "expected a.txt and b.txt in $files")
    }
}
