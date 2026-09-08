package com.thelightphone.backup

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// API Wrapping mostly LLM'ed
private const val DRIVE_API = "https://www.googleapis.com/drive/v3"
private const val DRIVE_UPLOAD_API = "https://www.googleapis.com/upload/drive/v3/files"
private const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"

// Drive's magic alias for the account's actual My Drive root - not configurable per-instance
// (unlike the old rootFolderId), since rootFolderPath is now just a named segment resolved like
// any other, always anchored at the account's real root.
private const val DRIVE_ROOT_ALIAS = "root"

// 403 reasons that mean "you're being throttled, back off and retry" rather than a real,
// non-transient rejection (storageQuotaExceeded, dailyLimitExceeded, insufficientPermissions, ...).
private val RETRYABLE_FORBIDDEN_REASONS = setOf("userRateLimitExceeded", "rateLimitExceeded")

@Serializable
private data class DriveFile(val id: String, val name: String? = null)

@Serializable
private data class DriveFileList(val files: List<DriveFile> = emptyList())

@Serializable
private data class DriveCreateFileRequest(val name: String, val mimeType: String? = null, val parents: List<String>? = null)

@Serializable
private data class DriveErrorDetail(val reason: String? = null)

@Serializable
private data class DriveErrorBody(val errors: List<DriveErrorDetail> = emptyList())

@Serializable
private data class DriveErrorEnvelope(val error: DriveErrorBody? = null)

// Wraps the Google Drive v3 API. Directory paths are resolved segment-by-segment under
// [rootFolderPath] (Drive's "root" alias by default, i.e. the account's actual My Drive root).
//
// Requires an HttpClient with redirect-following disabled - resumable uploads rely on Drive's
// (nonstandard) use of HTTP 308 as an "upload not finished yet" signal rather than a real
// redirect, and a client that auto-follows 308s will misinterpret it.
class GoogleDriveRemoteBackup(
    private val tokenProvider: GoogleTokenProvider,
    override val rootFolderPath: String,
    private val httpClient: HttpClient = HttpClient(CIO) {
        followRedirects = false
        expectSuccess = false
    },
    private val maxRetries: Int = 5,
    // Must be a multiple of 256 KiB per Drive's resumable upload requirements (the final chunk of
    // a file is exempt from that rule).
    private val chunkSizeBytes: Int = 8 * 1024 * 1024,
) : RemoteBackup {
    private val json = Json { ignoreUnknownKeys = true }

    // Resolved folder ids for path segments already seen this session, keyed by the segment path
    // joined with "/". Avoids re-walking (and re-querying Drive for) shared prefixes - e.g.
    // createDirectory("Backups/2026-09-04") followed by several
    // createDirectory("Backups/2026-09-04/<label>") calls only resolves "Backups/2026-09-04" once.
    private val folderIdCache = ConcurrentHashMap<String, String>()

    override suspend fun getMostRecentBackupDate(): Result<Instant?> {
        // rootFolderPath/META_FOLDER_NAME are segment names (e.g. "Backups/_meta"), not Drive ids -
        // resolve them the same way any other path segments would be. If either doesn't exist yet,
        // no backup has ever completed successfully.
        val metaId = resolveFolder(listOf(rootFolderPath, META_FOLDER_NAME), createIfMissing = false)
            .getOrElse { error ->
                return if (error is RemoteBackupError.NotFound) Result.success(null) else Result.failure(error)
            }

        val response = apiRequest(HttpMethod.Get, "$DRIVE_API/files") {
            url {
                parameters.append(
                    "q",
                    "'$metaId' in parents and mimeType != '$FOLDER_MIME_TYPE' and trashed = false",
                )
                parameters.append("fields", "files(id,name)")
                parameters.append("pageSize", "1000")
            }
        }.getOrElse { return Result.failure(it) }

        val fileIds = parseBody<DriveFileList>(response).files.map { it.id }
        if (fileIds.isEmpty()) return Result.success(null)

        // completedAt is embedded in each _meta file's content by BackupRunner (see
        // RemoteBackup.getMostRecentBackupDate() for why) rather than read off Drive's own
        // createdTime - so unlike the old orderBy=createdTime/pageSize=1 query, every _meta file
        // has to actually be fetched and compared.
        var mostRecent: Instant? = null
        for (fileId in fileIds) {
            val contentResponse = apiRequest(HttpMethod.Get, "$DRIVE_API/files/$fileId") {
                url { parameters.append("alt", "media") }
            }.getOrElse { return Result.failure(it) }

            val completedAt = runCatching { Instant.parse(parseBody<BackupSummary>(contentResponse).completedAt) }
                .getOrElse {
                    return Result.failure(RemoteBackupError.Unknown("unparsable _meta content for file $fileId", it))
                }
            if (mostRecent == null || completedAt > mostRecent) mostRecent = completedAt
        }
        return Result.success(mostRecent)
    }

    override suspend fun createDirectory(path: Path): Result<Unit> =
        resolveFolder(path.segments(), createIfMissing = true).map { }

    override suspend fun uploadFile(remoteDirectory: Path, fileName: String, data: InputStream): Result<Unit> {
        val folderId = resolveFolder(remoteDirectory.segments(), createIfMissing = false)
            .getOrElse { return Result.failure(it) }
        return try {
            resumableUpload(folderId, fileName, data)
        } finally {
            runCatching { data.close() }
        }
    }

    override suspend fun listFiles(remoteDirectory: Path): Result<List<String>> =
        listChildren(remoteDirectory, isFolder = false)

    override suspend fun listSubdirectories(remoteDirectory: Path): Result<List<String>> =
        listChildren(remoteDirectory, isFolder = true)

    override suspend fun downloadFile(remoteDirectory: Path, fileName: String): Result<InputStream> {
        val folderId = resolveFolder(remoteDirectory.segments(), createIfMissing = false)
            .getOrElse { return Result.failure(it) }
        val fileId = findChild(folderId, fileName, isFolder = false).getOrElse { return Result.failure(it) }
            ?: return Result.failure(RemoteBackupError.NotFound("$remoteDirectory/$fileName"))

        val response = apiRequest(HttpMethod.Get, "$DRIVE_API/files/$fileId") {
            url { parameters.append("alt", "media") }
        }.getOrElse { return Result.failure(it) }

        return Result.success(ByteArrayInputStream(response.bodyAsBytes()))
    }

    private suspend fun listChildren(remoteDirectory: Path, isFolder: Boolean): Result<List<String>> {
        val folderId = resolveFolder(remoteDirectory.segments(), createIfMissing = false)
            .getOrElse { return Result.failure(it) }
        val mimeClause = mimeClause(isFolder)
        val response = apiRequest(HttpMethod.Get, "$DRIVE_API/files") {
            url {
                parameters.append("q", "'$folderId' in parents and $mimeClause and trashed = false")
                parameters.append("fields", "files(id,name)")
                parameters.append("pageSize", "1000")
            }
        }.getOrElse { return Result.failure(it) }

        return Result.success(parseBody<DriveFileList>(response).files.mapNotNull { it.name })
    }

    // Walks `segments` one Drive folder at a time starting from Drive's actual root, either reusing
    // an existing child folder with a matching name or (if createIfMissing) creating one. Callers
    // are responsible for including rootFolderPath as the first segment themselves when they want
    // to be scoped under it (see BackupRunner) - this always starts at the true Drive root. Not
    // atomic - Drive has no upsert, so two concurrent callers resolving the same missing path could
    // both create it, leaving two folders with the same name. A subsequent retry of either caller
    // re-runs the find step first though, so it self-heals into reusing whichever one won rather
    // than piling up more duplicates.
    private suspend fun resolveFolder(segments: List<String>, createIfMissing: Boolean): Result<String> {
        var parentId = DRIVE_ROOT_ALIAS
        var cacheKey = ""
        for (segment in segments) {
            cacheKey = if (cacheKey.isEmpty()) segment else "$cacheKey/$segment"
            val cached = folderIdCache[cacheKey]
            if (cached != null) {
                parentId = cached
                continue
            }

            val found = findChild(parentId, segment, isFolder = true).getOrElse { return Result.failure(it) }
            val resolvedId = found ?: run {
                if (!createIfMissing) return Result.failure(RemoteBackupError.NotFound(cacheKey))
                createChildFolder(parentId, segment).getOrElse { return Result.failure(it) }
            }
            folderIdCache[cacheKey] = resolvedId
            parentId = resolvedId
        }
        return Result.success(parentId)
    }

    private suspend fun findChild(parentId: String, name: String, isFolder: Boolean): Result<String?> {
        val response = apiRequest(HttpMethod.Get, "$DRIVE_API/files") {
            url {
                parameters.append(
                    "q",
                    "'$parentId' in parents and name = '${escapeForDriveQuery(name)}' " +
                        "and ${mimeClause(isFolder)} and trashed = false",
                )
                parameters.append("pageSize", "1")
                parameters.append("fields", "files(id,name)")
            }
        }.getOrElse { return Result.failure(it) }

        return Result.success(parseBody<DriveFileList>(response).files.firstOrNull()?.id)
    }

    private fun mimeClause(isFolder: Boolean): String =
        if (isFolder) "mimeType = '$FOLDER_MIME_TYPE'" else "mimeType != '$FOLDER_MIME_TYPE'"

    private suspend fun createChildFolder(parentId: String, name: String): Result<String> {
        val response = apiRequest(HttpMethod.Post, "$DRIVE_API/files") {
            url { parameters.append("fields", "id") }
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(DriveCreateFileRequest(name = name, mimeType = FOLDER_MIME_TYPE, parents = listOf(parentId))))
        }.getOrElse { return Result.failure(it) }

        return Result.success(parseBody<DriveFile>(response).id)
    }

    private suspend fun resumableUpload(folderId: String, fileName: String, data: InputStream): Result<Unit> {
        val sessionUri = initiateResumableSession(folderId, fileName).getOrElse { return Result.failure(it) }
        return uploadChunks(sessionUri, data)
    }

    private suspend fun initiateResumableSession(folderId: String, fileName: String): Result<String> {
        val response = apiRequest(HttpMethod.Post, DRIVE_UPLOAD_API) {
            url { parameters.append("uploadType", "resumable") }
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(DriveCreateFileRequest(name = fileName, parents = listOf(folderId))))
        }.getOrElse { return Result.failure(it) }

        val location = response.headers[HttpHeaders.Location]
            ?: return Result.failure(RemoteBackupError.Unknown("resumable session response had no Location header"))
        return Result.success(location)
    }

    // Drives the chunked PUT loop for a resumable session. `chunk`/`chunkStart` always describe
    // the bytes we're currently trying to get the server to confirm - we only pull fresh bytes off
    // `data` once the server has confirmed everything we've sent so far, so a failed attempt can
    // always be retried (or trimmed to just the unconfirmed suffix) without needing to re-read
    // already-consumed stream data.
    private suspend fun uploadChunks(sessionUri: String, data: InputStream): Result<Unit> {
        var chunkStart = 0L
        var chunk = data.readChunk(chunkSizeBytes)
        var isFinalChunk = chunk.size < chunkSizeBytes
        var attempt = 0

        while (true) {
            val total = if (isFinalChunk) chunkStart + chunk.size else null
            when (val outcome = putChunk(sessionUri, chunk, chunkStart, total)) {
                is ChunkPutOutcome.Completed -> return Result.success(Unit)

                is ChunkPutOutcome.Confirmed -> {
                    attempt = 0
                    val fullyAccepted = outcome.confirmedOffset >= chunkStart + chunk.size
                    if (fullyAccepted) {
                        chunkStart += chunk.size
                        if (isFinalChunk) {
                            // The server has every byte but this response didn't finalize the file
                            // (can happen right after resuming the final chunk) - an empty status
                            // PUT against the now-complete range resolves to Completed next time.
                            chunk = ByteArray(0)
                        } else {
                            chunk = data.readChunk(chunkSizeBytes)
                            isFinalChunk = chunk.size < chunkSizeBytes
                        }
                    } else {
                        val acceptedLocally = (outcome.confirmedOffset - chunkStart).toInt().coerceIn(0, chunk.size)
                        chunkStart = outcome.confirmedOffset
                        chunk = chunk.copyOfRange(acceptedLocally, chunk.size)
                    }
                }

                is ChunkPutOutcome.Retryable -> {
                    attempt++
                    if (attempt > maxRetries) return Result.failure(outcome.error)
                    delayBeforeRetry(attempt, (outcome.error as? RemoteBackupError.RateLimited)?.retryAfter)

                    // We don't know whether the server actually received (some of) the chunk we
                    // just failed to get a response for, so ask before resending.
                    val confirmed = queryUploadOffset(sessionUri, total).getOrElse { return Result.failure(it) }
                    val acceptedLocally = (confirmed - chunkStart).toInt().coerceIn(0, chunk.size)
                    chunkStart = confirmed
                    chunk = chunk.copyOfRange(acceptedLocally, chunk.size)
                }

                is ChunkPutOutcome.Fatal -> return Result.failure(outcome.error)
            }
        }
    }

    private suspend fun queryUploadOffset(sessionUri: String, total: Long?): Result<Long> =
        when (val outcome = putChunk(sessionUri, ByteArray(0), 0, total)) {
            is ChunkPutOutcome.Confirmed -> Result.success(outcome.confirmedOffset)
            // Already finished server-side despite the earlier response never reaching us - report
            // everything as confirmed so the caller's next iteration lands on a plain Completed check.
            is ChunkPutOutcome.Completed -> Result.success(total ?: Long.MAX_VALUE)
            is ChunkPutOutcome.Retryable -> Result.failure(outcome.error)
            is ChunkPutOutcome.Fatal -> Result.failure(outcome.error)
        }

    // A single PUT against a resumable session URI - either real chunk bytes, or (when `bytes` is
    // empty) a status query per Drive's resumable upload protocol. Bypasses apiRequest() because
    // its retry loop doesn't understand 308 ("keep going") as a non-error outcome.
    private suspend fun putChunk(sessionUri: String, bytes: ByteArray, start: Long, total: Long?): ChunkPutOutcome {
        val token = tokenProvider.getAccessToken().getOrElse {
            return ChunkPutOutcome.Fatal(it as? RemoteBackupError ?: RemoteBackupError.Unauthorized(it))
        }

        val response = try {
            httpClient.put(sessionUri) {
                header(HttpHeaders.Authorization, "Bearer $token")
                val range = "${if (bytes.isEmpty()) "*" else "$start-${start + bytes.size - 1}"}/${total ?: "*"}"
                header(HttpHeaders.ContentRange, "bytes $range")
                if (bytes.isNotEmpty()) {
                    contentType(ContentType.Application.OctetStream)
                    setBody(bytes)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ChunkPutOutcome.Retryable(RemoteBackupError.Unavailable(e))
        }

        return when (response.status.value) {
            200, 201 -> ChunkPutOutcome.Completed
            308 -> {
                // "bytes=0-524287" -> the server has confirmed bytes [0, 524288). Missing entirely
                // means it has nothing yet.
                val confirmed = response.headers[HttpHeaders.Range]
                    ?.substringAfter('-')
                    ?.toLongOrNull()
                    ?.plus(1)
                    ?: 0L
                ChunkPutOutcome.Confirmed(confirmed)
            }
            401 -> {
                tokenProvider.invalidateAccessToken()
                ChunkPutOutcome.Retryable(RemoteBackupError.Unauthorized())
            }
            404, 410 -> ChunkPutOutcome.Fatal(RemoteBackupError.NotFound(sessionUri))
            429 -> ChunkPutOutcome.Retryable(RemoteBackupError.RateLimited(retryAfterOf(response)))
            in 500..599 -> ChunkPutOutcome.Retryable(RemoteBackupError.Unavailable(IOException("HTTP ${response.status.value}")))
            else -> ChunkPutOutcome.Fatal(RemoteBackupError.Unknown("HTTP ${response.status.value}: ${response.bodyAsText()}"))
        }
    }

    // Issues a single request, retrying on transient failures (connection errors, 429, 5xx) with
    // backoff, and refreshing the access token once if the first attempt comes back 401. Not used
    // for resumable upload chunk PUTs - see putChunk().
    private suspend fun apiRequest(
        method: HttpMethod,
        requestUrl: String,
        block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): Result<HttpResponse> {
        var attempt = 0
        var refreshedOnce = false
        var lastError: RemoteBackupError = RemoteBackupError.Unknown("no attempts made")

        while (attempt < maxRetries) {
            attempt++
            val token = tokenProvider.getAccessToken().getOrElse { return Result.failure(it) }

            val response = try {
                httpClient.request(requestUrl) {
                    this.method = method
                    timeout { requestTimeoutMillis = REQUEST_TIMEOUT.inWholeMilliseconds }
                    header(HttpHeaders.Authorization, "Bearer $token")
                    block()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = RemoteBackupError.Unavailable(e)
                delayBeforeRetry(attempt, null)
                continue
            }

            when {
                response.status == HttpStatusCode.Unauthorized && !refreshedOnce -> {
                    refreshedOnce = true
                    tokenProvider.invalidateAccessToken()
                }
                response.status == HttpStatusCode.Unauthorized -> {
                    return Result.failure(RemoteBackupError.Unauthorized())
                }
                response.status == HttpStatusCode.TooManyRequests -> {
                    lastError = RemoteBackupError.RateLimited(retryAfterOf(response))
                    delayBeforeRetry(attempt, retryAfterOf(response))
                }
                response.status == HttpStatusCode.Forbidden -> {
                    val reason = driveErrorReason(response.bodyAsText())
                    when {
                        reason == "storageQuotaExceeded" -> return Result.failure(RemoteBackupError.QuotaExceeded())
                        reason in RETRYABLE_FORBIDDEN_REASONS -> {
                            lastError = RemoteBackupError.RateLimited(retryAfterOf(response))
                            delayBeforeRetry(attempt, retryAfterOf(response))
                        }
                        else -> return Result.failure(RemoteBackupError.Unknown("forbidden: ${reason ?: "unknown reason"}"))
                    }
                }
                response.status == HttpStatusCode.NotFound -> {
                    return Result.failure(RemoteBackupError.NotFound(requestUrl))
                }
                response.status.value in 500..599 -> {
                    lastError = RemoteBackupError.Unavailable(IOException("HTTP ${response.status.value}"))
                    delayBeforeRetry(attempt, null)
                }
                !response.status.isSuccess() -> {
                    return Result.failure(RemoteBackupError.Unknown("HTTP ${response.status.value}: ${response.bodyAsText()}"))
                }
                else -> return Result.success(response)
            }
        }
        return Result.failure(lastError)
    }

    private suspend inline fun <reified T> parseBody(response: HttpResponse): T =
        json.decodeFromString(response.bodyAsText())

    private fun driveErrorReason(bodyText: String): String? =
        runCatching { json.decodeFromString<DriveErrorEnvelope>(bodyText).error?.errors?.firstOrNull()?.reason }
            .getOrNull()

    private fun retryAfterOf(response: HttpResponse): Duration? =
        response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.seconds

    // AWS-style "full jitter" backoff: sleep a random duration between 0 and the exponential cap,
    // rather than the cap itself, so retries from many concurrent callers don't all land at once.
    private suspend fun delayBeforeRetry(attempt: Int, retryAfter: Duration?) {
        val cap = retryAfter ?: (BASE_BACKOFF * (1 shl (attempt - 1).coerceAtMost(10))).coerceAtMost(MAX_BACKOFF)
        delay(Random.nextLong(cap.inWholeMilliseconds.coerceAtLeast(1)))
    }

    private companion object {
        val REQUEST_TIMEOUT = 30.seconds
        val BASE_BACKOFF = 500.milliseconds
        val MAX_BACKOFF = 30.seconds
    }
}

private sealed class ChunkPutOutcome {
    data object Completed : ChunkPutOutcome()
    data class Confirmed(val confirmedOffset: Long) : ChunkPutOutcome()
    data class Retryable(val error: RemoteBackupError) : ChunkPutOutcome()
    data class Fatal(val error: RemoteBackupError) : ChunkPutOutcome()
}

private fun Path.segments(): List<String> {
    val names = mutableListOf<String>()
    var current: Path? = this
    while (current != null) {
        if (current.name.isNotEmpty()) names += current.name
        current = current.parent
    }
    return names.asReversed()
}

private fun escapeForDriveQuery(value: String): String =
    value.replace("\\", "\\\\").replace("'", "\\'")

// InputStream.readNBytes(int) would do this directly, but it's API 33+ on Android and this module
// targets minSdk 26 - read(ByteArray, Int, Int) has been available since API 1.
private fun InputStream.readChunk(maxLength: Int): ByteArray {
    val buffer = ByteArray(maxLength)
    var totalRead = 0
    while (totalRead < maxLength) {
        val read = read(buffer, totalRead, maxLength - totalRead)
        if (read == -1) break
        totalRead += read
    }
    return if (totalRead == maxLength) buffer else buffer.copyOf(totalRead)
}

data class RefreshedAccessToken(val accessToken: String, val expiresAt: Instant)

sealed class TokenRefreshOutcome {
    data class Refreshed(val token: RefreshedAccessToken) : TokenRefreshOutcome()

    // The refresh token itself is dead (OAuth invalid_grant - revoked, expired, or the user pulled
    // access) rather than the request having merely failed. Retrying won't help; the caller should
    // drop the stored credentials so it can detect "not linked" and prompt to relink.
    data class InvalidGrant(val message: String) : TokenRefreshOutcome()

    // Anything else - network failure, unexpected response shape, etc. Worth retrying later; the
    // stored credentials are still presumed good.
    data class Failed(val cause: Throwable) : TokenRefreshOutcome()
}

// Adapts OAuthTunnelClient.refreshToken's OAuthResult into the TokenRefreshOutcome shape
// StoredGoogleTokenProvider expects, e.g.:
// StoredGoogleTokenProvider(accountType, tokenStorage, refresh = oAuthTunnelClient::refreshAccessToken)
suspend fun OAuthTunnelClient.refreshAccessToken(refreshToken: String): TokenRefreshOutcome {
    val tokens = when (val result = refreshToken(refreshToken)) {
        is OAuthResult.Failure -> {
            return if (result.code == "invalid_grant") {
                TokenRefreshOutcome.InvalidGrant(result.error)
            } else {
                TokenRefreshOutcome.Failed(IllegalStateException(result.error))
            }
        }
        is OAuthResult.Success -> result.tokens.jsonObject
    }

    val accessToken = tokens["access_token"]?.jsonPrimitive?.contentOrNull
    // expires_in is in seconds per the OAuth2 spec (RFC 6749 4.2.2).
    val expiresInSeconds = tokens["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
    if (accessToken == null || expiresInSeconds == null) {
        return TokenRefreshOutcome.Failed(IllegalStateException("missing or invalid refreshed token fields"))
    }

    return TokenRefreshOutcome.Refreshed(RefreshedAccessToken(accessToken, Clock.System.now() + expiresInSeconds.seconds))
}

interface GoogleTokenProvider {
    // A currently-valid access token for this account, refreshing it first if it's expired (or
    // close to it). Fails with RemoteBackupError.Unauthorized if no account is linked, or if the
    // refresh itself fails.
    suspend fun getAccessToken(): Result<String>

    // Called after the API rejects a token as invalid despite it looking unexpired (e.g. it was
    // revoked externally). Forces the next getAccessToken() call to refresh rather than trust
    // the cache.
    suspend fun invalidateAccessToken()
}

// How the actual refresh HTTP call happens (direct to the provider, proxied through a relay that
// holds a client secret, etc.) is intentionally left to the caller - this class only owns caching
// and deciding when a refresh is needed.
class StoredGoogleTokenProvider(
    private val accountType: String,
    private val tokenStorage: TokenStorage,
    private val refresh: suspend (refreshToken: String) -> TokenRefreshOutcome,
) : GoogleTokenProvider {
    // Refreshes are a network round trip plus a re-persist, and every concurrent Drive call needs
    // a token - this mutex means concurrent callers during a refresh all await the one attempt
    // rather than each firing their own.
    private val mutex = Mutex()
    private var cached: StoredOAuthTokens? = null
    private var forceRefresh = false

    override suspend fun getAccessToken(): Result<String> = mutex.withLock {
        val stored = cached ?: tokenStorage.getOAuthDetails(accountType)
        ?: return@withLock Result.failure(
            RemoteBackupError.Unauthorized(IllegalStateException("no $accountType account linked")),
        )
        cached = stored

        val expiringSoon = Clock.System.now() >= stored.expiresAt - EXPIRY_SKEW
        if (!forceRefresh && !expiringSoon) {
            return@withLock Result.success(stored.accessToken)
        }

        when (val outcome = refresh(stored.refreshToken)) {
            is TokenRefreshOutcome.Refreshed -> {
                val updated = stored.copy(
                    accessToken = outcome.token.accessToken,
                    expiresAt = outcome.token.expiresAt,
                )
                tokenStorage.saveOAuthDetails(
                    accountType = accountType,
                    accessToken = updated.accessToken,
                    refreshToken = updated.refreshToken,
                    expiresAt = updated.expiresAt,
                    scope = updated.scope,
                )
                cached = updated
                forceRefresh = false
                Result.success(updated.accessToken)
            }
            is TokenRefreshOutcome.InvalidGrant -> {
                // The refresh token is permanently dead - keeping it around would just mean every
                // future call fails the same way, so drop it and let the caller detect "not linked".
                tokenStorage.removeOAuthDetails(accountType)
                cached = null
                Result.failure(RemoteBackupError.Unauthorized(IllegalStateException(outcome.message)))
            }
            is TokenRefreshOutcome.Failed -> {
                Result.failure(RemoteBackupError.Unauthorized(outcome.cause))
            }
        }
    }

    override suspend fun invalidateAccessToken() = mutex.withLock {
        forceRefresh = true
    }

    private companion object {
        val EXPIRY_SKEW = 2.minutes
    }
}
