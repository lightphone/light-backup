# light-backup

A LightOS module that automatically/periodically backs up a Light Phone user's data to their own cloud storage
(Google Drive, Dropbox, OneDrive + more to come). Each tool is responsible for managing which of its data
gets backed up and what that data looks like when stored. At backup time, a tool will be presented with
the timestamp of each of its paths' last successful backup, and based on that it will provide a list of
files to be copied onto the user's cloud storage.

Also of interest is
[`OAuthJobDataTree`](library/src/jvmSharedMain/kotlin/com/thelightphone/backup/OAuthJobDataTree.kt),
which demonstrates how to use Tool Manager's `job` abstraction to hook up OAuth login for various
providers.

Though this will be primarily used by LightOS, we will put as much of the actual code that consumes it
inside the `server` module of the SDK, and we will at least hook up one dummy cloud provider in the
LightOS emulator so you can test your own tools' backups.

This repo is open source, but is depended on by shipping Light Phone products - see
[CONTRIBUTING.md](CONTRIBUTING.md) for what kinds of changes we're currently able to accept.

## Architecture

- [**`RemoteBackup`**](library/src/jvmSharedMain/kotlin/com/thelightphone/backup/RemoteBackup.kt) -
  the interface a cloud provider implements: create a directory, upload/list/download files, and
  report each path's last-successful-backup time (`getMostRecentBackupDates()`). See
  [`GoogleDriveRemoteBackup`](library/src/jvmSharedMain/kotlin/com/thelightphone/backup/GoogleDriveRemoteBackup.kt)
  and
  [`DropboxRemoteBackup`](library/src/jvmSharedMain/kotlin/com/thelightphone/backup/DropboxRemoteBackup.kt)
  for examples.
- [**`BackupDataSource`**](library/src/jvmSharedMain/kotlin/com/thelightphone/backup/RemoteBackup.kt) -
  the interface a host app implements to say *what* to back up (`getPathsToBackUp`,
  `getFilesToBackUpForPath`, `readFile`, `hashForFile`). This library ships no implementation, it
  only knows how to move bytes once told which ones.
- [**`BackupRunner`**](library/src/jvmSharedMain/kotlin/com/thelightphone/backup/RemoteBackup.kt) -
  orchestrates one backup pass: walks each path, uploads what's changed since that path's last
  backup, writes its checksum manifest, and records a `_meta` entry for any path that made progress
  (these are used to determine time of last successful backup). Platform-agnostic - on Android it's
  invoked by [`BackupWorker`](library/src/androidMain/kotlin/com/thelightphone/backup/BackupWorker.kt),
  an Android WorkManager `CoroutineWorker`.
- [**`RemoteAccessTokenProvider`** /
  **`StoredOAuthTokenProvider`**](library/src/jvmSharedMain/kotlin/com/thelightphone/backup/RemoteAccessTokenProvider.kt) -
  provider-agnostic OAuth access token caching, refresh, and persistence.
- [**`OAuthTunnelClient`**](library/src/jvmSharedMain/kotlin/com/thelightphone/backup/OAuthTunnelClient.kt) -
  the device-side half of the OAuth linking flow, talking to the companion
  [light-oauth-relay](https://github.com/lightphone/light-oauth-relay) worker. Supports both
  server-side token exchange (`EXCHANGE`, for providers that require a client secret) and
  on-device exchange (`RELAY`).

## Project layout

Most code lives in the `jvmShared[x]` source sets since it does not need to be Android-specific.
Integration tests (which write to actual cloud storage) can be run on a PC.

```
library/src/
  commonMain/     # true common code (currently minimal)
  jvmSharedMain/  # shared between jvm() and androidTarget() - most of the library lives here
  jvmSharedTest/  # tests for the above, run on the jvm() target
  jvmMain/        # jvm()-only code
  androidMain/    # Android-only, mostly boilerplate/wrappers to run backups using WorkManager
```

## Building & testing

```
./gradlew check
```

Most tests are plain unit/fake-based tests. A handful are **contract tests that run against a real
Google Drive or Dropbox account** -
[`RemoteBackupContractTest`](library/src/jvmSharedTest/kotlin/com/thelightphone/backup/RemoteBackupContractTest.kt)
and
[`BackupRunnerContractTest`](library/src/jvmSharedTest/kotlin/com/thelightphone/backup/BackupRunnerContractTest.kt),
each run against both providers via
[`GoogleDriveRemoteBackupContractTest`](library/src/jvmSharedTest/kotlin/com/thelightphone/backup/GoogleDriveRemoteBackupContractTest.kt)/
[`DropboxRemoteBackupContractTest`](library/src/jvmSharedTest/kotlin/com/thelightphone/backup/DropboxRemoteBackupContractTest.kt)
and
[`GoogleBackupRunnerContractTest`](library/src/jvmSharedTest/kotlin/com/thelightphone/backup/GoogleBackupRunnerContractTest.kt)/
[`DropboxBackupRunnerContractTest`](library/src/jvmSharedTest/kotlin/com/thelightphone/backup/DropboxBackupRunnerContractTest.kt) -
they write real files into real cloud storage. They're skipped automatically unless you set
`GOOGLE_DRIVE_TEST_ACCESS_TOKEN` and/or `DROPBOX_TEST_ACCESS_TOKEN` to a valid access token for a
disposable/test account. Use with caution.

