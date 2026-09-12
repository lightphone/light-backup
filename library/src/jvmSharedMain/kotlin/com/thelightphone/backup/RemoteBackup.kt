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
    Custom, Google, Dropbox, OneDrive
}

// Name of the folder directly under rootFolderPath where BackupRunner records run summaries
internal const val META_FOLDER_NAME = "_meta"

// Everything needed from a cloud provider to allow backups
// each provider will have an API wrapper that conforms
interface RemoteBackup {
    // Per-path last-successful-backup time, keyed by BackupPath.label, used to determine what
    // should be backed up for each path during the current run. A label absent from the map means
    // that path has never completed a backup - callers should treat it like Instant.DISTANT_PAST.
    // Keeping this per-path (rather than one date for the whole run) means a single misbehaving
    // path doesn't force every other, already-succeeded path to be re-uploaded from scratch.
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

interface BackupDataSource {
    suspend fun getPathsToBackUp(): Result<List<BackupPath>>
    suspend fun getFilesToBackUpForPath(parent: Path, timeOfLastBackup: Instant): Result<List<Path>>
    suspend fun readFile(path: Path): Result<InputStream>

    // SHA-256 hex digest of the file's current contents. Independent of readFile
    suspend fun hashForFile(path: Path): Result<String>
}

// Name of the per-leaf-directory manifest BackupRunner uploads alongside each path's files.
private const val CHECKSUM_MANIFEST_FILE_NAME = "checksums.sha256"

private fun buildChecksumManifest(entries: List<Pair<String, String>>): ByteArray =
    entries.joinToString(separator = "") { (fileName, hash) -> "$hash  $fileName\n" }.toByteArray(Charsets.UTF_8)

// Visible package-wide (not just this file) so each RemoteBackup implementation's
// getMostRecentBackupDates() can parse completedAt (and the per-run path labels) back out of a
// downloaded _meta file.
@Serializable
internal data class BackupSummary(
    val directoryName: String,
    val filesBackedUp: Int,
    val paths: List<String>,
    // ISO-8601 (via Instant.toString())
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
        // structure in backup root in cloud is
        // tool-id/date/file.example
        // where there is one date directory per backup

        val timesOfLastBackup = remoteBackup.getMostRecentBackupDates().getOrElse {
            return BackupResult.Failed(BackupFailure(FailureScope.Run, it))
        }

        val paths = dataSource.getPathsToBackUp().getOrElse {
            return BackupResult.Failed(BackupFailure(FailureScope.Run, it))
        }

        var filesBackedUp = 0
        val failures = mutableListOf<BackupFailure>()
        val backedUpPathLabels = mutableListOf<String>()

        for (path in paths) {
            val timeOfLastBackup = timesOfLastBackup[path.label] ?: Instant.DISTANT_PAST
            val files = dataSource
                .getFilesToBackUpForPath(path.localPath, timeOfLastBackup)
                .getOrElse {
                    // Local listing failures are never fatal to the run (see isFatalToBackupRun)
                    failures += BackupFailure(FailureScope.Path(path.label), it)
                    continue
                }

            // Nothing to do for this path this run - skip creating a directory for it so we don't
            // leave an empty dated folder behind under path.label.
            if (files.isEmpty()) continue

            val timeRemotePath = Path(Path(remoteBackup.rootFolderPath, path.label), todayDirectoryName)

            remoteBackup.createDirectory(timeRemotePath).exceptionOrNull()?.let { cause ->
                val failure = BackupFailure(FailureScope.Path(path.label), cause)
                if (cause.isFatalToBackupRun()) {
                    return abort(filesBackedUp, failures, backedUpPathLabels, todayDirectoryName, failure)
                }
                failures += failure
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
                    if (uploadError.isFatalToBackupRun()) {
                        return abort(filesBackedUp, failures, backedUpPathLabels, todayDirectoryName, failure)
                    }
                    failures += failure
                    continue
                }
                filesBackedUp++
                pathFilesBackedUp++
                // Recorded as soon as the first file lands (rather than after the whole path
                // finishes) so an abort() partway through this path still credits the files that
                // made it before the fatal error.
                if (pathFilesBackedUp == 1) backedUpPathLabels += path.label

                // The file is backed up either way at this point - a hash failure only means it
                // won't be verifiable via the manifest, not that the upload itself is invalid.
                dataSource.hashForFile(file).fold(
                    onSuccess = { hash -> checksums += file.name to hash },
                    onFailure = { failures += BackupFailure(FailureScope.File(path.label, file.name), it) },
                )
            }

            if (checksums.isNotEmpty()) {
                val manifest = buildChecksumManifest(checksums)
                val manifestError = remoteBackup
                    .uploadFile(timeRemotePath, CHECKSUM_MANIFEST_FILE_NAME, manifest.inputStream())
                    .exceptionOrNull()
                if (manifestError != null) {
                    val failure = BackupFailure(FailureScope.Path(path.label), manifestError)
                    if (manifestError.isFatalToBackupRun()) {
                        return abort(filesBackedUp, failures, backedUpPathLabels, todayDirectoryName, failure)
                    }
                    failures += failure
                }
            }
        }

        // Record a _meta entry covering exactly the paths that made progress this run, even if
        // other paths failed - so a later run only re-attempts the paths that failed (or never
        // ran) instead of re-uploading everything behind one shared timeOfLastBackup.
        if (backedUpPathLabels.isNotEmpty()) {
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

    private suspend fun abort(
        filesBackedUp: Int,
        failures: MutableList<BackupFailure>,
        backedUpPathLabels: List<String>,
        todayDirectoryName: String,
        abortedBy: BackupFailure,
    ): BackupResult {
        // Same as the happy-path exit: record whatever progress happened before the fatal error,
        // so the next run doesn't redo it.
        if (backedUpPathLabels.isNotEmpty()) {
            writeMetaSummary(todayDirectoryName, filesBackedUp, backedUpPathLabels).exceptionOrNull()?.let {
                failures += BackupFailure(FailureScope.Summary, it)
            }
        }
        return if (filesBackedUp == 0 && failures.isEmpty()) {
            BackupResult.Failed(abortedBy)
        } else {
            BackupResult.Partial(filesBackedUp, failures, abortedBy)
        }
    }
}