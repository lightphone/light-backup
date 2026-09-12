package com.thelightphone.backup

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.io.files.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// Mostly LLM'ed, reviewed by Guy
private const val DROPBOX_API = "https://api.dropboxapi.com/2"
private const val DROPBOX_CONTENT_API = "https://content.dropboxapi.com/2"

@Serializable
private data class DbxPathArg(val path: String)

@Serializable
private data class DbxCreateFolderRequest(val path: String, val autorename: Boolean = false)

@Serializable
private data class DbxListFolderRequest(val path: String, val recursive: Boolean = false)

@Serializable
private data class DbxListFolderContinueRequest(val cursor: String)

@Serializable
private data class DbxEntry(@SerialName(".tag") val tag: String, val name: String)

@Serializable
private data class DbxListFolderResponse(
    val entries: List<DbxEntry> = emptyList(),
    val cursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
private data class DbxMetadata(@SerialName(".tag") val tag: String)

@Serializable
private data class DbxUploadArg(val path: String, val mode: String = "overwrite", val autorename: Boolean = false)

@Serializable
private data class DbxSessionStartArg(val close: Boolean = false)

@Serializable
private data class DbxUploadSessionStartResponse(@SerialName("session_id") val sessionId: String)

@Serializable
private data class DbxCursor(@SerialName("session_id") val sessionId: String, val offset: Long)

@Serializable
private data class DbxAppendArg(val cursor: DbxCursor, val close: Boolean = false)

@Serializable
private data class DbxCommit(val path: String, val mode: String = "overwrite", val autorename: Boolean = false)

@Serializable
private data class DbxFinishArg(val cursor: DbxCursor, val commit: DbxCommit)

// Wraps the Dropbox API v2.
class DropboxRemoteBackup(
    private val tokenProvider: RemoteAccessTokenProvider,
    override val rootFolderPath: String,
    private val httpClient: HttpClient = HttpClient(CIO),
    private val maxRetries: Int = 5,
    private val chunkSizeBytes: Int = 8 * 1024 * 1024,
) : RemoteBackup {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getMostRecentBackupDates(): Result<Map<String, Instant>> {
        // Each backed-up path is its own top-level folder under rootFolderPath (see BackupRunner) -
        // list those rather than assuming any particular set of labels, since this call has no
        // knowledge of what the current run's paths are.
        val labels = listSubdirectories(Path(rootFolderPath)).getOrElse { error ->
            return if (error is RemoteBackupError.NotFound) Result.success(emptyMap()) else Result.failure(error)
        }

        val mostRecentByLabel = mutableMapOf<String, Instant>()
        for (label in labels) {
            val metaPath = Path(Path(rootFolderPath, label), META_FOLDER_NAME).toDropboxPath()
            // Dropbox's list_folder has no server-side sort/limit like Drive's orderBy - but this
            // list is scoped to one path's own _meta folder now, and (unlike before) nothing gets
            // downloaded: meta filenames are chosen (see metaFileNameFor) so plain name ordering
            // matches chronological ordering, so the max name is this path's last-backup date.
            val fileNames = listChildren(metaPath, tag = "file").getOrElse { error ->
                if (error is RemoteBackupError.NotFound) continue else return Result.failure(error)
            }

            val latestName = fileNames.maxOrNull() ?: continue
            val completedAt = parseMetaFileName(latestName)
                ?: return Result.failure(RemoteBackupError.Unknown("unparsable _meta file name for $label: $latestName"))
            mostRecentByLabel[label] = completedAt
        }
        return Result.success(mostRecentByLabel)
    }

    override suspend fun createDirectory(path: Path): Result<Unit> {
        val dropboxPath = path.toDropboxPath()
        val response = rpcRequest("$DROPBOX_API/files/create_folder_v2", DbxCreateFolderRequest(dropboxPath))
            .getOrElse { return Result.failure(it) }

        if (response.status.isSuccess()) return Result.success(Unit)

        if (response.status == HttpStatusCode.Conflict) {
            val error = dbxErrorField(response.bodyAsText())
            // Already exists as a folder - createDirectory is documented as idempotent.
            if (tagAt(error, "path", "conflict") == "folder") return Result.success(Unit)
        }
        return Result.failure(RemoteBackupError.Unknown("HTTP ${response.status.value}: ${response.bodyAsText()}"))
    }

    override suspend fun uploadFile(remoteDirectory: Path, fileName: String, data: InputStream): Result<Unit> {
        val dropboxPath = Path(remoteDirectory, fileName).toDropboxPath()
        return try {
            // Dropbox's upload endpoints will silently create any missing parent folders, which
            // would break the documented "remoteDirectory must already exist" contract
            // TODO maybe? dunno if we need this. safe to leave
            ensureDirectoryExists(remoteDirectory).getOrElse { return Result.failure(it) }
            resumableUpload(dropboxPath, data)
        } finally {
            runCatching { data.close() }
        }
    }

    override suspend fun listFiles(remoteDirectory: Path): Result<List<String>> =
        listChildren(remoteDirectory.toDropboxPath(), tag = "file")

    override suspend fun listSubdirectories(remoteDirectory: Path): Result<List<String>> =
        listChildren(remoteDirectory.toDropboxPath(), tag = "folder")

    override suspend fun downloadFile(remoteDirectory: Path, fileName: String): Result<InputStream> {
        val dropboxPath = Path(remoteDirectory, fileName).toDropboxPath()
        return downloadContent(dropboxPath).map { ByteArrayInputStream(it) }
    }

    private suspend fun ensureDirectoryExists(remoteDirectory: Path): Result<Unit> {
        val dropboxPath = remoteDirectory.toDropboxPath()
        val response = rpcRequest("$DROPBOX_API/files/get_metadata", DbxPathArg(dropboxPath))
            .getOrElse { return Result.failure(it) }

        if (response.status.isSuccess()) {
            val tag = parseBody<DbxMetadata>(response).tag
            return if (tag == "folder") {
                Result.success(Unit)
            } else {
                Result.failure(RemoteBackupError.NotFound(dropboxPath))
            }
        }
        return Result.failure(pathErrorOrUnexpected(response, dropboxPath))
    }

    private suspend fun listChildren(dropboxPath: String, tag: String): Result<List<String>> {
        var response = rpcRequest("$DROPBOX_API/files/list_folder", DbxListFolderRequest(dropboxPath))
            .getOrElse { return Result.failure(it) }
        if (!response.status.isSuccess()) return Result.failure(pathErrorOrUnexpected(response, dropboxPath))

        val names = mutableListOf<String>()
        var page = parseBody<DbxListFolderResponse>(response)
        names += page.entries.filter { it.tag == tag }.map { it.name }

        while (page.hasMore) {
            val cursor = page.cursor ?: break
            response = rpcRequest("$DROPBOX_API/files/list_folder/continue", DbxListFolderContinueRequest(cursor))
                .getOrElse { return Result.failure(it) }
            if (!response.status.isSuccess()) return Result.failure(pathErrorOrUnexpected(response, dropboxPath))
            page = parseBody<DbxListFolderResponse>(response)
            names += page.entries.filter { it.tag == tag }.map { it.name }
        }
        return Result.success(names)
    }

    private suspend fun downloadContent(dropboxPath: String): Result<ByteArray> {
        val response = contentRequest(
            url = "$DROPBOX_CONTENT_API/files/download",
            apiArg = json.encodeToString(DbxPathArg(dropboxPath)),
            body = null,
        ).getOrElse { return Result.failure(it) }

        if (!response.status.isSuccess()) return Result.failure(pathErrorOrUnexpected(response, dropboxPath))
        return Result.success(response.bodyAsBytes())
    }

    // Skips the upload-session machinery entirely for anything that fits in one chunk - Dropbox's
    // simple /files/upload endpoint handles up to 150MB in a single request. Larger files go
    // through a start/append/finish session
    private suspend fun resumableUpload(dropboxPath: String, data: InputStream): Result<Unit> {
        var chunk = data.readChunk(chunkSizeBytes)
        if (chunk.size < chunkSizeBytes) {
            return simpleUpload(dropboxPath, chunk)
        }

        val sessionId = startUploadSession(chunk).getOrElse { return Result.failure(it) }
        var offset = chunk.size.toLong()

        while (true) {
            chunk = data.readChunk(chunkSizeBytes)
            if (chunk.size < chunkSizeBytes) {
                return finishUploadSession(sessionId, offset, chunk, dropboxPath)
            }
            appendToUploadSession(sessionId, offset, chunk).getOrElse { return Result.failure(it) }
            offset += chunk.size
        }
    }

    private suspend fun simpleUpload(dropboxPath: String, bytes: ByteArray): Result<Unit> {
        val response = contentRequest(
            url = "$DROPBOX_CONTENT_API/files/upload",
            apiArg = json.encodeToString(DbxUploadArg(dropboxPath)),
            body = bytes,
        ).getOrElse { return Result.failure(it) }

        if (response.status.isSuccess()) return Result.success(Unit)
        return Result.failure(uploadErrorOrUnexpected(response, dropboxPath))
    }

    private suspend fun startUploadSession(bytes: ByteArray): Result<String> {
        val response = contentRequest(
            url = "$DROPBOX_CONTENT_API/files/upload_session/start",
            apiArg = json.encodeToString(DbxSessionStartArg()),
            body = bytes,
        ).getOrElse { return Result.failure(it) }

        if (!response.status.isSuccess()) {
            return Result.failure(RemoteBackupError.Unknown("HTTP ${response.status.value}: ${response.bodyAsText()}"))
        }
        return Result.success(parseBody<DbxUploadSessionStartResponse>(response).sessionId)
    }

    private suspend fun appendToUploadSession(sessionId: String, offset: Long, bytes: ByteArray): Result<Unit> {
        val response = contentRequest(
            url = "$DROPBOX_CONTENT_API/files/upload_session/append_v2",
            apiArg = json.encodeToString(DbxAppendArg(DbxCursor(sessionId, offset), close = false)),
            body = bytes,
        ).getOrElse { return Result.failure(it) }

        if (response.status.isSuccess()) return Result.success(Unit)
        return Result.failure(RemoteBackupError.Unknown("HTTP ${response.status.value}: ${response.bodyAsText()}"))
    }

    private suspend fun finishUploadSession(
        sessionId: String,
        offset: Long,
        bytes: ByteArray,
        dropboxPath: String,
    ): Result<Unit> {
        val response = contentRequest(
            url = "$DROPBOX_CONTENT_API/files/upload_session/finish",
            apiArg = json.encodeToString(DbxFinishArg(DbxCursor(sessionId, offset), DbxCommit(dropboxPath))),
            body = bytes,
        ).getOrElse { return Result.failure(it) }

        if (response.status.isSuccess()) return Result.success(Unit)
        return Result.failure(uploadErrorOrUnexpected(response, dropboxPath))
    }

    private suspend fun pathErrorOrUnexpected(response: HttpResponse, path: String): RemoteBackupError {
        val bodyText = response.bodyAsText()
        if (response.status == HttpStatusCode.Conflict) {
            val error = dbxErrorField(bodyText)
            if (tagAt(error, "path") == "not_found") return RemoteBackupError.NotFound(path)
        }
        return RemoteBackupError.Unknown("HTTP ${response.status.value}: $bodyText")
    }

    private suspend fun uploadErrorOrUnexpected(response: HttpResponse, path: String): RemoteBackupError {
        val bodyText = response.bodyAsText()
        if (response.status == HttpStatusCode.Conflict) {
            val error = dbxErrorField(bodyText)
            if (tagAt(error, "path") == "not_found") return RemoteBackupError.NotFound(path)
            // insufficient_space can appear at a couple of different nesting depths depending on
            // which upload endpoint produced it - a substring check on the summary is a more
            // robust fallback than chasing every exact shape.
            if (bodyText.contains("insufficient_space")) return RemoteBackupError.QuotaExceeded()
        }
        return RemoteBackupError.Unknown("HTTP ${response.status.value}: $bodyText")
    }

    // Issues an RPC-style call (JSON body, JSON response) against api.dropboxapi.com, retrying on
    // transient failures with backoff and refreshing the access token once on a 401.
    private suspend inline fun <reified T> rpcRequest(url: String, body: T): Result<HttpResponse> =
        apiRequest(HttpMethod.Post, url) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(body))
        }

    // Content endpoints (upload/download) take their JSON argument via the Dropbox-API-Arg header
    // instead of the body - the body is reserved for raw file bytes (or empty, for download).
    private suspend fun contentRequest(url: String, apiArg: String, body: ByteArray?): Result<HttpResponse> =
        apiRequest(HttpMethod.Post, url) {
            header("Dropbox-API-Arg", apiArg)
            if (body != null) {
                contentType(ContentType.Application.OctetStream)
                setBody(body)
            }
        }

    private suspend fun apiRequest(
        method: HttpMethod,
        requestUrl: String,
        block: HttpRequestBuilder.() -> Unit = {},
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
                response.status.value in 500..599 -> {
                    lastError = RemoteBackupError.Unavailable(IOException("HTTP ${response.status.value}"))
                    delayBeforeRetry(attempt, null)
                }
                // Everything else (including 400/403/409) is handed back to the caller as-is -
                // Dropbox packs most "expected" API errors into a 409 body the caller has to
                // interpret itself (see pathErrorOrUnexpected/uploadErrorOrUnexpected).
                else -> return Result.success(response)
            }
        }
        return Result.failure(lastError)
    }

    private suspend inline fun <reified T> parseBody(response: HttpResponse): T =
        json.decodeFromString(response.bodyAsText())

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

// Dropbox represents its true account root as "" (not "/") and every other path with a leading
// "/" and no trailing slash - e.g. segments ["a", "b"] -> "/a/b", [] -> "".
private fun Path.toDropboxPath(): String {
    val segments = segments()
    return if (segments.isEmpty()) "" else "/" + segments.joinToString("/")
}

private val errorParsingJson = Json { ignoreUnknownKeys = true }

private fun dbxErrorField(bodyText: String): JsonElement? =
    runCatching { errorParsingJson.parseToJsonElement(bodyText).jsonObject["error"] }.getOrNull()

// Walks a chain of object keys inside a Dropbox error body and reads the ".tag" at that point -
// e.g. tagAt(error, "path", "conflict") for {"path": {"conflict": {".tag": "folder"}}}.
private fun tagAt(root: JsonElement?, vararg keys: String): String? {
    var current: JsonElement? = root
    for (key in keys) {
        current = (current as? JsonObject)?.get(key) ?: return null
    }
    return (current as? JsonObject)?.get(".tag")?.jsonPrimitive?.contentOrNull
}
