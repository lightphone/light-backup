package com.thelightphone.backup

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
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
        val runner =
            BackupRunner(remoteBackup, dataSource, earliestBackupTimeBeforeBuffer = Duration.ZERO)

        val result = runner.run()

        assertIs<BackupResult.Completed>(result)
        assertEquals(6, result.filesBackedUp)

        val dir1RunFolder =
            Path(Path(remoteBackup.rootFolderPath, "dir1"), onlyWindowFolder("dir1"))

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
        assertEquals(
            1,
            dir1MetaFiles.size,
            "expected exactly one dir1/_meta entry, got $dir1MetaFiles"
        )
    }

    @Test
    fun run_withOneUnreadableFile_stillBacksUpTheRestAsPartial() = runBlocking {
        val paths = listOf(BackupPath("com.example", Path("dir1"), "dir1"))
        val dataSource =
            FakeBackupDataSource(paths, filesPerPath = 3, failingFile = "dir1-file2.txt")
        val runner =
            BackupRunner(remoteBackup, dataSource, earliestBackupTimeBeforeBuffer = Duration.ZERO)

        val result = runner.run()

        assertIs<BackupResult.Partial>(result)
        assertEquals(2, result.filesBackedUp)
        val failure = result.failures.single()
        assertEquals(FailureScope.File("dir1", "dir1-file2.txt"), failure.scope)

        // The two good files made it despite the third's local read failure
        val dir1RunFolder =
            Path(Path(remoteBackup.rootFolderPath, "dir1"), onlyWindowFolder("dir1"))
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
        assertEquals(
            1,
            dir1MetaFiles.size,
            "expected exactly one dir1/_meta entry, got $dir1MetaFiles"
        )

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
        val runner =
            BackupRunner(remoteBackup, dataSource, earliestBackupTimeBeforeBuffer = Duration.ZERO)

        val result = runner.run()

        assertIs<BackupResult.Completed>(result)
        assertEquals(1, result.filesBackedUp)

        val rootSubfolders =
            remoteBackup.listSubdirectories(Path(remoteBackup.rootFolderPath)).getOrThrow()
        assertTrue(
            "dir1" in rootSubfolders,
            "expected dir1 to be created since it had a file to back up"
        )
        assertTrue(
            "dir2" !in rootSubfolders,
            "expected no dir2 folder since it had nothing to back up, got $rootSubfolders"
        )
    }

    @Test
    fun secondRun_getsItsOwnDatedFolder_andGetMostRecentBackupDatesAdvancesForThatLabel() =
        runBlocking {
            val dataSource = FakeBackupDataSource(
                listOf(BackupPath("com.example", Path("dir1"), "dir1")),
                filesPerPath = 1
            )
            val clock = FakeClock(Clock.System.now())
            val runner = BackupRunner(
                remoteBackup,
                dataSource,
                clock,
                earliestBackupTimeBeforeBuffer = Duration.ZERO
            )

            assertIs<BackupResult.Completed>(runner.run())
            val afterFirst = remoteBackup.getMostRecentBackupDates().getOrThrow()["dir1"]

            clock.now += 1.seconds

            assertIs<BackupResult.Completed>(runner.run())
            val afterSecond = remoteBackup.getMostRecentBackupDates().getOrThrow()["dir1"]

            assertTrue(
                afterSecond!! > afterFirst!!,
                "expected $afterSecond to be after $afterFirst"
            )

            val rootSubfolders =
                remoteBackup.listSubdirectories(Path(remoteBackup.rootFolderPath)).getOrThrow()
            assertEquals(
                1,
                rootSubfolders.count { it == "dir1" },
                "expected dir1 to be created once and reused"
            )

            // Each run gets its own dated window folder under dir1
            val dir1WindowFolders =
                remoteBackup.listSubdirectories(Path(remoteBackup.rootFolderPath, "dir1"))
                    .getOrThrow()
                    .filter { it != META_FOLDER_NAME }
            assertEquals(
                2,
                dir1WindowFolders.size,
                "expected two distinct dated folders, got $dir1WindowFolders"
            )

            // Each run wrote its own dir1/_meta entry, named after its own completedAt - not a shared
            // file keyed by directory name.
            val dir1MetaFiles = remoteBackup
                .listFiles(Path(Path(remoteBackup.rootFolderPath, "dir1"), META_FOLDER_NAME))
                .getOrThrow()
            assertEquals(
                2,
                dir1MetaFiles.size,
                "expected two dir1/_meta entries, got $dir1MetaFiles"
            )
        }

    @Test
    fun run_withACatchUpPeriodLongerThanChunkDuration_splitsIntoMultipleWindows() = runBlocking {
        val start = Clock.System.now()
        val clock = FakeClock(start + 3.hours)
        val dataSource = FakeBackupDataSource(
            paths = listOf(BackupPath("com.example", Path("dir1"), "dir1")),
            earliestPossibleBackupDate = start,
            filesForWindow = { label, _, upperBound -> Result.success(listOf("$label-${upperBound.epochSeconds}.txt")) },
        )
        val runner = BackupRunner(
            remoteBackup,
            dataSource,
            clock,
            chunkDuration = 1.hours,
            earliestBackupTimeBeforeBuffer = Duration.ZERO
        )

        val result = runner.run()

        assertIs<BackupResult.Completed>(result)
        assertEquals(3, result.filesBackedUp)

        val windowFolders =
            remoteBackup.listSubdirectories(Path(remoteBackup.rootFolderPath, "dir1"))
                .getOrThrow()
                .filter { it != META_FOLDER_NAME }
        assertEquals(
            3,
            windowFolders.size,
            "expected one folder per 1-hour window, got $windowFolders"
        )

        windowFolders.forEach { folder ->
            val filesInFolder = remoteBackup
                .listFiles(Path(Path(remoteBackup.rootFolderPath, "dir1"), folder))
                .getOrThrow()
            assertEquals(
                2, filesInFolder.size,
                "expected the one data file plus checksums manifest in $folder, got $filesInFolder",
            )
        }

        val dir1MetaFiles = remoteBackup
            .listFiles(Path(Path(remoteBackup.rootFolderPath, "dir1"), META_FOLDER_NAME))
            .getOrThrow()
        assertEquals(
            3,
            dir1MetaFiles.size,
            "expected one _meta entry per window, got $dir1MetaFiles"
        )
    }

    @Test
    fun run_whenNeverBackedUpBefore_startsResumeFromEarliestPossibleDateMinusTheBuffer() =
        runBlocking {
            val start = Clock.System.now()
            val buffer = 2.hours
            val clock = FakeClock(start + 1.hours)
            val dataSource = FakeBackupDataSource(
                paths = listOf(BackupPath("com.example", Path("dir1"), "dir1")),
                earliestPossibleBackupDate = start,
                // Only the very first window - the one starting exactly `buffer` before the
                // reported earliest date - has anything to back up, so this file is only picked
                // up if the buffer was actually subtracted from resumeFrom.
                filesForWindow = { label, lowerBound, _ ->
                    if (lowerBound == start - buffer) {
                        Result.success(listOf("$label-buffered.txt"))
                    } else {
                        Result.success(emptyList())
                    }
                },
            )
            val progressUpdates = mutableListOf<BackupProgress>()
            val runner = BackupRunner(
                remoteBackup,
                dataSource,
                clock,
                chunkDuration = 1.hours,
                onProgress = { progressUpdates += it },
                earliestBackupTimeBeforeBuffer = buffer,
            )

            val result = runner.run()

            assertIs<BackupResult.Completed>(result)
            assertEquals(
                1,
                result.filesBackedUp,
                "expected the file in the buffered first window to be backed up"
            )

            // resumeFrom = start - buffer, now = start + 1.hours -> 3 one-hour windows total,
            // confirming the buffer actually pushed resumeFrom back rather than being ignored.
            assertEquals(3, progressUpdates.last().totalWindows)

            val windowFolders =
                remoteBackup.listSubdirectories(Path(remoteBackup.rootFolderPath, "dir1"))
                    .getOrThrow()
                    .filter { it != META_FOLDER_NAME }
            assertEquals(
                1,
                windowFolders.size,
                "expected only the buffered first window to get a folder, got $windowFolders"
            )
        }

    @Test
    fun run_reportsProgressOncePerWindowPlusAnUpfrontTotal() = runBlocking {
        val start = Clock.System.now()
        val clock = FakeClock(start + 3.hours)
        val dataSource = FakeBackupDataSource(
            paths = listOf(BackupPath("com.example", Path("dir1"), "dir1")),
            earliestPossibleBackupDate = start,
            filesForWindow = { label, _, upperBound -> Result.success(listOf("$label-${upperBound.epochSeconds}.txt")) },
        )
        val progressUpdates = mutableListOf<BackupProgress>()
        val runner = BackupRunner(
            remoteBackup, dataSource, clock, chunkDuration = 1.hours,
            onProgress = { progressUpdates += it },
            earliestBackupTimeBeforeBuffer = Duration.ZERO
        )

        assertIs<BackupResult.Completed>(runner.run())

        // The total (3 windows) is known upfront, before any window is processed, then one update
        // per window as BackupRunner finishes with it.
        assertEquals(
            listOf(
                BackupProgress(0, 3),
                BackupProgress(1, 3),
                BackupProgress(2, 3),
                BackupProgress(3, 3),
            ),
            progressUpdates,
        )
    }

    @Test
    fun run_withSparseWindows_onlyCreatesFoldersAndMetaEntriesForWindowsThatHadFiles() =
        runBlocking {
            val start = Clock.System.now()
            val clock = FakeClock(start + 3.hours)
            // Only the middle of the 3 hourly windows has anything to back up.
            val dataSource = FakeBackupDataSource(
                paths = listOf(BackupPath("com.example", Path("dir1"), "dir1")),
                earliestPossibleBackupDate = start,
                filesForWindow = { label, lowerBound, _ ->
                    if (lowerBound == start + 1.hours) {
                        Result.success(listOf("$label-only-file.txt"))
                    } else {
                        Result.success(emptyList())
                    }
                },
            )
            val runner = BackupRunner(remoteBackup, dataSource, clock, chunkDuration = 1.hours)

            val result = runner.run()

            assertIs<BackupResult.Completed>(result)
            assertEquals(1, result.filesBackedUp)

            val windowFolders =
                remoteBackup.listSubdirectories(Path(remoteBackup.rootFolderPath, "dir1"))
                    .getOrThrow()
                    .filter { it != META_FOLDER_NAME }
            assertEquals(
                1,
                windowFolders.size,
                "expected only the non-empty window to get a folder, got $windowFolders"
            )

            val dir1MetaFiles = remoteBackup
                .listFiles(Path(Path(remoteBackup.rootFolderPath, "dir1"), META_FOLDER_NAME))
                .getOrThrow()
            assertEquals(
                1,
                dir1MetaFiles.size,
                "expected exactly one _meta entry, for the window that had files"
            )
        }

    @Test
    fun run_withANonFatalListingFailureInOneWindow_stillAttemptsLaterWindowsForThatPath() =
        runBlocking {
            val start = Clock.System.now()
            val clock = FakeClock(start + 3.hours)
            val dataSource = FakeBackupDataSource(
                paths = listOf(BackupPath("com.example", Path("dir1"), "dir1")),
                earliestPossibleBackupDate = start,
                filesForWindow = { label, lowerBound, upperBound ->
                    if (lowerBound == start) {
                        Result.failure(IOException("simulated local listing failure for the first window"))
                    } else {
                        Result.success(listOf("$label-${upperBound.epochSeconds}.txt"))
                    }
                },
            )
            val runner = BackupRunner(
                remoteBackup,
                dataSource,
                clock,
                chunkDuration = 1.hours,
                earliestBackupTimeBeforeBuffer = Duration.ZERO
            )

            val result = runner.run()

            assertIs<BackupResult.Partial>(result)
            assertEquals(
                2,
                result.filesBackedUp,
                "expected the later two windows to still complete despite the first's failure"
            )
            assertEquals(FailureScope.Path("dir1"), result.failures.single().scope)

            val windowFolders =
                remoteBackup.listSubdirectories(Path(remoteBackup.rootFolderPath, "dir1"))
                    .getOrThrow()
                    .filter { it != META_FOLDER_NAME }
            assertEquals(
                2,
                windowFolders.size,
                "expected folders only for the two windows that succeeded, got $windowFolders"
            )
        }

    @Test
    fun run_withNothingEverToBackUp_completesWithoutCreatingAnyFolder() = runBlocking {
        val now = Clock.System.now()
        val dataSource = FakeBackupDataSource(
            paths = listOf(BackupPath("com.example", Path("dir1"), "dir1")),
            // Nothing under dir1 yet - see getEarliestPossibleBackupDate's contract for parent with
            // nothing in it.
            earliestPossibleBackupDate = now,
        )
        val runner = BackupRunner(
            remoteBackup,
            dataSource,
            FakeClock(now),
            earliestBackupTimeBeforeBuffer = Duration.ZERO
        )

        val result = runner.run()

        assertIs<BackupResult.Completed>(result)
        assertEquals(0, result.filesBackedUp)

        val rootSubfolders = remoteBackup.listSubdirectories(Path(remoteBackup.rootFolderPath))
            .getOrElse { if (it is RemoteBackupError.NotFound) emptyList() else throw it }
        assertTrue(
            "dir1" !in rootSubfolders,
            "expected no dir1 folder since it never had anything to back up, got $rootSubfolders"
        )
    }

    @Test
    fun run_whenGetEarliestPossibleBackupDateFails_recordsAPathFailureAndContinuesOtherPaths() =
        runBlocking {
            val clock = FakeClock(Clock.System.now())

            // Seed dir1 with a completed backup so its resumeFrom comes from getMostRecentBackupDates,
            // not getEarliestPossibleBackupDate - only dir2 (never backed up) needs the latter to
            // succeed.
            val seedRunner = BackupRunner(
                remoteBackup,
                FakeBackupDataSource(
                    listOf(BackupPath("com.example", Path("dir1"), "dir1")),
                    filesPerPath = 1
                ),
                clock,
                earliestBackupTimeBeforeBuffer = Duration.ZERO
            )
            assertIs<BackupResult.Completed>(seedRunner.run())

            // A real gap, rather than relying on wall-clock time moving between the two run() calls -
            // now gets truncated to whole seconds (see truncatedToWholeSeconds), so two runs within the
            // same real-world second would otherwise see no new time to work with for dir1.
            clock.now += 1.hours

            val paths = listOf(
                BackupPath("com.example", Path("dir1"), "dir1"),
                BackupPath("com.example", Path("dir2"), "dir2"),
            )
            val dataSource = FakeBackupDataSource(
                paths, filesPerPath = 1,
                earliestPossibleBackupDateFailure = IOException("simulated getEarliestPossibleBackupDate failure"),
            )
            val runner = BackupRunner(remoteBackup, dataSource, clock)

            val result = runner.run()

            assertIs<BackupResult.Partial>(result)
            assertEquals(
                1,
                result.filesBackedUp,
                "expected dir1, which already had a last-backup time, to still complete"
            )
            assertEquals(FailureScope.Path("dir2"), result.failures.single().scope)
        }

    @Test
    fun run_withAnInvalidLabel_recordsAFailureAndDoesNotEscapeItsOwnSubtree() = runBlocking {
        val paths = listOf(
            BackupPath("com.example", Path("dir1"), "dir1"),
            // Attempts to address dir1's own folder directly instead of its own - see
            // BackupPath.hasValidLabel.
            BackupPath("com.evil", Path("evil"), "../dir1"),
        )
        val dataSource = FakeBackupDataSource(paths, filesPerPath = 1)
        val runner =
            BackupRunner(remoteBackup, dataSource, earliestBackupTimeBeforeBuffer = Duration.ZERO)

        val result = runner.run()

        assertIs<BackupResult.Partial>(result)
        assertEquals(1, result.filesBackedUp, "expected only the legitimate dir1 path to back up")
        assertEquals(FailureScope.Path("../dir1"), result.failures.single().scope)

        // The malicious label never got as far as touching dir1's own _meta folder.
        val dir1MetaFiles = remoteBackup
            .listFiles(Path(Path(remoteBackup.rootFolderPath, "dir1"), META_FOLDER_NAME))
            .getOrThrow()
        assertEquals(
            1,
            dir1MetaFiles.size,
            "expected only dir1's own _meta entry, got $dir1MetaFiles"
        )
    }

    @Test
    fun run_withMoreWindowsThanTheCapForAPath_onlyProcessesTheCapAndLeavesTheRestForFutureRuns() =
        runBlocking {
            val start = Clock.System.now()
            val clock = FakeClock(start + 5.hours)
            val dataSource = FakeBackupDataSource(
                paths = listOf(BackupPath("com.example", Path("dir1"), "dir1")),
                earliestPossibleBackupDate = start,
                filesForWindow = { label, _, upperBound -> Result.success(listOf("$label-${upperBound.epochSeconds}.txt")) },
            )
            val runner = BackupRunner(
                remoteBackup,
                dataSource,
                clock,
                chunkDuration = 1.hours,
                maxWindowsPerPathPerRun = 2,
                earliestBackupTimeBeforeBuffer = Duration.ZERO
            )

            val firstResult = runner.run()
            assertIs<BackupResult.Completed>(firstResult)
            assertEquals(
                2,
                firstResult.filesBackedUp,
                "expected only the capped 2 of 5 windows to be processed"
            )

            val secondResult = runner.run()
            assertIs<BackupResult.Completed>(secondResult)
            assertEquals(
                2,
                secondResult.filesBackedUp,
                "expected the next 2 windows on the following run"
            )

            val thirdResult = runner.run()
            assertIs<BackupResult.Completed>(thirdResult)
            assertEquals(
                1,
                thirdResult.filesBackedUp,
                "expected the final remaining window on a third run"
            )
        }

    @Test
    fun run_whenAWindowReportsMoreFilesThanTheCap_uploadsTheCapAndDoesNotMarkTheWindowComplete() =
        runBlocking {
            val paths = listOf(BackupPath("com.example", Path("dir1"), "dir1"))
            val dataSource = FakeBackupDataSource(paths, filesPerPath = 5)
            val runner = BackupRunner(
                remoteBackup,
                dataSource,
                maxFilesPerWindow = 3,
                earliestBackupTimeBeforeBuffer = Duration.ZERO
            )

            val result = runner.run()

            assertIs<BackupResult.Partial>(result)
            assertEquals(
                3,
                result.filesBackedUp,
                "expected only the capped 3 of 5 files to be uploaded"
            )
            assertEquals(FailureScope.Path("dir1"), result.failures.single().scope)

            // Not marked complete - no _meta entry, so a retry won't skip past the untouched remainder.
            val dir1MetaFiles = remoteBackup
                .listFiles(Path(Path(remoteBackup.rootFolderPath, "dir1"), META_FOLDER_NAME))
                .getOrElse { if (it is RemoteBackupError.NotFound) emptyList() else throw it }
            assertEquals(
                0,
                dir1MetaFiles.size,
                "expected no _meta entry since the window wasn't fully drained"
            )

            val mostRecent = remoteBackup.getMostRecentBackupDates().getOrThrow()
            assertTrue("dir1" !in mostRecent, "expected dir1's resumeFrom to not have advanced")
        }

    @Test
    fun run_whenADataSourceCallHangs_timesOutAsANonFatalFailureInsteadOfBlockingTheRun() =
        runBlocking {
            val paths = listOf(BackupPath("com.example", Path("dir1"), "dir1"))
            val dataSource = FakeBackupDataSource(
                paths,
                filesPerPath = 1,
                getFilesToBackUpForPathDelay = 5.seconds
            )
            val runner = BackupRunner(
                remoteBackup,
                dataSource,
                dataSourceTimeout = 50.milliseconds,
                earliestBackupTimeBeforeBuffer = Duration.ZERO
            )

            val result = runner.run()

            assertIs<BackupResult.Partial>(result)
            assertEquals(0, result.filesBackedUp)
            val failure = result.failures.single()
            assertEquals(FailureScope.Path("dir1"), failure.scope)
            assertIs<TimeoutException>(failure.cause)
            Unit
        }

    // There should be exactly one non-_meta subfolder under rootFolderPath/label; returns its name.
    private suspend fun onlyWindowFolder(label: String): String =
        remoteBackup.listSubdirectories(Path(remoteBackup.rootFolderPath, label)).getOrThrow()
            .single { it != META_FOLDER_NAME }
}

private class FakeBackupDataSource(
    private val paths: List<BackupPath>,
    private val filesPerPath: Int = 3,
    private val failingFile: String? = null,
    // Labels that should report nothing left to back up, regardless of filesPerPath.
    private val emptyLabels: Set<String> = emptySet(),
    private val earliestPossibleBackupDate: Instant = Clock.System.now() - 1.hours,
    private val earliestPossibleBackupDateFailure: Throwable? = null,
    private val filesForWindow: ((label: String, lowerBound: Instant, upperBound: Instant) -> Result<List<String>>)? = null,
    // Simulates a hung/slow client implementation - see dataSourceTimeout.
    private val getFilesToBackUpForPathDelay: Duration? = null,
) : BackupDataSource {
    override suspend fun getPathsToBackUp(): Result<List<BackupPath>> = Result.success(paths)

    override suspend fun getFilesToBackUpForPath(
        parent: Path,
        lowerBound: Instant,
        upperBound: Instant,
    ): Result<List<Path>> {
        getFilesToBackUpForPathDelay?.let { delay(it) }
        filesForWindow?.let {
            return it(
                parent.name,
                lowerBound,
                upperBound
            ).map { names -> names.map(::Path) }
        }
        if (parent.name in emptyLabels) return Result.success(emptyList())
        return Result.success((1..filesPerPath).map { Path("${parent.name}-file$it.txt") })
    }

    override suspend fun readFile(path: Path): Result<InputStream> {
        if (path.name == failingFile) {
            return Result.failure(IOException("simulated local read failure for ${path.name}"))
        }
        return Result.success(contentFor(path.name).byteInputStream())
    }

    override suspend fun hashForFile(path: Path): Result<String> =
        Result.success(sha256Hex(contentFor(path.name)))

    override suspend fun getEarliestPossibleBackupDate(parent: Path): Result<Instant> =
        earliestPossibleBackupDateFailure?.let { Result.failure(it) } ?: Result.success(
            earliestPossibleBackupDate
        )

    fun contentFor(fileName: String) = "content for $fileName\n"
}
