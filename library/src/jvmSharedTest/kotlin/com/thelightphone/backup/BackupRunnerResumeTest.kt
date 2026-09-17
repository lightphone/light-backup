package com.thelightphone.backup

import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.junit.Test
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class BackupRunnerResumeTest {
    @Test
    fun run_afterAFatalFailureMidCatchUp_resumesFromTheLastCompletedWindowOnly() = runBlocking {
        val remoteBackup = InMemoryRemoteBackup()
        val start = Clock.System.now()
        val clock = FakeClock(start + 3.hours)
        val window1Upper = start + 1.hours
        val window2Upper = start + 2.hours
        val window3Upper = start + 3.hours

        val dataSource =
            OneFilePerWindowDataSource(label = "dir1", earliestPossibleBackupDate = start)
        val runner = BackupRunner(
            remoteBackup,
            dataSource,
            clock,
            chunkDuration = 1.hours,
            earliestBackupTimeBeforeBuffer = Duration.ZERO
        )

        // Make the second window's upload fail fatally, aborting the run right after the first
        // window completed.
        remoteBackup.failUploadsTo(
            backupDirectoryNameFor(window2Upper),
            RemoteBackupError.QuotaExceeded()
        )

        val firstResult = runner.run()
        assertIs<BackupResult.Partial>(firstResult)
        assertEquals(
            1,
            firstResult.filesBackedUp,
            "expected only the first window to make it before the abort"
        )
        assertIs<RemoteBackupError.QuotaExceeded>(firstResult.abortedBy?.cause)

        val dir1Path = Path(remoteBackup.rootFolderPath, "dir1")
        assertTrue(
            backupDirectoryNameFor(window1Upper) in remoteBackup.listSubdirectories(dir1Path)
                .getOrThrow(),
            "expected the first window's folder to exist despite the later abort",
        )
        assertEquals(
            1,
            remoteBackup.listFiles(Path(dir1Path, META_FOLDER_NAME)).getOrThrow().size,
            "expected only the first window's _meta entry to exist after the abort",
        )

        // Clear the injected fault and retry - should resume right after the first window's
        // recorded progress (i.e. only redo windows 2 and 3), not redo window 1.
        remoteBackup.clearUploadFailures()
        val secondResult = runner.run()

        assertIs<BackupResult.Completed>(secondResult)
        assertEquals(
            2,
            secondResult.filesBackedUp,
            "expected only the two remaining windows to be redone"
        )

        val allWindowFolders =
            remoteBackup.listSubdirectories(dir1Path).getOrThrow().filter { it != META_FOLDER_NAME }
        assertEquals(
            setOf(window1Upper, window2Upper, window3Upper).map(::backupDirectoryNameFor).toSet(),
            allWindowFolders.toSet(),
            "expected all three window folders to exist once resuming finished",
        )
        assertEquals(
            3,
            remoteBackup.listFiles(Path(dir1Path, META_FOLDER_NAME)).getOrThrow().size,
            "expected 3 total _meta entries: 1 from the aborted run + 2 from the resume",
        )
    }
}

private class OneFilePerWindowDataSource(
    private val label: String,
    private val earliestPossibleBackupDate: Instant,
) : BackupDataSource {
    override suspend fun getPathsToBackUp(): Result<List<BackupPath>> =
        Result.success(listOf(BackupPath("com.example", Path(label), label)))

    override suspend fun getFilesToBackUpForPath(
        parent: Path,
        lowerBound: Instant,
        upperBound: Instant
    ): Result<List<Path>> =
        Result.success(listOf(Path("${parent.name}-${upperBound.epochSeconds}.txt")))

    override suspend fun readFile(path: Path): Result<InputStream> =
        Result.success(contentFor(path.name).byteInputStream())

    override suspend fun hashForFile(path: Path): Result<String> =
        Result.success(sha256Hex(contentFor(path.name)))

    override suspend fun getEarliestPossibleBackupDate(parent: Path): Result<Instant> =
        Result.success(earliestPossibleBackupDate)

    private fun contentFor(fileName: String) = "content for $fileName\n"
}