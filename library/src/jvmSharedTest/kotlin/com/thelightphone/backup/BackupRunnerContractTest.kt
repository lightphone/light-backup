package com.thelightphone.backup

import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// Extend/Implement for each provider
abstract class BackupRunnerContractTest {
    protected abstract fun createRemoteBackup(): RemoteBackup

    private lateinit var remoteBackup: RemoteBackup

    @Before
    fun setUpRemoteBackup() {
        remoteBackup = createRemoteBackup()
    }

    @Test
    fun run_backsUpEveryFileAndUploadsAVerifiableChecksumManifest() = runBlocking {
        val paths = listOf(
            BackupPath("com.example", Path("dir1"), "dir1"),
            BackupPath("com.example", Path("dir2"), "dir2"),
        )
        val dataSource = FakeBackupDataSource(paths, filesPerPath = 3)
        val runner = BackupRunner(remoteBackup, dataSource)

        val result = runner.run("run-1")

        assertIs<BackupResult.Completed>(result)
        assertEquals(6, result.filesBackedUp)

        val dir1RunFolder = Path(Path(remoteBackup.rootFolderPath, "dir1"), "run-1")

        val fileContent = remoteBackup.downloadFile(dir1RunFolder, "dir1-file1.txt").getOrThrow()
            .bufferedReader().readText()
        assertEquals(dataSource.contentFor("dir1-file1.txt"), fileContent)

        // Confirm the manifest's hash for that file is the real SHA-256 of its content, in
        val manifest = remoteBackup.downloadFile(dir1RunFolder, "checksums.sha256").getOrThrow()
            .bufferedReader().readText()
        val expectedHash = sha256Hex(dataSource.contentFor("dir1-file1.txt"))
        assertTrue(
            manifest.lines().contains("$expectedHash  dir1-file1.txt"),
            "expected manifest to contain \"$expectedHash  dir1-file1.txt\", got:\n$manifest",
        )

        // completed, should have a _meta entry filed under dir1's own folder (not a shared one)
        val dir1MetaFiles = remoteBackup
            .listFiles(Path(Path(remoteBackup.rootFolderPath, "dir1"), META_FOLDER_NAME))
            .getOrThrow()
        assertEquals(1, dir1MetaFiles.size, "expected exactly one dir1/_meta entry, got $dir1MetaFiles")
    }

    @Test
    fun run_withOneUnreadableFile_stillBacksUpTheRestAsPartial() = runBlocking {
        val paths = listOf(BackupPath("com.example", Path("dir1"), "dir1"))
        val dataSource = FakeBackupDataSource(paths, filesPerPath = 3, failingFile = "dir1-file2.txt")
        val runner = BackupRunner(remoteBackup, dataSource)

        val result = runner.run("run-partial")

        assertIs<BackupResult.Partial>(result)
        assertEquals(2, result.filesBackedUp)
        val failure = result.failures.single()
        assertEquals(FailureScope.File("dir1", "dir1-file2.txt"), failure.scope)

        // The two good files made it despite the third's local read failure
        val dir1RunFolder = Path(Path(remoteBackup.rootFolderPath, "dir1"), "run-partial")
        val files = remoteBackup.listFiles(dir1RunFolder).getOrThrow()
        assertTrue("dir1-file1.txt" in files)
        assertTrue("dir1-file3.txt" in files)
        assertTrue("dir1-file2.txt" !in files)

        // dir1 still made partial progress (2 of 3 files), so it must be credited in its own
        // _meta folder - otherwise a retry would re-upload dir1-file1.txt and dir1-file3.txt from
        // scratch.
        val dir1MetaFiles = remoteBackup
            .listFiles(Path(Path(remoteBackup.rootFolderPath, "dir1"), META_FOLDER_NAME))
            .getOrElse { if (it is RemoteBackupError.NotFound) emptyList() else throw it }
        assertEquals(1, dir1MetaFiles.size, "expected exactly one dir1/_meta entry, got $dir1MetaFiles")

        val mostRecent = remoteBackup.getMostRecentBackupDates().getOrThrow()
        assertNotNull(mostRecent["dir1"])
        Unit
    }

    @Test
    fun run_withAPathThatHasNothingToBackUp_doesNotCreateAnEmptyDirectoryForIt() = runBlocking {
        val paths = listOf(
            BackupPath("com.example", Path("dir1"), "dir1"),
            BackupPath("com.example", Path("dir2"), "dir2"),
        )
        val dataSource = FakeBackupDataSource(paths, filesPerPath = 1, emptyLabels = setOf("dir2"))
        val runner = BackupRunner(remoteBackup, dataSource)

        val result = runner.run("run-nothing-for-dir2")

        assertIs<BackupResult.Completed>(result)
        assertEquals(1, result.filesBackedUp)

        val rootSubfolders = remoteBackup.listSubdirectories(Path(remoteBackup.rootFolderPath)).getOrThrow()
        assertTrue("dir1" in rootSubfolders, "expected dir1 to be created since it had a file to back up")
        assertTrue("dir2" !in rootSubfolders, "expected no dir2 folder since it had nothing to back up, got $rootSubfolders")
    }

    @Test
    fun secondRun_getsItsOwnDatedFolder_andGetMostRecentBackupDatesAdvancesForThatLabel() = runBlocking {
        val dataSource = FakeBackupDataSource(listOf(BackupPath("com.example", Path("dir1"), "dir1")), filesPerPath = 1)
        val clock = FakeClock(Clock.System.now())
        val runner = BackupRunner(remoteBackup, dataSource, clock)

        assertIs<BackupResult.Completed>(runner.run("run-a"))
        val afterFirst = remoteBackup.getMostRecentBackupDates().getOrThrow()["dir1"]

        clock.now += 1.seconds

        assertIs<BackupResult.Completed>(runner.run("run-b"))
        val afterSecond = remoteBackup.getMostRecentBackupDates().getOrThrow()["dir1"]

        assertTrue(afterSecond!! > afterFirst!!, "expected $afterSecond to be after $afterFirst")

        val rootSubfolders = remoteBackup.listSubdirectories(Path(remoteBackup.rootFolderPath)).getOrThrow()
        assertEquals(1, rootSubfolders.count { it == "dir1" }, "expected dir1 to be created once and reused")

        val dir1Subfolders = remoteBackup.listSubdirectories(Path(remoteBackup.rootFolderPath, "dir1")).getOrThrow()
        assertTrue("run-a" in dir1Subfolders)
        assertTrue("run-b" in dir1Subfolders)

        // Each run wrote its own dir1/_meta entry, named after its own completedAt - not a shared
        // file keyed by directory name.
        val dir1MetaFiles = remoteBackup
            .listFiles(Path(Path(remoteBackup.rootFolderPath, "dir1"), META_FOLDER_NAME))
            .getOrThrow()
        assertEquals(2, dir1MetaFiles.size, "expected two dir1/_meta entries, got $dir1MetaFiles")
    }
}

private class FakeBackupDataSource(
    private val paths: List<BackupPath>,
    private val filesPerPath: Int = 3,
    private val failingFile: String? = null,
    // Labels that should report nothing left to back up, regardless of filesPerPath.
    private val emptyLabels: Set<String> = emptySet(),
) : BackupDataSource {
    override suspend fun getPathsToBackUp(): Result<List<BackupPath>> = Result.success(paths)

    override suspend fun getFilesToBackUpForPath(parent: Path, timeOfLastBackup: Instant): Result<List<Path>> {
        if (parent.name in emptyLabels) return Result.success(emptyList())
        return Result.success((1..filesPerPath).map { Path("${parent.name}-file$it.txt") })
    }

    override suspend fun readFile(path: Path): Result<InputStream> {
        if (path.name == failingFile) {
            return Result.failure(IOException("simulated local read failure for ${path.name}"))
        }
        return Result.success(contentFor(path.name).byteInputStream())
    }

    override suspend fun hashForFile(path: Path): Result<String> = Result.success(sha256Hex(contentFor(path.name)))

    fun contentFor(fileName: String) = "content for $fileName\n"
}

private class FakeClock(var now: Instant) : Clock {
    override fun now(): Instant = now
}
