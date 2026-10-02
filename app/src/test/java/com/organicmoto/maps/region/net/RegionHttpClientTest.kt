package com.organicmoto.maps.region.net

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exercises the downloader against a real local HTTP server: full downloads,
 * resumed Range requests with If-Range, servers that ignore Range, truncated
 * bodies, 404/416 handling, cancellation, and the HTTPS-only policy.
 */
class RegionHttpClientTest {

    private val servers = mutableListOf<TestHttpServer>()

    @After
    fun tearDown() {
        servers.forEach { it.close() }
    }

    /** Server-side state the tests can tweak. */
    private class Scenario {
        val content = "curveMaps-region-package-".repeat(64).toByteArray()
        var etag: String? = "\"v1\""
        var ignoreRange = false
        var status = 200
        var truncateBy = 0
        var body = ""
        val requests = mutableListOf<Pair<String?, String?>>()
        val rangeRequests = AtomicInteger(0)
    }

    private fun scenarioServer(): Pair<Scenario, TestHttpServer> {
        val scenario = Scenario()
        val server = TestHttpServer { request ->
            if (request.path == "/text") {
                TestHttpServer.Response(scenario.status, scenario.body)
            } else {
                val range = request.header("Range")
                val ifRange = request.header("If-Range")
                scenario.requests += range to ifRange
                if (scenario.status != 200) {
                    TestHttpServer.Response(scenario.status, "")
                } else {
                    val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull()
                    val useRange = start != null && !scenario.ignoreRange &&
                        (ifRange == null || ifRange == scenario.etag)
                    if (useRange && start!! >= scenario.content.size) {
                        TestHttpServer.Response(
                            416,
                            "",
                            mapOf("Content-Range" to "bytes */${scenario.content.size}"),
                        )
                    } else {
                        val slice = if (useRange) {
                            scenario.content.copyOfRange(start!!, scenario.content.size)
                        } else {
                            scenario.content
                        }
                        val length = (slice.size - scenario.truncateBy).coerceAtLeast(0)
                        val headers = mutableMapOf<String, String>()
                        scenario.etag?.let { headers["ETag"] = it }
                        if (useRange) {
                            headers["Content-Range"] =
                                "bytes $start-${scenario.content.size - 1}/${scenario.content.size}"
                            scenario.rangeRequests.addAndGet(1)
                        }
                        TestHttpServer.Response(
                            if (useRange) 206 else 200,
                            slice.copyOfRange(0, length),
                            headers,
                            declaredLength = slice.size.toLong(),
                        )
                    }
                }
            }
        }
        servers += server
        return scenario to server
    }

    private fun client(allowInsecure: (String) -> Boolean = { true }) =
        RegionHttpClient(connectTimeoutMs = 2_000, readTimeoutMs = 5_000, allowInsecure = allowInsecure)

    @Test
    fun downloadsAWholeFileAndCapturesTheEtag() {
        val (scenario, server) = scenarioServer()
        val destination = File.createTempFile("download", ".motomap").also { it.delete() }
        val progress = mutableListOf<Long>()
        val outcome = client().download(
            "${server.baseUrl}/file",
            destination,
            etag = null,
            onProgress = { bytes, _ -> progress += bytes },
            isCancelled = { false },
        )
        assertEquals(scenario.content.size.toLong(), outcome.bytes)
        assertEquals("\"v1\"", outcome.etag)
        assertFalse(outcome.resumed)
        assertEquals(scenario.content.toList(), destination.readBytes().toList())
        assertTrue(progress.isNotEmpty())
        assertFalse(File(destination.parentFile, "${destination.name}.part").exists())
    }

    @Test
    fun resumesFromAPartialFileWithIfRange() {
        val (scenario, server) = scenarioServer()
        val destination = File.createTempFile("download", ".motomap").also { it.delete() }
        File(destination.parentFile, "${destination.name}.part")
            .writeBytes(scenario.content.copyOfRange(0, 100))
        val outcome = client().download(
            "${server.baseUrl}/file",
            destination,
            etag = "\"v1\"",
            onProgress = { _, _ -> },
            isCancelled = { false },
        )
        assertTrue(outcome.resumed)
        assertEquals(scenario.content.size.toLong(), outcome.bytes)
        assertEquals(scenario.content.toList(), destination.readBytes().toList())
        assertEquals(1, scenario.rangeRequests.get())
        assertEquals("bytes=100-" to "\"v1\"", scenario.requests.last())
    }

    @Test
    fun restartsWhenTheServerIgnoresRange() {
        val (scenario, server) = scenarioServer()
        scenario.ignoreRange = true
        val destination = File.createTempFile("download", ".motomap").also { it.delete() }
        File(destination.parentFile, "${destination.name}.part")
            .writeBytes(scenario.content.copyOfRange(0, 50))
        val outcome = client().download(
            "${server.baseUrl}/file",
            destination,
            etag = "\"v1\"",
            onProgress = { _, _ -> },
            isCancelled = { false },
        )
        assertEquals(scenario.content.size.toLong(), outcome.bytes)
        assertEquals(scenario.content.toList(), destination.readBytes().toList())
    }

    @Test
    fun treatsACompletePartialAsDone() {
        val (scenario, server) = scenarioServer()
        val destination = File.createTempFile("download", ".motomap").also { it.delete() }
        File(destination.parentFile, "${destination.name}.part").writeBytes(scenario.content)
        val outcome = client().download(
            "${server.baseUrl}/file",
            destination,
            etag = "\"v1\"",
            onProgress = { _, _ -> },
            isCancelled = { false },
        )
        assertTrue(outcome.resumed)
        assertEquals(scenario.content.size.toLong(), outcome.bytes)
        assertEquals(scenario.content.toList(), destination.readBytes().toList())
    }

    @Test
    fun detectsATruncatedDownload() {
        val (scenario, server) = scenarioServer()
        scenario.truncateBy = 32
        val destination = File.createTempFile("download", ".motomap").also { it.delete() }
        try {
            client().download(
                "${server.baseUrl}/file",
                destination,
                etag = null,
                onProgress = { _, _ -> },
                isCancelled = { false },
            )
            fail("truncated download reported success")
        } catch (expected: RegionHttpException) {
            assertTrue(expected.message!!.contains("truncated"))
        }
        assertFalse(destination.exists())
        assertTrue(File(destination.parentFile, "${destination.name}.part").exists())
    }

    @Test
    fun reportsNotFound() {
        val (scenario, server) = scenarioServer()
        scenario.status = 404
        try {
            client().download(
                "${server.baseUrl}/file",
                File.createTempFile("download", ".motomap").also { it.delete() },
                etag = null,
                onProgress = { _, _ -> },
                isCancelled = { false },
            )
            fail("404 accepted")
        } catch (expected: RegionHttpException) {
            assertEquals(404, expected.statusCode)
        }
    }

    @Test
    fun cancellationLeavesThePartialFileForResume() {
        val (_, server) = scenarioServer()
        val destination = File.createTempFile("download", ".motomap").also { it.delete() }
        var calls = 0
        try {
            client().download(
                "${server.baseUrl}/file",
                destination,
                etag = null,
                onProgress = { _, _ -> },
                isCancelled = { calls++ > 0 },
            )
            fail("cancelled download reported success")
        } catch (expected: DownloadCancelledException) {
        }
        assertFalse(destination.exists())
        assertTrue(File(destination.parentFile, "${destination.name}.part").exists())
    }

    @Test
    fun rejectsPlainHttpUnlessExplicitlyAllowed() {
        val (scenario, server) = scenarioServer()
        try {
            client(allowInsecure = { false }).fetchText("${server.baseUrl}/text")
            fail("plain HTTP accepted")
        } catch (expected: RegionHttpException) {
            assertTrue(expected.message!!.contains("HTTPS"))
        }
        scenario.body = """{"ok":true}"""
        val text = client(allowInsecure = { it.startsWith("http://127.0.0.1") })
            .fetchText("${server.baseUrl}/text")
        assertEquals("""{"ok":true}""", text)
    }

    @Test
    fun fetchTextRejectsOversizedResponses() {
        val (scenario, server) = scenarioServer()
        scenario.body = "x".repeat(4096)
        try {
            client().fetchText("${server.baseUrl}/text", maxBytes = 128)
            fail("oversized response accepted")
        } catch (expected: RegionHttpException) {
            assertTrue(expected.message!!.contains("too large"))
        }
    }

    // --- reliability ------------------------------------------------------------

    @Test
    fun anInterruptedTransferReportsItsEtagSoTheNextAttemptResumes() {
        // Regression: the ETag used to be known only after a *successful*
        // transfer, so Resume after a drop sent no Range and restarted at 0.
        val (scenario, server) = scenarioServer()
        scenario.truncateBy = 400
        val destination = File.createTempFile("download", ".motomap").also { it.delete() }
        var reportedEtag: String? = null
        var reportedTotal: Long? = null
        try {
            client().download(
                "${server.baseUrl}/file",
                destination,
                etag = null,
                onProgress = { _, _ -> },
                isCancelled = { false },
                onResponse = { etag, total ->
                    reportedEtag = etag
                    reportedTotal = total
                },
            )
            fail("truncated download reported success")
        } catch (expected: RegionHttpException) {
            assertTrue("a dropped body is worth retrying", expected.transient)
        }
        assertEquals("\"v1\"", reportedEtag)
        assertEquals(scenario.content.size.toLong(), reportedTotal)
        val partial = File(destination.parentFile, "${destination.name}.part").length()
        assertTrue(partial in 1 until scenario.content.size)

        scenario.truncateBy = 0
        val outcome = client().download(
            "${server.baseUrl}/file",
            destination,
            etag = reportedEtag,
            onProgress = { _, _ -> },
            isCancelled = { false },
        )
        assertTrue(outcome.resumed)
        assertEquals("bytes=$partial-" to "\"v1\"", scenario.requests.last())
        assertEquals(scenario.content.toList(), destination.readBytes().toList())
    }

    @Test
    fun resumesWithRangeEvenWithoutAKnownEtag() {
        val (scenario, server) = scenarioServer()
        val destination = File.createTempFile("download", ".motomap").also { it.delete() }
        File(destination.parentFile, "${destination.name}.part")
            .writeBytes(scenario.content.copyOfRange(0, 200))
        val outcome = client().download(
            "${server.baseUrl}/file",
            destination,
            etag = null,
            onProgress = { _, _ -> },
            isCancelled = { false },
        )
        assertTrue(outcome.resumed)
        assertEquals("bytes=200-" to null, scenario.requests.last())
        assertEquals(scenario.content.toList(), destination.readBytes().toList())
    }

    @Test
    fun aPartialResponseForTheWrongOffsetIsDiscardedNotAppended() {
        val content = ByteArray(1000) { (it % 251).toByte() }
        val server = TestHttpServer {
            // Always answers from byte 10, whatever was asked.
            TestHttpServer.Response(
                206,
                content.copyOfRange(10, content.size),
                mapOf("Content-Range" to "bytes 10-999/1000", "ETag" to "\"v1\""),
            )
        }.also { servers += it }
        val destination = File.createTempFile("download", ".motomap").also { it.delete() }
        val part = File(destination.parentFile, "${destination.name}.part")
        part.writeBytes(content.copyOfRange(0, 500))
        try {
            client().download("${server.baseUrl}/file", destination, "\"v1\"", { _, _ -> }, { false })
            fail("misaligned range appended")
        } catch (expected: RegionHttpException) {
            assertTrue(expected.transient)
        }
        assertFalse("a corrupt partial must not survive", part.exists())
        assertFalse(destination.exists())
    }

    @Test
    fun serverErrorsAreTransientAndCarryRetryAfter() {
        val server = TestHttpServer {
            TestHttpServer.Response(503, "", mapOf("Retry-After" to "7"))
        }.also { servers += it }
        try {
            client().download(
                "${server.baseUrl}/file",
                File.createTempFile("download", ".motomap").also { it.delete() },
                etag = null,
                onProgress = { _, _ -> },
                isCancelled = { false },
            )
            fail("503 accepted")
        } catch (expected: RegionHttpException) {
            assertEquals(503, expected.statusCode)
            assertTrue(expected.transient)
            assertEquals(7_000L, expected.retryAfterMs)
            assertTrue(expected.message!!.contains("Try again"))
        }
    }

    @Test
    fun notFoundIsPermanent() {
        val (scenario, server) = scenarioServer()
        scenario.status = 404
        try {
            client().download(
                "${server.baseUrl}/file",
                File.createTempFile("download", ".motomap").also { it.delete() },
                etag = null,
                onProgress = { _, _ -> },
                isCancelled = { false },
            )
            fail("404 accepted")
        } catch (expected: RegionHttpException) {
            assertFalse(expected.transient)
        }
    }

    @Test
    fun getRetriesOneTransientFailureButNotAPermanentOne() {
        val calls = AtomicInteger(0)
        val sleeps = mutableListOf<Long>()
        val server = TestHttpServer { request ->
            when (request.path) {
                "/flaky" -> if (calls.incrementAndGet() == 1) {
                    TestHttpServer.Response(502, "")
                } else {
                    TestHttpServer.Response(200, """{"ok":true}""")
                }
                else -> {
                    calls.incrementAndGet()
                    TestHttpServer.Response(403, "")
                }
            }
        }.also { servers += it }
        val client = RegionHttpClient(
            connectTimeoutMs = 2_000,
            readTimeoutMs = 5_000,
            allowInsecure = { true },
            sleep = { sleeps += it },
        )
        assertEquals("""{"ok":true}""", client.fetchText("${server.baseUrl}/flaky"))
        assertEquals(2, calls.get())
        assertEquals(1, sleeps.size)

        calls.set(0)
        try {
            client.fetchText("${server.baseUrl}/forbidden")
            fail("403 accepted")
        } catch (expected: RegionHttpException) {
            assertEquals(403, expected.statusCode)
        }
        assertEquals("a permanent failure is not retried", 1, calls.get())
    }

    @Test
    fun aRefusedConnectionSaysWhatToCheck() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val client = RegionHttpClient(
            connectTimeoutMs = 2_000,
            readTimeoutMs = 2_000,
            allowInsecure = { true },
            getAttempts = 1,
        )
        try {
            client.fetchText("http://127.0.0.1:$port/api/v1/catalog")
            fail("closed port accepted")
        } catch (expected: RegionHttpException) {
            assertEquals(0, expected.statusCode)
            assertTrue(expected.transient)
            assertTrue(expected.message, expected.message!!.contains("Couldn't connect"))
            assertTrue(expected.message, expected.message!!.contains("127.0.0.1"))
        }
    }

    @Test
    fun aRedirectNamesWhereTheServerMoved() {
        val server = TestHttpServer {
            TestHttpServer.Response(301, "", mapOf("Location" to "https://maps.example.net/api/v1/catalog"))
        }.also { servers += it }
        try {
            client().fetchText("${server.baseUrl}/api/v1/catalog")
            fail("redirect followed silently")
        } catch (expected: RegionHttpException) {
            assertFalse(expected.transient)
            assertTrue(expected.message!!.contains("https://maps.example.net"))
        }
    }
}
