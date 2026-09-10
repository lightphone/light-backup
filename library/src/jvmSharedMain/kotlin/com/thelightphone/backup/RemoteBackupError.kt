package com.thelightphone.backup

import kotlin.time.Duration

sealed class RemoteBackupError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    // Credentials are missing, expired, or were rejected even after a refresh attempt.
    class Unauthorized(cause: Throwable? = null) : RemoteBackupError("not authorized", cause)

    // The remote account is out of storage space.
    class QuotaExceeded(cause: Throwable? = null) : RemoteBackupError("remote storage quota exceeded", cause)

    // The provider asked us to slow down. retryAfter is how long it suggested waiting, if given.
    class RateLimited(val retryAfter: Duration? = null, cause: Throwable? = null) :
        RemoteBackupError("rate limited", cause)

    // A path this call expected to already exist wasn't found.
    class NotFound(path: String, cause: Throwable? = null) : RemoteBackupError("not found: $path", cause)

    // Ran out of retries against a transient condition: connection failures, timeouts, 5xx responses.
    class Unavailable(cause: Throwable? = null) : RemoteBackupError("remote service unavailable", cause)

    // A provider-specific error with no dedicated case above.
    class Unknown(message: String, cause: Throwable? = null) : RemoteBackupError(message, cause)
}
