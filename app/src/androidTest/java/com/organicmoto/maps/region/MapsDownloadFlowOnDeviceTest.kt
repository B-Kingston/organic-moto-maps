package com.organicmoto.maps.region

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device proof of the separated download/import/delete contract:
 * importing a user-picked file leaves that file in place, a recovered
 * app-storage package can be saved to a user-visible file without another
 * download, an interrupted partial keeps its bytes, and deleting the active map
 * switches to the bundled dataset and waits for the routing graph to drain
 * before unlinking anything.
 */
@RunWith(AndroidJUnit4::class)
class MapsDownloadFlowOnDeviceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var manager: RegionManager? = null

    private val registry: RegionRegistry
        get() = RegionRegistry(File(context.filesDir, "regions"))

    private val regionsRoot: File
        get() = File(context.filesDir, "regions")

    @Before
    fun clean() {
        regionsRoot.deleteRecursively()
        // Tests configure a server address (the loopback server test); reset it
        // so each test starts from the real unconfigured state.
        RegionSettings(context).apply {
            serverAddress = ""
            allowInsecureLocal = false
        }
    }

    @After
    fun tearDown() {
        manager?.close()
        manager = null
        regionsRoot.deleteRecursively()
    }

    private fun copyMonacoPackage(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = File(context.cacheDir, "monaco-test-${System.nanoTime()}.motomap")
        instrumentation.context.assets.open("regions/monaco.motomap").use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    private fun newManager(): RegionManager = RegionManager(context).also { manager = it }

    private suspend fun awaitState(predicate: (MapsState) -> Boolean): MapsState =
        withTimeout(30_000) { (manager ?: newManager()).state.first(predicate) }

    private fun installAndActivateMonaco(): Pair<File, InstalledRegion> {
        val archive = copyMonacoPackage()
        val install = registry.install(archive)
        registry.activate(install.installId)
        return archive to install
    }

    @Test
    fun importPackageLeavesTheUsersSourceFileInPlace() {
        val source = copyMonacoPackage()
        val original = source.readBytes()
        val manager = newManager()
        manager.importPackage(Uri.fromFile(source))
        val state = runBlocking {
            withTimeout(30_000) { manager.state.first { it.installed.isNotEmpty() && !it.busy } }
        }
        assertEquals("monaco", state.installed.single().regionId)
        assertTrue("the user's picked file must survive the import", source.isFile)
        assertEquals(original.size, source.readBytes().size)
        assertTrue(state.notice!!.contains("left where it was"))
    }

    @Test
    fun savingARecoveredPackageCopiesItToTheChosenFileWithoutDownloadingAgain() {
        val content = copyMonacoPackage().readBytes()
        val downloads = File(regionsRoot, "downloads").apply { mkdirs() }
        val staged = File(downloads, "monaco-staged.motomap")
        staged.writeBytes(content)
        RegionDownloadStore(File(regionsRoot, "download.json"), downloads).save(
            RegionDownloadRecord(
                jobId = "job-1",
                regionId = "monaco",
                regionName = "Monaco",
                fingerprint = "b".repeat(64),
                downloadUrl = "/api/v1/artifacts/monaco/${"b".repeat(64)}/monaco.motomap",
                etag = "\"v1\"",
                bytes = content.size.toLong(),
                total = content.size.toLong(),
                fileName = "monaco-staged.motomap",
                destinationUri = null,
            ),
        )
        val manager = newManager()
        val recovered = runBlocking { awaitState { it.recoveredPackage != null } }
        assertEquals(content.size.toLong(), recovered.recoveredPackage!!.bytes)

        val destination = File(context.cacheDir, "saved-${System.nanoTime()}.motomap")
        manager.saveRecoveredPackage(Uri.fromFile(destination))
        val saved = runBlocking { awaitState { it.savedPackage != null } }
        assertEquals(content.size.toLong(), saved.savedPackage!!.bytes)
        assertTrue(destination.isFile)
        assertEquals(content.size, destination.readBytes().size)
        assertFalse("the staged copy is app-managed and cleaned after a verified save", staged.isFile)
        assertEquals(null, saved.recoveredPackage)
    }

    @Test
    fun anInterruptedPartialIsOfferedForResumeAndItsBytesSurvive() {
        val downloads = File(regionsRoot, "downloads").apply { mkdirs() }
        val part = File(downloads, "monaco-staged.motomap.part")
        part.writeBytes(ByteArray(4096) { 7 })
        RegionDownloadStore(File(regionsRoot, "download.json"), downloads).save(
            RegionDownloadRecord(
                jobId = "job-1",
                regionId = "monaco",
                regionName = "Monaco",
                fingerprint = "b".repeat(64),
                downloadUrl = "/api/v1/artifacts/monaco/${"b".repeat(64)}/monaco.motomap",
                etag = "\"v1\"",
                bytes = 4096,
                total = 1_000_000,
                fileName = "monaco-staged.motomap",
                destinationUri = null,
            ),
        )
        val manager = newManager()
        val state = runBlocking { awaitState { it.interrupted != null } }
        assertEquals(4096L, state.interrupted!!.bytes)
        assertTrue("the partial bytes must survive", part.isFile)
        // Without a configured server the resume fails honestly and keeps the
        // partial for a retry.
        manager.resumeDownload(Uri.fromFile(File(context.cacheDir, "unused.motomap")))
        val failed = runBlocking { awaitState { it.error != null } }
        assertTrue(failed.error!!.contains("server address"))
        assertTrue(part.isFile)
        assertNotNull(failed.interrupted)
    }

    @Test
    fun aRealDownloadStagesPrivatelyAndSavesTheCompleteFile() {
        val content = copyMonacoPackage().readBytes()
        LocalPackageServer(content).use { server ->
            RegionSettings(context).apply {
                serverAddress = "http://127.0.0.1:${server.port}"
                allowInsecureLocal = true
            }
            val manager = newManager()
            manager.checkServer()
            val ready = runBlocking { awaitState { it.catalog.isNotEmpty() && !it.busy } }
            assertTrue(ready.catalog.single().hasCachedArtifact)
            val destination = File(context.cacheDir, "downloaded-${System.nanoTime()}.motomap")
            manager.startDownload("monaco", Uri.fromFile(destination))
            val saved = runBlocking { awaitState { it.savedPackage != null } }
            assertEquals(content.size.toLong(), saved.savedPackage!!.bytes)
            assertTrue(content.contentEquals(destination.readBytes()))
            assertTrue(
                "private staging must be cleaned after a verified save",
                File(regionsRoot, "downloads").listFiles().isNullOrEmpty(),
            )
            assertFalse(File(regionsRoot, "download.json").exists())
            destination.delete()
        }
    }

    @Test
    fun aPackageThatDoesNotMatchTheAdvertisedDigestIsDiscardedAndNeverSaved() {
        val content = copyMonacoPackage().readBytes()
        LocalPackageServer(content, advertisedSha256 = "d".repeat(64)).use { server ->
            RegionSettings(context).apply {
                serverAddress = "http://127.0.0.1:${server.port}"
                allowInsecureLocal = true
            }
            val manager = newManager()
            manager.checkServer()
            runBlocking { awaitState { it.catalog.isNotEmpty() && !it.busy } }
            val destination = File(context.cacheDir, "corrupt-${System.nanoTime()}.motomap")
            manager.startDownload("monaco", Uri.fromFile(destination))
            val failed = runBlocking { awaitState { it.download == null && it.error != null } }
            assertTrue(failed.error!!, failed.error!!.contains("checksum mismatch"))
            assertEquals(null, failed.savedPackage)
            assertTrue(
                "nothing may reach the user's file",
                !destination.exists() || destination.length() == 0L,
            )
            assertTrue(
                "damaged staged bytes must not be offered for resume",
                File(regionsRoot, "downloads").listFiles().orEmpty().none { it.length() > 0 },
            )
            destination.delete()
        }
    }

    @Test
    fun removingAnInactiveMapDeletesOnlyTheInstalledCopy() {
        val (archive, install) = installAndActivateMonaco()
        registry.activateBundled()
        val manager = newManager()
        runBlocking { awaitState { it.installed.isNotEmpty() } }
        manager.requestRemove(install.installId)
        val state = runBlocking { awaitState { it.installed.isEmpty() && !it.busy } }
        assertTrue(state.notice!!.contains("deleted from this device"))
        assertTrue(state.notice!!.contains("Files are not touched"))
        assertFalse(File(registry.installsDir, install.dirName).exists())
        assertTrue("the user's downloaded package file is untouched", archive.isFile)
    }

    @Test
    fun removingTheActiveMapSwitchesToBundledAndWaitsForTheGraphDrain() {
        val (_, install) = installAndActivateMonaco()
        val manager = newManager()
        runBlocking { awaitState { it.installed.isNotEmpty() } }
        manager.requestRemove(install.installId)

        val pending = runBlocking { awaitState { it.pendingRemoval != null } }
        assertEquals(install.installId, pending.pendingRemoval!!.installId)
        assertEquals(null, pending.activeInstallId)
        assertTrue(
            "the directory must not be unlinked before the drain signal",
            File(registry.installsDir, install.dirName).isDirectory,
        )
        val dataset = runBlocking { withTimeout(30_000) { manager.dataset.first { it.isBundled } } }
        assertEquals(RegionKind.BUNDLED, dataset.kind)

        manager.onDatasetReadersReleased(install.installId)
        val removed = runBlocking { awaitState { it.pendingRemoval == null && it.installed.isEmpty() } }
        assertTrue(removed.notice!!.contains("deleted from this device"))
        assertFalse(File(registry.installsDir, install.dirName).exists())
    }

    @Test
    fun removingTheActiveMapMidRideIsRefused() {
        val (_, install) = installAndActivateMonaco()
        val manager = newManager()
        runBlocking { awaitState { it.installed.isNotEmpty() } }
        manager.setRideActive(true)
        manager.requestRemove(install.installId)
        val state = runBlocking { awaitState { it.error != null } }
        assertTrue(state.error!!.contains("Stop the ride"))
        assertTrue(File(registry.installsDir, install.dirName).isDirectory)
        assertEquals(install.installId, registry.active().installId)
        assertTrue(state.pendingRemoval == null)
    }
}

/**
 * A minimal loopback HTTP server for the on-device download test: it serves the
 * health and catalog documents plus the artifact bytes. Loopback-only, test-only
 * (the production network boundary stays in `region/net`).
 */
private class LocalPackageServer(
    private val content: ByteArray,
    /** The digest the catalog advertises; defaults to the real one. */
    private val advertisedSha256: String = PackageChecksum.sha256Hex(
        java.io.File.createTempFile("advertised", ".bin").apply {
            writeBytes(content)
            deleteOnExit()
        },
    ),
) : java.io.Closeable {

    private val server = java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
    private val fingerprint = "c".repeat(64)
    private val thread = Thread(::serve, "local-package-server").apply {
        isDaemon = true
        start()
    }

    val port: Int get() = server.localPort

    private fun serve() {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (e: Exception) {
                return
            }
            socket.use { connection ->
                try {
                    val reader = connection.getInputStream().bufferedReader()
                    val requestLine = reader.readLine() ?: return@use
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val path = requestLine.split(' ').getOrNull(1).orEmpty()
                    val (status, body) = when {
                        path.endsWith("/api/v1/health") -> "200 OK" to
                            """{"version":"test","generation":{"enabled":true,"message":""}}""".toByteArray()
                        path.endsWith("/api/v1/catalog") -> "200 OK" to catalogJson()
                        path.contains("/api/v1/artifacts/") -> "200 OK" to content
                        else -> "404 Not Found" to ByteArray(0)
                    }
                    val out = connection.getOutputStream()
                    out.write(
                        "HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                            .toByteArray(),
                    )
                    out.write(body)
                    out.flush()
                } catch (e: Exception) {
                    // A client that cancels/disconnects mid-request is expected.
                }
            }
        }
    }

    private fun catalogJson(): ByteArray = """
        {"regions":[{"id":"monaco","name":"Monaco","coverage":"Monaco","disabled":false,
        "maxZoom":14,"sourceDate":"260928","sourceSha256":"${"a".repeat(64)}","sourcePinned":true,
        "artifact":{"available":true,"fingerprint":"$fingerprint","size":${content.size},
        "sha256":"$advertisedSha256","generatedAt":"2026-10-01T00:00:00Z",
        "downloadUrl":"/api/v1/artifacts/monaco/$fingerprint/monaco.motomap"}}]}
    """.trimIndent().encodeToByteArray()

    override fun close() {
        runCatching { server.close() }
        runCatching { thread.interrupt() }
    }
}
