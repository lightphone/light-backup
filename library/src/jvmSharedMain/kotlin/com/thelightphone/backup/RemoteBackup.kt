package com.thelightphone.backup

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeoutException
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

enum class RemoteBackupProvider {
    Custom, Google, Dropbox, OneDrive
}

// Name of the subfolder inside each backed-up path's own folder (rootFolderPath/<label>/_meta)
// where BackupRunner records one file per run that backed up at least one file for that path
internal const val META_FOLDER_NAME = "_meta"

// Everything needed from a cloud provider to allow backups
// each provider will have an API wrapper that conforms
interface RemoteBackup {
    // Per-path last-successful-backup time, keyed by BackupPath.label, used to determine what
    // should be backed up for each path during the current run. A label absent from the map means
    // that path has never completed a backup.
    suspend fun getMostRecentBackupDates(): Result<Map<String, Instant>>

    // Idempotent. Failure is a RemoteBackupError.
    suspend fun createDirectory(path: Path): Result<Unit>

    // remoteDirectory must already exist (via createDirectory), failure is RemoteBackupError.NotFound
    // if not. Other failures are also RemoteBackupError, e.g. QuotaExceeded for "not enough space".
    suspend fun uploadFile(
        remoteDirectory: Path,
        fileName: String,
        data: InputStream
    ): Result<Unit>

    // Names of files (not subdirectories) directly inside remoteDirectory.
    suspend fun listFiles(remoteDirectory: Path): Result<List<String>>

    // Names of subdirectories directly inside remoteDirectory.
    suspend fun listSubdirectories(remoteDirectory: Path): Result<List<String>>

    // Downloads a file's full content. Failure is RemoteBackupError.
    // NotFound if remoteDirectory or fileName doesn't exist.
    suspend fun downloadFile(remoteDirectory: Path, fileName: String): Result<InputStream>

    val rootFolderPath: String
}

// Shared by every RemoteBackup implementation that models directories with kotlinx.io Path
internal fun Path.segments(): List<String> {
    val names = mutableListOf<String>()
    var current: Path? = this
    while (current != null) {
        if (current.name.isNotEmpty()) names += current.name
        current = current.parent
    }
    return names.asReversed()
}

// Changed from InputStream.readNBytes(int) for Android SDK 26 compat
internal fun InputStream.readChunk(maxLength: Int): ByteArray {
    val buffer = ByteArray(maxLength)
    var totalRead = 0
    while (totalRead < maxLength) {
        val read = read(buffer, totalRead, maxLength - totalRead)
        if (read == -1) break
        totalRead += read
    }
    return if (totalRead == maxLength) buffer else buffer.copyOf(totalRead)
}

data class BackupPath(val authority: String, val localPath: Path, val label: String)

// label becomes a single path segment directly under rootFolderPath (see BackupRunner.run), so a
// label a client didn't sanitize (e.g. containing "..") must never be allowed to address anything
// outside its own rootFolderPath/<label> subtree - including another label's _meta folder.
internal fun BackupPath.hasValidLabel(): Boolean =
    label.isNotEmpty() && label != "." && label != ".." && '/' !in label && '\\' !in label

interface BackupDataSource {
    suspend fun getPathsToBackUp(): Result<List<BackupPath>>

    // Files under parent that were created/modified within (lowerBound, upperBound] (and should be backed up)
    suspend fun getFilesToBackUpForPath(parent: Path, lowerBound: Instant, upperBound: Instant): Result<List<Path>>

    suspend fun readFile(path: Path): Result<InputStream>

    // SHA-256 hex digest of the file's current contents. Independent of readFile
    suspend fun hashForFile(path: Path): Result<String>

    // The earliest timestamp among files currently under parent that could ever need backing up
    // (likely the oldest file's creation/modified time).
    // BackupRunner uses this as the starting lower bound when a label has never completed a backup
    suspend fun getEarliestPossibleBackupDate(parent: Path): Result<Instant>
}

// Name of the per-leaf-directory manifest BackupRunner uploads alongside each path's files.
private const val CHECKSUM_MANIFEST_FILE_NAME = "checksums.sha256"

private fun buildChecksumManifest(entries: List<Pair<String, String>>): ByteArray =
    entries.joinToString(separator = "") { (fileName, hash) -> "$hash  $fileName\n" }.toByteArray(Charsets.UTF_8)

private const val TIMESTAMP_LENGTH = 20 // "yyyy-MM-ddTHH-mm-ssZ"

// Precision for marking backups as successful
internal fun Instant.truncatedToWholeSeconds(): Instant = Instant.fromEpochSeconds(epochSeconds)

internal fun metaFileNameFor(instant: Instant): String {
    val timestamp = instant.truncatedToWholeSeconds().toString().replace(':', '-')
    val suffix = Random.nextBytes(4).joinToString("") { "%02x".format(it) }
    return "$timestamp-$suffix"
}

internal fun parseMetaFileName(name: String): Instant? = runCatching {
    val (datePart, timePart) = name.take(TIMESTAMP_LENGTH).split("T", limit = 2)
    Instant.parse("${datePart}T${timePart.removeSuffix("Z").replace('-', ':')}Z")
}.getOrNull()

@Serializable
internal data class BackupSummary(
    val directoryName: String,
    val filesBackedUp: Int,
    // ISO-8601 (via Instant.toString())
    val completedAt: String,
)

private val summaryJson = Json { prettyPrint = true }

private fun buildBackupSummary(
    directoryName: String,
    filesBackedUp: Int,
    completedAt: Instant,
): ByteArray =
    summaryJson.encodeToString(BackupSummary(directoryName, filesBackedUp, completedAt.toString()))
        .toByteArray(Charsets.UTF_8)

class BackupRunner(
    private val remoteBackup: RemoteBackup,
    private val dataSource: BackupDataSource,
    private val clock: Clock = Clock.System,
    private val chunkDuration: Duration = 1.days,
    // Called once upfront with the full window count, then again after every window BackupRunner finishes with.
    private val onProgress: suspend (BackupProgress) -> Unit = {},
    private val maxWindowsPerPathPerRun: Int = 365 * 2, // LP3 is about 2 years old at time of typing?
    private val maxFilesPerWindow: Int = 100000,
    private val dataSourceTimeout: Duration = 45.seconds,
    // when a source returns the time of earliest file to be backed up
    // (in the cae where no backup has ever happened for that source)
    // buffer some time before that timestamp so it doesn't get skipped
    private val earliestBackupTimeBeforeBuffer: Duration = 1.days
) {
    // One path's plan for this run: which windows (see chunkWindows) it still needs to catch up
    // on. Computed for every path upfront so the total window count is known before any uploading
    // starts, rather than discovered incrementally as each path is reached.
    private data class PathPlan(val path: BackupPath, val windows: List<BackupWindow>)

    // Every dataSource call BackupRunner makes goes through this, so a hung/deadlocked client
    // implementation degrades to an ordinary (non-fatal) Result.failure instead of blocking the
    // run - and now, since the worker runs as a foreground service, potentially forever.
    private suspend fun <T> dataSourceCall(block: suspend () -> Result<T>): Result<T> =
        withTimeoutOrNull(dataSourceTimeout) { block() }
            ?: Result.failure(TimeoutException("dataSource call did not complete within $dataSourceTimeout"))

    suspend fun run(): BackupResult {
        // structure in backup root in cloud is
        // tool-id/date/file.example
        // where there is one date directory per backup window

        // Truncated so window boundaries line up exactly
        val now = clock.now().truncatedToWholeSeconds()

        val timesOfLastBackup = remoteBackup.getMostRecentBackupDates().getOrElse {
            return BackupResult.Failed(BackupFailure(FailureScope.Run, it))
        }

        val paths = dataSourceCall { dataSource.getPathsToBackUp() }.getOrElse {
            return BackupResult.Failed(BackupFailure(FailureScope.Run, it))
        }

        var filesBackedUp = 0
        val failures = mutableListOf<BackupFailure>()

        val plans = mutableListOf<PathPlan>()
        for (path in paths) {
            if (!path.hasValidLabel()) {
                failures += BackupFailure(
                    FailureScope.Path(path.label),
                    IllegalArgumentException("invalid label \"${path.label}\" - must be a single path segment"),
                )
                continue
            }

            val resumeFrom = timesOfLastBackup[path.label] ?: dataSourceCall {
                dataSource.getEarliestPossibleBackupDate(path.localPath)
                    // buffer one extra day since data sources may report timestamp of oldest file,
                    // which would get skipped since lowerBound is exclusive
                    .map { it - earliestBackupTimeBeforeBuffer }
            }.getOrElse {
                failures += BackupFailure(FailureScope.Path(path.label), it)
                continue
            }
            plans += PathPlan(path, chunkWindows(resumeFrom, now, chunkDuration).take(maxWindowsPerPathPerRun))
        }

        val totalWindows = plans.sumOf { it.windows.size }
        var windowsCompleted = 0
        onProgress(BackupProgress(windowsCompleted, totalWindows))

        for ((path, windows) in plans) {
            for (window in windows) {
                // finally (rather than one increment per exit point below) so every way of
                // leaving this window's body - continuing past it, or returning to abort the run
                // - still advances/reports progress exactly once.
                try {
                    val directoryName = backupDirectoryNameFor(window.upperBound)

                    val reportedFiles = dataSourceCall {
                        dataSource.getFilesToBackUpForPath(path.localPath, window.lowerBound, window.upperBound)
                    }.getOrElse {
                        // Local listing failures are never fatal to the run (see isFatalToBackupRun)
                        failures += BackupFailure(FailureScope.Path(path.label), it)
                        continue
                    }

                    if (reportedFiles.isEmpty()) continue

                    // A window whose true count exceeds the cap is never marked complete (see the
                    // meta-entry check below) - the untouched remainder is retried, not dropped, on
                    // the next run.
                    val truncated = reportedFiles.size > maxFilesPerWindow
                    val files = if (truncated) reportedFiles.take(maxFilesPerWindow) else reportedFiles
                    if (truncated) {
                        failures += BackupFailure(
                            FailureScope.Path(path.label),
                            IllegalStateException(
                                "window reported ${reportedFiles.size} files, exceeding the cap of " +
                                    "$maxFilesPerWindow; uploading the first $maxFilesPerWindow and retrying the rest next run",
                            ),
                        )
                    }

                    val windowRemotePath = Path(Path(remoteBackup.rootFolderPath, path.label), directoryName)

                    remoteBackup.createDirectory(windowRemotePath).exceptionOrNull()?.let { cause ->
                        val failure = BackupFailure(FailureScope.Path(path.label), cause)
                        if (cause.isFatalToBackupRun()) {
                            return abort(filesBackedUp, failures, path.label, directoryName, 0, window.upperBound, failure)
                        }
                        failures += failure
                        continue
                    }

                    val checksums = mutableListOf<Pair<String, String>>()
                    var windowFilesBackedUp = 0

                    for (file in files) {
                        val data = dataSourceCall { dataSource.readFile(file) }.getOrElse {
                            failures += BackupFailure(FailureScope.File(path.label, file.name), it)
                            continue
                        }
                        val uploadError = remoteBackup.uploadFile(windowRemotePath, file.name, data).exceptionOrNull()
                        if (uploadError != null) {
                            val failure = BackupFailure(FailureScope.File(path.label, file.name), uploadError)
                            if (uploadError.isFatalToBackupRun()) {
                                return abort(
                                    filesBackedUp, failures, path.label, directoryName,
                                    windowFilesBackedUp, window.upperBound, failure,
                                )
                            }
                            failures += failure
                            continue
                        }
                        filesBackedUp++
                        windowFilesBackedUp++

                        // The file is backed up either way at this point - a hash failure only means it
                        // won't be verifiable via the manifest, not that the upload itself is invalid.
                        dataSourceCall { dataSource.hashForFile(file) }.fold(
                            onSuccess = { hash -> checksums += file.name to hash },
                            onFailure = { failures += BackupFailure(FailureScope.File(path.label, file.name), it) },
                        )
                    }

                    if (checksums.isNotEmpty()) {
                        val manifest = buildChecksumManifest(checksums)
                        val manifestError = remoteBackup
                            .uploadFile(windowRemotePath, CHECKSUM_MANIFEST_FILE_NAME, manifest.inputStream())
                            .exceptionOrNull()
                        if (manifestError != null) {
                            val failure = BackupFailure(FailureScope.Path(path.label), manifestError)
                            if (manifestError.isFatalToBackupRun()) {
                                return abort(
                                    filesBackedUp, failures, path.label, directoryName,
                                    windowFilesBackedUp, window.upperBound, failure,
                                )
                            }
                            failures += failure
                        }
                    }

                    // Not written if truncated - this window isn't actually done, so resumeFrom
                    // must not advance past it (see the truncation check above).
                    if (windowFilesBackedUp > 0 && !truncated) {
                        writeMetaEntry(path.label, directoryName, windowFilesBackedUp, window.upperBound).exceptionOrNull()?.let {
                            failures += BackupFailure(FailureScope.Path(path.label), it)
                        }
                    }
                } finally {
                    windowsCompleted++
                    onProgress(BackupProgress(windowsCompleted, totalWindows))
                }
            }
        }

        return if (failures.isEmpty()) {
            BackupResult.Completed(filesBackedUp)
        } else {
            BackupResult.Partial(filesBackedUp, failures)
        }
    }

    // Writes one _meta entry recording that `label` backed up `filesBackedUp` files as of completedAt
    private suspend fun writeMetaEntry(
        label: String,
        directoryName: String,
        filesBackedUp: Int,
        completedAt: Instant,
    ): Result<Unit> {
        val metaDirectory = Path(Path(remoteBackup.rootFolderPath, label), META_FOLDER_NAME)
        remoteBackup.createDirectory(metaDirectory).getOrElse { return Result.failure(it) }

        val summary = buildBackupSummary(directoryName, filesBackedUp, completedAt)
        return remoteBackup.uploadFile(metaDirectory, metaFileNameFor(completedAt), summary.inputStream())
    }

    private suspend fun abort(
        filesBackedUp: Int,
        failures: MutableList<BackupFailure>,
        inProgressLabel: String,
        directoryName: String,
        inProgressWindowFilesBackedUp: Int,
        completedAt: Instant,
        abortedBy: BackupFailure,
    ): BackupResult {
        if (inProgressWindowFilesBackedUp > 0) {
            writeMetaEntry(inProgressLabel, directoryName, inProgressWindowFilesBackedUp, completedAt).exceptionOrNull()?.let {
                failures += BackupFailure(FailureScope.Path(inProgressLabel), it)
            }
        }
        return if (filesBackedUp == 0 && failures.isEmpty()) {
            BackupResult.Failed(abortedBy)
        } else {
            BackupResult.Partial(filesBackedUp, failures, abortedBy)
        }
    }
}

// (lowerBound, upperBound] - strictly after lowerBound, up to and including upperBound.
internal data class BackupWindow(val lowerBound: Instant, val upperBound: Instant)

// Splits (from, to] into consecutive windows no longer than chunkDuration
internal fun chunkWindows(from: Instant, to: Instant, chunkDuration: Duration = 1.days): List<BackupWindow> {
    if (from >= to) return emptyList()
    val windows = mutableListOf<BackupWindow>()
    var lowerBound = from
    while (lowerBound < to) {
        val upperBound = minOf(lowerBound + chunkDuration, to)
        windows += BackupWindow(lowerBound, upperBound)
        lowerBound = upperBound
    }
    return windows
}

internal fun backupDirectoryNameFor(instant: Instant): String =
    instant.truncatedToWholeSeconds().toString().replace(':', '-')