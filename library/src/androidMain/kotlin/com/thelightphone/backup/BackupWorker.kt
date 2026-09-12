package com.thelightphone.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.toJavaDuration

// Runs one BackupRunner pass
class BackupWorker(
    context: Context,
    params: WorkerParameters,
    private val preferences: BackupPreferences,
    private val dependencyProvider: BackupDependencyProvider,
    private val clock: Clock = Clock.System,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        preferences.getEnabled() ?: return Result.success()
        val provider = preferences.getActiveProvider() ?: return Result.success()
        val remoteBackup = buildRemoteBackup(provider) ?: return Result.failure()

        val runner = BackupRunner(remoteBackup, dependencyProvider.createDataSource(), clock)
        return when (val result = runner.run(todayDirectoryName(clock.now()))) {
            is BackupResult.Completed -> {
                preferences.setLastBackupStatus(BackupStatus.Succeeded(clock.now()))
                Result.success()
            }

            is BackupResult.Partial -> recordFailureAndDecide(result.abortedBy?.cause)
            is BackupResult.Failed -> recordFailureAndDecide(result.cause.cause)
        }
    }

    private suspend fun recordFailureAndDecide(cause: Throwable?): Result = when (cause) {
        is RemoteBackupError.Unauthorized -> {
            preferences.setLastBackupStatus(BackupStatus.NeedsReauth)
            Result.failure()
        }

        is RemoteBackupError.QuotaExceeded -> {
            preferences.setLastBackupStatus(BackupStatus.QuotaExceeded)
            Result.failure()
        }

        else -> {
            preferences.setLastBackupStatus(BackupStatus.Failed(cause?.message ?: "backup failed"))
            Result.retry()
        }
    }

    private fun buildRemoteBackup(provider: RemoteBackupProvider): RemoteBackup? {
        val tokenProvider = dependencyProvider.createTokenProvider(provider) ?: return null
        val rootFolderPath = dependencyProvider.rootFolderPath()
        return when (provider) {
            RemoteBackupProvider.Google -> GoogleDriveRemoteBackup(tokenProvider, rootFolderPath)
            RemoteBackupProvider.Dropbox -> DropboxRemoteBackup(tokenProvider, rootFolderPath)
            RemoteBackupProvider.OneDrive -> null // no RemoteBackup implementation yet
            RemoteBackupProvider.Custom -> dependencyProvider.buildCustomRemoteBackup(
                tokenProvider,
                rootFolderPath
            )
        }
    }
}

// "yyyy-MM-ddTHH-mm-ssZ"
private fun todayDirectoryName(now: Instant): String =
    Instant.fromEpochSeconds(now.epochSeconds).toString().replace(":", "-")

// use a worker factory:
//   class App : Application(), Configuration.Provider {
//       override val workManagerConfiguration
//           get() = Configuration.Builder()
//               .setWorkerFactory(BackupWorkerFactory(preferences, dependencyProvider))
//               .build()
//   }
class BackupWorkerFactory(
    private val preferences: BackupPreferences,
    private val dependencyProvider: BackupDependencyProvider,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        BackupWorker::class.java.name -> BackupWorker(
            appContext,
            workerParameters,
            preferences,
            dependencyProvider
        )

        else -> null
    }
}

// WorkManager rejects periodic intervals under 15 minutes (PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS).
private val MIN_BACKUP_INTERVAL = PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS.milliseconds

object BackupScheduler {
    private const val PERIODIC_WORK_NAME = "light-backup-periodic"
    private const val IMMEDIATE_WORK_NAME = "light-backup-immediate"

    // call on LightOS boot and/or when preferences change
    suspend fun syncSchedule(context: Context, preferences: BackupPreferences) {
        if (preferences.getActiveProvider() == null) {
            cancel(context)
            return
        }

        val interval = preferences.getBackupInterval().coerceAtLeast(MIN_BACKUP_INTERVAL)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (preferences.getRequiresUnmeteredNetwork()) NetworkType.UNMETERED else NetworkType.CONNECTED,
            )
            .setRequiresCharging(preferences.getRequiresCharging())
            .build()

        val request = PeriodicWorkRequestBuilder<BackupWorker>(interval.toJavaDuration())
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
    }

    fun enqueueImmediateBackup(context: Context) {
        val request = OneTimeWorkRequestBuilder<BackupWorker>().build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(IMMEDIATE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}
