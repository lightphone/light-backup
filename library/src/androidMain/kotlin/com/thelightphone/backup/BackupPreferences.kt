package com.thelightphone.backup

import kotlin.time.Duration
import kotlin.time.Instant

// Outcome of the most recent BackupWorker run, written by BackupWorker itself.
sealed class BackupStatus {
    data class Succeeded(val completedAt: Instant) : BackupStatus()

    // The linked account's credentials are dead (RemoteBackupError.Unauthorized). Shouldn't automatically retry
    data object NeedsReauth : BackupStatus()

    // The remote account is out of space (RemoteBackupError.QuotaExceeded). Shouldn't automatically retry
    data object QuotaExceeded : BackupStatus()

    // Anything else, likely transient so retries are ok
    data class Failed(val message: String) : BackupStatus()
}

// Persistent storage for Backup
interface BackupPreferences {
    // User setting for if backups should run. only true if activeprovider (below) is not null as well
    suspend fun getEnabled(): Boolean?
    suspend fun setEnabled(enabled: Boolean)
    // Which provider (if any) the user has linked and wants backups sent to.
    suspend fun getActiveProvider(): RemoteBackupProvider?
    suspend fun setActiveProvider(provider: RemoteBackupProvider?)

    // How often BackupWorker should run.
    suspend fun getBackupInterval(): Duration
    suspend fun setBackupInterval(interval: Duration)

    suspend fun getRequiresCharging(): Boolean
    suspend fun setRequiresCharging(requiresCharging: Boolean)

    // Restricts backups to unmetered networks (wifi) rather than any connection.
    suspend fun getRequiresUnmeteredNetwork(): Boolean
    suspend fun setRequiresUnmeteredNetwork(requiresUnmeteredNetwork: Boolean)

    suspend fun getLastBackupStatus(): BackupStatus?
    suspend fun setLastBackupStatus(status: BackupStatus)
}
