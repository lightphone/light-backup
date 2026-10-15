package com.thelightphone.backup

import at.bitfire.dav4jvm.ktor.DavCollection
import at.bitfire.dav4jvm.ktor.DavResource
import at.bitfire.dav4jvm.ktor.PreemptiveBasicDigestAuthProvider
import at.bitfire.dav4jvm.ktor.Response
import at.bitfire.dav4jvm.ktor.exception.DavException
import at.bitfire.dav4jvm.ktor.exception.HttpException
import at.bitfire.dav4jvm.ktor.responsesWithRelation
import at.bitfire.dav4jvm.property.webdav.ResourceType
import at.bitfire.dav4jvm.property.webdav.WebDAV
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.AuthProvider
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// API Wrapping mostly LLM'ed, reviewed by Guy

// RFC 4918. Not in Ktor's HttpStatusCode constants.
private const val INSUFFICIENT_STORAGE = 507

// Wraps a generic WebDAV (RFC 4918) server - Nextcloud, ownCloud, Synology, Apache mod_dav,
// nginx dav, rclone serve webdav, etc. - via dav4jvm, which handles the protocol itself (XML,
// multistatus parsing, href resolution, redirects). Every path is resolved relative to serverUrl.
//
// Uses only the core verbs (PROPFIND/MKCOL/PUT/GET), so no server-specific extensions like
// Nextcloud's chunked uploads - each file is a single PUT. Uploads are buffered (in memory if
// small, otherwise to a temp file) first, so the PUT carries a Content-Length (some servers
// reject chunked transfer encoding) and can be retried after a transient failure.
//
// httpClient is extended with auth (scoped to serverUrl's exact host, so a redirect elsewhere
// never receives credentials) and has redirect-following turned off, since dav4jvm follows
// redirects itself (refusing https -> http ones).
class WebDavRemoteBackup(
    serverUrl: String,
    private val credentials: WebDavCredentials,
    override val rootFolderPath: String,
    httpClient: HttpClient = HttpClient(CIO),
    private val maxRetries: Int = 5,
    // Uploads at most this big are buffered in memory, anything larger spills to a temp file.
    private val inMemoryUploadLimitBytes: Int = 8 * 1024 * 1024,
    // Where oversized uploads are buffered. null means java.io.tmpdir (the app's cache dir on Android).
    private val tempDirectory: File? = null,
    allowInsecureHttp: Boolean = false,
) : RemoteBackup {
    // Checked on the raw string - Ktor's Url parser quietly turns "nas.local" into
    // "http://localhost/nas.local" rather than rejecting it.
    init {
        val scheme = serverUrl.substringBefore("://", missingDelimiterValue = "").lowercase()
        require(scheme == "https" || scheme == "http") { "serverUrl must be an http(s) URL" }
        require(scheme == "https" || allowInsecureHttp) {
            "refusing to send credentials over plain http - pass allowInsecureHttp = true to allow it"
        }
    }

    private val baseUrl = Url(serverUrl)

    private val client = httpClient.config {
        followRedirects = false
        expectSuccess = false
        // Longer than the other providers' - a single PUT carries the whole file.
        install(HttpTimeout) { requestTimeoutMillis = REQUEST_TIMEOUT.inWholeMilliseconds }
        install(Auth) { providers += HostScopedAuthProvider(baseUrl.host, authProviderFor(credentials)) }
    }

    // Collections known to exist this session, keyed by segments joined with "/". Spares
    // createDirectory from re-issuing MKCOL for shared prefixes (rootFolderPath, each label, ...).
    private val knownDirectories = ConcurrentHashMap.newKeySet<String>()

    // Checks that serverUrl is reachable and accepts these credentials, without writing anything.
    // Meant for the account-linking UI, since unlike OAuth there's no linking flow to validate
    // credentials as a side effect. Failure is a RemoteBackupError - Unauthorized for bad
    // credentials, NotFound for a wrong URL.
    suspend fun verify(): Result<Unit> {
        val url = webDavUrl(baseUrl, emptyList(), trailingSlash = true)
        return davCall(url) { DavResource(client, url).propfind(0, WebDAV.ResourceType).toList() }.map { }
    }

    override suspend fun getMostRecentBackupDates(): Result<Map<String, Instant>> {
        // Each backed-up path is its own top-level folder under rootFolderPath (see BackupRunner) -
        // list those rather than assuming any particular set of labels, since this call has no
        // knowledge of what the current run's paths are.
        val labels = listSubdirectories(Path(rootFolderPath)).getOrElse { error ->
            return if (error is RemoteBackupError.NotFound) Result.success(emptyMap()) else Result.failure(error)
        }

        val mostRecentByLabel = mutableMapOf<String, Instant>()
        for (label in labels) {
            // WebDAV has no server-side sort/limit - but meta filenames are chosen (see
            // metaFileNameFor) so plain name ordering matches chronological ordering, so the max
            // name is this path's last-backup date and nothing needs downloading.
            val fileNames = listFiles(Path(Path(rootFolderPath, label), META_FOLDER_NAME)).getOrElse { error ->
                if (error is RemoteBackupError.NotFound) continue else return Result.failure(error)
            }

            val latestName = fileNames.maxOrNull() ?: continue
            val completedAt = parseMetaFileName(latestName)
                ?: return Result.failure(RemoteBackupError.Unknown("unparsable _meta file name for $label: $latestName"))
            mostRecentByLabel[label] = completedAt
        }
        return Result.success(mostRecentByLabel)
    }

    // MKCOL only creates one level and fails if the parent is missing, so walk down from the
    // first segment creating each in turn.
    override suspend fun createDirectory(path: Path): Result<Unit> {
        val segments = path.segments()
        for (depth in 1..segments.size) {
            val prefix = segments.take(depth)
            val cacheKey = prefix.joinToString("/")
            if (cacheKey in knownDirectories) continue

            val url = webDavUrl(baseUrl, prefix, trailingSlash = true)
            davCall(
                url,
                // 405 is RFC 4918's "something already exists at this URL" - createDirectory is
                // documented as idempotent. (Strictly that something could be a plain file, in
                // which case the next upload into it fails instead.)
                onHttpError = { if (it.statusCode == 405) Result.success(Unit) else null },
            ) {
                DavResource(client, url).mkCol(xmlBody = null) { }
            }.getOrElse { return Result.failure(it) }
            knownDirectories += cacheKey
        }
        return Result.success(Unit)
    }

    override suspend fun uploadFile(remoteDirectory: Path, fileName: String, data: InputStream): Result<Unit> {
        val url = webDavUrl(baseUrl, remoteDirectory.segments() + fileName, trailingSlash = false)
        val body = try {
            withContext(Dispatchers.IO) { bufferUpload(data) }
        } catch (e: IOException) {
            return Result.failure(e)
        } finally {
            runCatching { data.close() }
        }

        return try {
            davCall(
                url,
                // RFC 4918 says 409 for "parent collection doesn't exist", some servers say 404.
                onHttpError = {
                    if (it.statusCode == 409 || it.statusCode == 404) {
                        Result.failure(RemoteBackupError.NotFound(remoteDirectory.toString()))
                    } else {
                        null
                    }
                },
            ) {
                DavResource(client, url).put(body.toOutgoingContent()) { }
            }
        } finally {
            body.discard()
        }
    }

    override suspend fun listFiles(remoteDirectory: Path): Result<List<String>> =
        listChildren(remoteDirectory).map { children -> children.filterNot { it.isCollection() }.map { it.hrefName() } }

    override suspend fun listSubdirectories(remoteDirectory: Path): Result<List<String>> =
        listChildren(remoteDirectory).map { children -> children.filter { it.isCollection() }.map { it.hrefName() } }

    override suspend fun downloadFile(remoteDirectory: Path, fileName: String): Result<InputStream> {
        val url = webDavUrl(baseUrl, remoteDirectory.segments() + fileName, trailingSlash = false)
        return davCall(url) {
            DavResource(client, url).get { response -> ByteArrayInputStream(response.bodyAsBytes()) }
        }
    }

    private suspend fun listChildren(remoteDirectory: Path): Result<List<Response>> {
        val segments = remoteDirectory.segments()
        val url = webDavUrl(baseUrl, segments, trailingSlash = true)
        return davCall(url) {
            DavCollection(client, url).propfind(1, WebDAV.ResourceType)
                .responsesWithRelation()
                .toList()
                // A Depth: 1 PROPFIND includes the collection itself alongside its children.
                .filter { it.relation == Response.HrefRelation.MEMBER }
                .map { it.response }
        }.onSuccess { knownDirectories += segments.joinToString("/") }
    }

    private fun Response.isCollection(): Boolean =
        this[ResourceType::class.java]?.types?.contains(WebDAV.Collection) == true

    private fun bufferUpload(data: InputStream): UploadBody {
        val head = data.readChunk(inMemoryUploadLimitBytes)
        if (head.size < inMemoryUploadLimitBytes) return UploadBody.InMemory(head)

        val file = File.createTempFile("light-backup-upload", ".tmp", tempDirectory)
        try {
            file.outputStream().use { out ->
                out.write(head)
                data.copyTo(out)
            }
        } catch (e: IOException) {
            file.delete()
            throw e
        }
        return UploadBody.TempFile(file)
    }

    // Runs one dav4jvm call, retrying on transient failures (connection errors, 429, 5xx) with
    // backoff. A 401 is only retried for Bearer credentials (once, after invalidating the token) -
    // Basic credentials can't be refreshed, so a 401 there means they're simply wrong.
    //
    // Any other HTTP error status is offered to onHttpError first, for call-specific meanings
    // (e.g. MKCOL's 405); returning null falls back to 404 -> NotFound, anything else -> Unknown.
    private suspend fun <T> davCall(
        url: Url,
        onHttpError: (HttpException) -> Result<T>? = { null },
        block: suspend () -> T,
    ): Result<T> {
        var attempt = 0
        var refreshedOnce = false
        var lastError: RemoteBackupError = RemoteBackupError.Unknown("no attempts made")

        while (attempt < maxRetries) {
            attempt++
            try {
                return Result.success(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                when (e.statusCode) {
                    401 -> {
                        if (credentials !is WebDavCredentials.Bearer || refreshedOnce) {
                            return Result.failure(RemoteBackupError.Unauthorized(e))
                        }
                        refreshedOnce = true
                        credentials.tokenProvider.invalidateAccessToken()
                    }
                    429 -> {
                        lastError = RemoteBackupError.RateLimited(cause = e)
                        delayBeforeRetry(attempt)
                    }
                    // 507 is in the 5xx range but isn't transient.
                    INSUFFICIENT_STORAGE -> return Result.failure(RemoteBackupError.QuotaExceeded(e))
                    in 500..599 -> {
                        lastError = RemoteBackupError.Unavailable(e)
                        delayBeforeRetry(attempt)
                    }
                    else -> return onHttpError(e) ?: Result.failure(
                        if (e.statusCode == 404) {
                            RemoteBackupError.NotFound(url.toString(), e)
                        } else {
                            RemoteBackupError.Unknown("HTTP ${e.statusCode} for $url", e)
                        },
                    )
                }
            } catch (e: DavException) {
                // Protocol-level problems (no/invalid multistatus, bad redirect) - retrying won't help.
                return Result.failure(RemoteBackupError.Unknown(e.message ?: "WebDAV error for $url", e))
            } catch (e: Exception) {
                lastError = RemoteBackupError.Unavailable(e)
                delayBeforeRetry(attempt)
            }
        }
        return Result.failure(lastError)
    }

    // AWS-style "full jitter" backoff: sleep a random duration between 0 and the exponential cap,
    // rather than the cap itself, so retries from many concurrent callers don't all land at once.
    private suspend fun delayBeforeRetry(attempt: Int) {
        val cap = (BASE_BACKOFF * (1 shl (attempt - 1).coerceAtMost(10))).coerceAtMost(MAX_BACKOFF)
        delay(Random.nextLong(cap.inWholeMilliseconds.coerceAtLeast(1)))
    }

    private companion object {
        val REQUEST_TIMEOUT = 5.minutes
        val BASE_BACKOFF = 500.milliseconds
        val MAX_BACKOFF = 30.seconds
    }
}

// baseUrl + segments, each segment percent-encoded as a single path segment (a "/" in a name can
// never address a different directory). Collections get a trailing slash - some servers (nginx)
// require it for MKCOL, and others redirect PROPFINDs on a collection without one.
//
// Re-parsed rather than returned straight from URLBuilder: a built Url's rawSegments lack the
// leading "" a parsed one has, which compares equal in Ktor but breaks dav4jvm's segment-by-segment
// check of which PROPFIND responses are members of the requested collection.
internal fun webDavUrl(baseUrl: Url, segments: List<String>, trailingSlash: Boolean): Url =
    Url(
        URLBuilder(baseUrl).apply {
            val base = pathSegments.filter { it.isNotEmpty() }
            pathSegments = base + segments + if (trailingSlash) listOf("") else emptyList()
        }.buildString(),
    )

private fun authProviderFor(credentials: WebDavCredentials): AuthProvider = when (credentials) {
    // dav4jvm's provider sends Basic preemptively, switching to Digest if the server asks for it.
    is WebDavCredentials.Basic -> PreemptiveBasicDigestAuthProvider(credentials.username, credentials.password)
    is WebDavCredentials.Bearer -> TokenProviderAuthProvider(credentials.tokenProvider)
}

// Sends the current token on every request. Refreshing on 401 is left to davCall's retry loop
// (same as the other providers) rather than Ktor's Auth plugin, hence isApplicable = false.
private class TokenProviderAuthProvider(private val tokenProvider: RemoteAccessTokenProvider) : AuthProvider {
    @Deprecated("Please use sendWithoutRequest function instead", level = DeprecationLevel.ERROR)
    override val sendWithoutRequest: Boolean
        get() = error("Deprecated")

    override fun sendWithoutRequest(request: HttpRequestBuilder) = true

    override fun isApplicable(auth: HttpAuthHeader) = false

    override suspend fun addRequestHeaders(request: HttpRequestBuilder, authHeader: HttpAuthHeader?) {
        request.headers.remove(HttpHeaders.Authorization)
        // On failure, send nothing - the resulting 401 surfaces as RemoteBackupError.Unauthorized.
        tokenProvider.getAccessToken().onSuccess { request.headers[HttpHeaders.Authorization] = "Bearer $it" }
    }
}

// Only lets credentials reach serverUrl's exact host. (dav4jvm's own DomainAuthProvider matches on
// the last two labels of the hostname, which would include e.g. every *.co.uk host.)
private class HostScopedAuthProvider(private val host: String, private val delegate: AuthProvider) : AuthProvider by delegate {
    override fun sendWithoutRequest(request: HttpRequestBuilder) =
        request.url.host.equals(host, ignoreCase = true) && delegate.sendWithoutRequest(request)

    override suspend fun addRequestHeaders(request: HttpRequestBuilder, authHeader: HttpAuthHeader?) {
        if (request.url.host.equals(host, ignoreCase = true)) delegate.addRequestHeaders(request, authHeader)
    }
}

// An upload's bytes, held somewhere re-readable so a failed PUT can be retried from the start.
private sealed class UploadBody {
    abstract fun toOutgoingContent(): OutgoingContent
    open fun discard() {}

    class InMemory(val bytes: ByteArray) : UploadBody() {
        override fun toOutgoingContent() = object : OutgoingContent.ByteArrayContent() {
            override val contentType = ContentType.Application.OctetStream
            override val contentLength = bytes.size.toLong()
            override fun bytes() = bytes
        }
    }

    class TempFile(val file: File) : UploadBody() {
        override fun toOutgoingContent() = object : OutgoingContent.ReadChannelContent() {
            override val contentType = ContentType.Application.OctetStream
            override val contentLength = file.length()
            override fun readFrom(): ByteReadChannel = file.inputStream().toByteReadChannel()
        }

        override fun discard() {
            file.delete()
        }
    }
}
