package com.thelightphone.backup

import org.junit.Assume.assumeTrue

// Any WebDAV server works
// spin up local from project root:
//   mkdir -p /tmp/webdav-test-root
//   uvx --from wsgidav==4.3.5 --with cheroot==11.1.2 wsgidav --config .github/webdav-test-server.yaml
//   WEBDAV_TEST_URL=http://127.0.0.1:8099/dav WEBDAV_TEST_USERNAME=test WEBDAV_TEST_PASSWORD=test ./gradlew check
internal fun webDavTestRemoteBackup(rootFolderPath: String, inMemoryUploadLimitBytes: Int? = null): WebDavRemoteBackup {
    val url = System.getenv("WEBDAV_TEST_URL")
    assumeTrue("set WEBDAV_TEST_URL (+ WEBDAV_TEST_USERNAME/WEBDAV_TEST_PASSWORD) to run these", url != null)
    val credentials = WebDavCredentials.Basic(
        System.getenv("WEBDAV_TEST_USERNAME").orEmpty(),
        System.getenv("WEBDAV_TEST_PASSWORD").orEmpty(),
    )
    // Test servers are usually local and plain http.
    return if (inMemoryUploadLimitBytes == null) {
        WebDavRemoteBackup(url!!, credentials, rootFolderPath, allowInsecureHttp = true)
    } else {
        WebDavRemoteBackup(
            url!!, credentials, rootFolderPath,
            inMemoryUploadLimitBytes = inMemoryUploadLimitBytes,
            allowInsecureHttp = true,
        )
    }
}
