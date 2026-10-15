package com.thelightphone.backup

sealed interface RemoteConnection {
    class OAuth(val tokenProvider: RemoteAccessTokenProvider) : RemoteConnection

    class WebDav(
        // The WebDAV collection everything is written under
        val serverUrl: String,
        val credentials: WebDavCredentials,
        // Plain http sends credentials in the clear. CAREFUL!
        val allowInsecureHttp: Boolean = false,
    ) : RemoteConnection
}

sealed interface WebDavCredentials {
    // note: most servers expect an app-specific password here rather than the account's real one.
    class Basic(val username: String, val password: String) : WebDavCredentials {
        override fun toString() = "Basic(username=$username, password=***)"
    }

    // For servers that issue OAuth tokens for WebDAV access (e.g. Nextcloud's OAuth2 app).
    class Bearer(val tokenProvider: RemoteAccessTokenProvider) : WebDavCredentials
}
