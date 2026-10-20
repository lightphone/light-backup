package com.thelightphone.backup

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class BackupTrafficTest {
    private val responseBody = ByteArray(300) { it.toByte() }

    private fun countingClient(counter: BackupTrafficCounter) =
        HttpClient(MockEngine { respond(responseBody) }).countingTraffic(counter)

    // the observer counts alongside the caller, so wait for it to catch up
    private suspend fun BackupTrafficCounter.awaitTraffic(expected: BackupTraffic) =
        withTimeout(5_000) {
            while (snapshot() != expected) delay(10)
        }

    @Test
    fun countsRequestAndResponseBodies() = runBlocking {
        val counter = BackupTrafficCounter()
        val client = countingClient(counter)

        val body = client.put("https://example.com/file") { setBody(ByteArray(100)) }.bodyAsBytes()

        // the caller still gets the whole response
        assertContentEquals(responseBody, body)
        counter.awaitTraffic(BackupTraffic(bytesSent = 100, bytesReceived = 300))
    }

    @Test
    fun accumulatesAcrossRequests() = runBlocking {
        val counter = BackupTrafficCounter()
        val client = countingClient(counter)

        client.put("https://example.com/a") { setBody(ByteArray(10)) }.bodyAsBytes()
        client.get("https://example.com/b").bodyAsBytes()

        counter.awaitTraffic(BackupTraffic(bytesSent = 10, bytesReceived = 600))
    }

    @Test
    fun startsAtZero() {
        assertEquals(BackupTraffic(0, 0), BackupTrafficCounter().snapshot())
    }
}
