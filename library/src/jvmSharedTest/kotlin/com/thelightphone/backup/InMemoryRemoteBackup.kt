package com.thelightphone.backup

import kotlinx.io.files.Path
import java.io.InputStream
import kotlin.time.Instant

internal class InMemoryRemoteBackup(override val rootFolderPath: String = "root") : RemoteBackup {
    private class Dir {
        val files = mutableMapOf<String, ByteArray>()
        val subDirs = mutableSetOf<String>()
    }

    private val dirs = mutableMapOf<String, Dir>()

    // (targetDirectoryName, error)
    private var uploadFailure: Pair<String, Throwable>? = null

    fun failUploadsTo(directoryName: String, error: Throwable) {
        uploadFailure = directoryName to error
    }

    fun clearUploadFailures() {
        uploadFailure = null
    }

    private fun normalize(path: Path): String = path.segments().joinToString("/")

    override suspend fun getMostRecentBackupDates(): Result<Map<String, Instant>> {
        val root = dirs[normalize(Path(rootFolderPath))] ?: return Result.success(emptyMap())
        val result = mutableMapOf<String, Instant>()
        for (label in root.subDirs) {
            val metaDir = dirs[normalize(Path(Path(rootFolderPath, label), META_FOLDER_NAME))] ?: continue
            val latest = metaDir.files.keys.mapNotNull { parseMetaFileName(it) }.maxOrNull() ?: continue
            result[label] = latest
        }
        return Result.success(result)
    }

    override suspend fun createDirectory(path: Path): Result<Unit> {
        val segments = path.segments()
        var key = ""
        for ((index, segment) in segments.withIndex()) {
            val parentKey = key
            key = if (index == 0) segment else "$key/$segment"
            dirs.getOrPut(key) { Dir() }
            if (index > 0) {
                dirs.getValue(parentKey).subDirs += segment
            }
        }
        return Result.success(Unit)
    }

    override suspend fun uploadFile(remoteDirectory: Path, fileName: String, data: InputStream): Result<Unit> {
        uploadFailure?.let { (directoryName, error) ->
            if (remoteDirectory.name == directoryName) return Result.failure(error)
        }
        val dir = dirs[normalize(remoteDirectory)]
            ?: return Result.failure(RemoteBackupError.NotFound(normalize(remoteDirectory)))
        dir.files[fileName] = data.readBytes()
        return Result.success(Unit)
    }

    override suspend fun listFiles(remoteDirectory: Path): Result<List<String>> {
        val dir = dirs[normalize(remoteDirectory)]
            ?: return Result.failure(RemoteBackupError.NotFound(normalize(remoteDirectory)))
        return Result.success(dir.files.keys.toList())
    }

    override suspend fun listSubdirectories(remoteDirectory: Path): Result<List<String>> {
        val dir = dirs[normalize(remoteDirectory)]
            ?: return Result.failure(RemoteBackupError.NotFound(normalize(remoteDirectory)))
        return Result.success(dir.subDirs.toList())
    }

    override suspend fun downloadFile(remoteDirectory: Path, fileName: String): Result<InputStream> {
        val dir = dirs[normalize(remoteDirectory)]
            ?: return Result.failure(RemoteBackupError.NotFound(normalize(remoteDirectory)))
        val bytes = dir.files[fileName]
            ?: return Result.failure(RemoteBackupError.NotFound("${normalize(remoteDirectory)}/$fileName"))
        return Result.success(bytes.inputStream())
    }
}