package com.thelightphone.backup

import kotlinx.io.files.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import kotlin.time.Clock
import kotlin.time.Instant

enum class RemoteBackupProvider {
    Google, Dropbox, OneDrive
}

// Name of the folder directly under rootFolderPath where BackupRunner records one file per
// successful run (named after that run's directory name) - see getMostRecentBackupDate below.
internal const val META_FOLDER_NAME = "_meta"

// will need API wrappers for each RemoteBackupProvider
interface RemoteBackup {
    // Used to determine what should be backed up during the current run.
    suspend fun getMostRecentBackupDate(): Result<Instant?>

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

data class BackupPath(val localPath: Path, val label: String)

interface BackupDataSource {
    suspend fun getPathsToBackUp(): Result<List<BackupPath>>
    suspend fun getFilesToBackUpForPath(parent: Path, timeOfLastBackup: Instant): Result<List<Path>>
    suspend fun readFile(path: Path): Result<InputStream>

    // SHA-256 hex digest of the file's current contents. Independent of readFile - implementations
    // are free to compute this via their own read, or return an already-known value.
    suspend fun hashForFile(path: Path): Result<String>
}

// Name of the per-leaf-directory manifest BackupRunner uploads alongside each path's files.
// Standard `sha256sum` format ("<hex>  <filename>" per line), so it can be verified with
// `sha256sum -c checksums.sha256` using nothing but coreutils.
private const val CHECKSUM_MANIFEST_FILE_NAME = "checksums.sha256"

private fun buildChecksumManifest(entries: List<Pair<String, String>>): ByteArray =
    entries.joinToString(separator = "") { (fileName, hash) -> "$hash  $fileName\n" }.toByteArray(Charsets.UTF_8)

// Visible package-wide (not just this file) so each RemoteBackup implementation's
// getMostRecentBackupDate() can parse completedAt back out of a downloaded _meta file.
@Serializable
internal data class BackupSummary(
    val directoryName: String,
    val filesBackedUp: Int,
    val paths: List<String>,
    // ISO-8601 (via Instant.toString()) rather than an Instant field - kotlin.time.Instant has no
    // built-in kotlinx.serialization support.
    val completedAt: String,
)

private val summaryJson = Json { prettyPrint = true }

private fun buildBackupSummary(
    directoryName: String,
    filesBackedUp: Int,
    paths: List<String>,
    completedAt: Instant,
): ByteArray =
    summaryJson.encodeToString(BackupSummary(directoryName, filesBackedUp, paths, completedAt.toString()))
        .toByteArray(Charsets.UTF_8)

class BackupRunner(
    private val remoteBackup: RemoteBackup,
    private val dataSource: BackupDataSource,
    private val clock: Clock = Clock.System,
) {
    suspend fun run(todayDirectoryName: String): BackupResult {
        val timeOfLastBackup = remoteBackup.getMostRecentBackupDate().getOrElse {
            return BackupResult.Failed(BackupFailure(FailureScope.Run, it))
        }

        val paths = dataSource.getPathsToBackUp().getOrElse {
            return BackupResult.Failed(BackupFailure(FailureScope.Run, it))
        }

        var filesBackedUp = 0
        val failures = mutableListOf<BackupFailure>()
        val backedUpPathLabels = mutableListOf<String>()

        for (path in paths) {
            val timeRemotePath = Path(Path(remoteBackup.rootFolderPath, path.label), todayDirectoryName)

            remoteBackup.createDirectory(timeRemotePath).exceptionOrNull()?.let { cause ->
                val failure = BackupFailure(FailureScope.Path(path.label), cause)
                if (cause.isFatalToBackupRun()) return abort(filesBackedUp, failures, failure)
                failures += failure
                continue
            }

            val files = dataSource
                .getFilesToBackUpForPath(path.localPath, timeOfLastBackup ?: Instant.DISTANT_PAST)
                .getOrElse {
                    // Local listing failures are never fatal to the run (see isFatalToBackupRun) -
                    // just move on to the next path.
                    failures += BackupFailure(FailureScope.Path(path.label), it)
                    continue
                }

            val checksums = mutableListOf<Pair<String, String>>()
            var pathFilesBackedUp = 0

            for (file in files) {
                val data = dataSource.readFile(file).getOrElse {
                    failures += BackupFailure(FailureScope.File(path.label, file.name), it)
                    continue
                }
                val uploadError = remoteBackup.uploadFile(timeRemotePath, file.name, data).exceptionOrNull()
                if (uploadError != null) {
                    val failure = BackupFailure(FailureScope.File(path.label, file.name), uploadError)
                    if (uploadError.isFatalToBackupRun()) return abort(filesBackedUp, failures, failure)
                    failures += failure
                    continue
                }
                filesBackedUp++
                pathFilesBackedUp++

                // The file is backed up either way at this point - a hash failure only means it
                // won't be verifiable via the manifest, not that the upload itself is invalid.
                dataSource.hashForFile(file).fold(
                    onSuccess = { hash -> checksums += file.name to hash },
                    onFailure = { failures += BackupFailure(FailureScope.File(path.label, file.name), it) },
                )
            }

            if (pathFilesBackedUp > 0) backedUpPathLabels += path.label

            if (checksums.isNotEmpty()) {
                val manifest = buildChecksumManifest(checksums)
                val manifestError = remoteBackup
                    .uploadFile(timeRemotePath, CHECKSUM_MANIFEST_FILE_NAME, manifest.inputStream())
                    .exceptionOrNull()
                if (manifestError != null) {
                    val failure = BackupFailure(FailureScope.Path(path.label), manifestError)
                    if (manifestError.isFatalToBackupRun()) return abort(filesBackedUp, failures, failure)
                    failures += failure
                }
            }
        }

        // Only record a _meta entry (and so only advance getMostRecentBackupDate) when nothing at
        // all failed.
        if (failures.isEmpty()) {
            writeMetaSummary(todayDirectoryName, filesBackedUp, backedUpPathLabels).exceptionOrNull()?.let {
                failures += BackupFailure(FailureScope.Summary, it)
            }
        }

        return if (failures.isEmpty()) {
            BackupResult.Completed(filesBackedUp)
        } else {
            BackupResult.Partial(filesBackedUp, failures)
        }
    }

    private suspend fun writeMetaSummary(
        todayDirectoryName: String,
        filesBackedUp: Int,
        pathLabels: List<String>,
    ): Result<Unit> {
        val metaDirectory = Path(remoteBackup.rootFolderPath, META_FOLDER_NAME)
        remoteBackup.createDirectory(metaDirectory).getOrElse { return Result.failure(it) }

        val summary = buildBackupSummary(todayDirectoryName, filesBackedUp, pathLabels, clock.now())
        return remoteBackup.uploadFile(metaDirectory, todayDirectoryName, summary.inputStream())
    }

    private fun abort(filesBackedUp: Int, failures: List<BackupFailure>, abortedBy: BackupFailure): BackupResult =
        if (filesBackedUp == 0 && failures.isEmpty()) {
            BackupResult.Failed(abortedBy)
        } else {
            BackupResult.Partial(filesBackedUp, failures, abortedBy)
        }
}