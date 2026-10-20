package com.thelightphone.backup

import io.ktor.client.HttpClient
import io.ktor.client.plugins.observer.ResponseObserver
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.discard
import java.util.concurrent.atomic.AtomicLong

data class BackupTraffic(val bytesSent: Long, val bytesReceived: Long)
class BackupTrafficCounter {
    private val sent = AtomicLong()
    private val received = AtomicLong()

    fun snapshot() = BackupTraffic(sent.get(), received.get())

    internal fun add(bytesSent: Long, bytesReceived: Long) {
        sent.addAndGet(bytesSent)
        received.addAndGet(bytesReceived)
    }
}

// A copy of this client that adds each request's and response's body size to counter.
// NOTE: doesn't count TLS overhead etc., just body bytes. Rough calculation.
fun HttpClient.countingTraffic(counter: BackupTrafficCounter): HttpClient = config {
    install(ResponseObserver) {
        onResponse { response ->
            val sent = response.call.request.content.contentLength ?: 0
            val received = response.bodyAsChannel().discard()
            counter.add(sent, received)
        }
    }
}
