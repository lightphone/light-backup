package com.thelightphone.backup

// Everything BackupWorker needs to actually run a backup, beyond the user-facing settings in
// BackupPreferences.
interface BackupDependencyProvider {
    // A linked OAuth account for provider (Google, Dropbox), or null if none is linked.
    suspend fun createOAuthConnection(provider: RemoteBackupProvider): RemoteConnection.OAuth?

    // A linked WebDAV server, or null if none is linked.
    suspend fun createWebDavConnection(): RemoteConnection.WebDav?

    // Can be used if the caller doesn't care about the type of connection, or overwrite for a custom one
    suspend fun createUnspecifiedConnection(provider: RemoteBackupProvider): RemoteConnection? =
        createOAuthConnection(provider) ?: createWebDavConnection()

    // What to back up. Independent of which cloud provider the run is targeting.
    fun createDataSource(): BackupDataSource

    // Root folder within the provider's storage that backups are written under.
    fun rootFolderPath(): String

    // connection is whatever createUnspecifiedConnection() returned.
    fun buildCustomRemoteBackup(connection: RemoteConnection?, rootFolderPath: String): RemoteBackup? = null
}
