package com.thelightphone.backup

// Provider-agnostic access-token caching

interface RemoteAccessTokenProvider {
    // A currently-valid access token for this account, refreshing it first if it's expired
    suspend fun getAccessToken(): Result<String>

    // Called after the API rejects a token as invalid despite it looking unexpired
    // Forces the next getAccessToken() call to refresh rather than trust
    // the cache.
    suspend fun invalidateAccessToken()
}
