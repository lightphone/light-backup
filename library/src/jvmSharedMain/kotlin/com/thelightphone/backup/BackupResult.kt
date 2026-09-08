package com.thelightphone.backup

sealed class FailureScope {
    // A run-wide setup step: listing paths to back up, looking up the last backup date, or
    // creating this run's root directory. Nothing was backed up.
    data object Run : FailureScope()

    // Everything under one BackupPath: creating its subdirectory, or listing its files.
    data class Path(val label: String) : FailureScope()

    // One specific file: reading it locally, or uploading it.
    data class File(val label: String, val fileName: String) : FailureScope()

    // Writing the _meta summary for an otherwise fully-successful run.
    data object Summary : FailureScope()
}

data class BackupFailure(val scope: FailureScope, val cause: Throwable)

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
internal fun Throwable.isFatalToBackupRun(): Boolean =
    this is RemoteBackupError.Unauthorized || this is RemoteBackupError.QuotaExceeded
