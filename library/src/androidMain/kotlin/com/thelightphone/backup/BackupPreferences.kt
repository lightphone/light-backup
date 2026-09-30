package com.thelightphone.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Instant

// Outcome of the most recent (or currently running) BackupWorker run, written by BackupWorker itself.
@Serializable
sealed class BackupStatus {
    // A backup run is currently underway.
    @Serializable
    data class InProgress(val startedAt: Instant) : BackupStatus()

    // A backup run that has finished, success or failure
    sealed interface Terminal {
        val finishedAt: Instant
    }

    @Serializable
    data class Succeeded(override val finishedAt: Instant) : BackupStatus(), Terminal

    // The linked account's credentials are dead (RemoteBackupError.Unauthorized). Shouldn't automatically retry
    @Serializable
    data class NeedsReauth(override val finishedAt: Instant) : BackupStatus(), Terminal

    // The remote account is out of space (RemoteBackupError.QuotaExceeded). Shouldn't automatically retry
    @Serializable
    data class QuotaExceeded(override val finishedAt: Instant) : BackupStatus(), Terminal

    // Anything else, likely transient so retries are ok
    @Serializable
    data class Failed(val message: String, override val finishedAt: Instant) : BackupStatus(), Terminal

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun encode(status: BackupStatus): String = json.encodeToString(status)
        fun decode(value: String): BackupStatus? = runCatching { json.decodeFromString<BackupStatus>(value) }.getOrNull()
    }
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
