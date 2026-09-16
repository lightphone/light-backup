package com.thelightphone.backup

sealed class FailureScope {
    // A run-wide setup step failed, nothing was backed up
    data object Run : FailureScope()

    // Everything under one BackupPath failed, nothing was backed up for it
    data class Path(val label: String) : FailureScope()

    // One specific file failed to read or upload
    data class File(val label: String, val fileName: String) : FailureScope()
}

data class BackupFailure(val scope: FailureScope, val cause: Throwable)

// Where "window" is per app, per day
data class BackupProgress(val windowsCompleted: Int, val totalWindows: Int)

sealed class BackupResult {
    // Every planned file was uploaded.
    data class Completed(val filesBackedUp: Int) : BackupResult()

    // At least one file was uploaded
    data class Partial(
        val filesBackedUp: Int,
        val failures: List<BackupFailure>,
        val abortedBy: BackupFailure? = null,
    ) : BackupResult()

    // Nothing was backed up at all
    data class Failed(val cause: BackupFailure) : BackupResult()
}

// Bad auth and no remote storage space left affect every subsequent call, so
// no point continuing
internal fun Throwable.isFatalToBackupRun(): Boolean = (this as? RemoteBackupError)?.let {
    when (it) {
        is RemoteBackupError.QuotaExceeded, is RemoteBackupError.Unauthorized -> true
        is RemoteBackupError.NotFound,
        is RemoteBackupError.RateLimited,
        is RemoteBackupError.Unavailable,
        is RemoteBackupError.Unknown -> false
    }
} ?: false

