package com.thelightphone.backup

import kotlinx.io.files.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import kotlin.random.Random
import kotlin.time.Clock
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

private const val TIMESTAMP_LENGTH = 20 // "yyyy-MM-ddTHH-mm-ssZ"

internal fun metaFileNameFor(instant: Instant): String {
    val timestamp = Instant.fromEpochSeconds(instant.epochSeconds).toString().replace(':', '-')
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

        for (path in paths) {
            val timeOfLastBackup = timesOfLastBackup[path.label] ?: Instant.DISTANT_PAST
            val files = dataSource
                .getFilesToBackUpForPath(path.localPath, timeOfLastBackup)
                .getOrElse {
                    // Local listing failures are never fatal to the run (see isFatalToBackupRun)
                    failures += BackupFailure(FailureScope.Path(path.label), it)
                    continue
                }

            if (files.isEmpty()) continue

            val timeRemotePath = Path(Path(remoteBackup.rootFolderPath, path.label), todayDirectoryName)

            remoteBackup.createDirectory(timeRemotePath).exceptionOrNull()?.let { cause ->
                val failure = BackupFailure(FailureScope.Path(path.label), cause)
                if (cause.isFatalToBackupRun()) {
                    return abort(filesBackedUp, failures, path.label, todayDirectoryName, 0, failure)
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
                        return abort(filesBackedUp, failures, path.label, todayDirectoryName, pathFilesBackedUp, failure)
                    }
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

            if (checksums.isNotEmpty()) {
                val manifest = buildChecksumManifest(checksums)
                val manifestError = remoteBackup
                    .uploadFile(timeRemotePath, CHECKSUM_MANIFEST_FILE_NAME, manifest.inputStream())
                    .exceptionOrNull()
                if (manifestError != null) {
                    val failure = BackupFailure(FailureScope.Path(path.label), manifestError)
                    if (manifestError.isFatalToBackupRun()) {
                        return abort(filesBackedUp, failures, path.label, todayDirectoryName, pathFilesBackedUp, failure)
                    }
                    failures += failure
                }
            }

            if (pathFilesBackedUp > 0) {
                writeMetaEntry(path.label, todayDirectoryName, pathFilesBackedUp).exceptionOrNull()?.let {
                    failures += BackupFailure(FailureScope.Path(path.label), it)
                }
            }
        }

        return if (failures.isEmpty()) {
            BackupResult.Completed(filesBackedUp)
        } else {
            BackupResult.Partial(filesBackedUp, failures)
        }
    }

    // Writes one _meta entry recording that `label` backed up `filesBackedUp` files as of now -
    // filed under that path's own _meta subfolder (rootFolderPath/label/_meta/<timestamp>
    private suspend fun writeMetaEntry(label: String, directoryName: String, filesBackedUp: Int): Result<Unit> {
        val metaDirectory = Path(Path(remoteBackup.rootFolderPath, label), META_FOLDER_NAME)
        remoteBackup.createDirectory(metaDirectory).getOrElse { return Result.failure(it) }

        val completedAt = clock.now()
        val summary = buildBackupSummary(directoryName, filesBackedUp, completedAt)
        return remoteBackup.uploadFile(metaDirectory, metaFileNameFor(completedAt), summary.inputStream())
    }

    private suspend fun abort(
        filesBackedUp: Int,
        failures: MutableList<BackupFailure>,
        inProgressLabel: String,
        todayDirectoryName: String,
        inProgressPathFilesBackedUp: Int,
        abortedBy: BackupFailure,
    ): BackupResult {
        if (inProgressPathFilesBackedUp > 0) {
            writeMetaEntry(inProgressLabel, todayDirectoryName, inProgressPathFilesBackedUp).exceptionOrNull()?.let {
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