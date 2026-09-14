package com.thelightphone.backup

// Everything BackupWorker needs to actually run a backup, beyond the user-facing settings in
// BackupPreferences.
interface BackupDependencyProvider {
    // A token provider for the linked account of this provider type.
    fun createTokenProvider(provider: RemoteBackupProvider): RemoteAccessTokenProvider?

    // What to back up. Independent of which cloud provider the run is targeting.
    fun createDataSource(): BackupDataSource

    // For (re-)authenticating an account
    fun createOAuthTunnelClient(provider: RemoteBackupProvider): OAuthTunnelClient?

    // Root folder within the provider's storage that backups are written under.
    fun rootFolderPath(): String

    fun buildCustomRemoteBackup(remoteAccessTokenProvider: RemoteAccessTokenProvider, rootFilePath: String): RemoteBackup? = null
}
