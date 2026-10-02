package com.organicmoto.maps.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.Assert.assertTrue
import java.io.File
import java.nio.file.Files
import java.util.regex.Pattern

/**
 * The offline guarantee, narrowed for regional package downloads.
 *
 * Routing, place search, and map rendering are still strictly offline. The one
 * allowed network boundary is `com.organicmoto.maps.region.net`: the resumable
 * package downloader and the map-server API client. Everything else must not
 * contain a network API, and the release manifest must not enable cleartext.
 */
class OfflineGuaranteeTest {

    /** Production sources allowed to contain network APIs and URL schemes. */
    private val downloadBoundary = "app/src/main/java/com/organicmoto/maps/region/net"

    @Test
    fun manifestDeclaresLocationAudioAndDownloadPermissions() {
        val manifest = repoRoot().resolve("app/src/main/AndroidManifest.xml").readText()
        val permissionPattern = Pattern.compile("uses-permission\\s+android:name=\"([^\"]+)\"")
        val matcher = permissionPattern.matcher(manifest)
        val permissions = buildSet {
            while (matcher.find()) add(matcher.group(1))
        }
        assertEquals(
            setOf(
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION",
                // The ride media control center adjusts the local music stream.
                "android.permission.MODIFY_AUDIO_SETTINGS",
                // Regional package downloads only; see the class doc.
                "android.permission.INTERNET",
            ),
            permissions,
        )
        assertFalse(manifest.contains("android.permission.ACCESS_NETWORK_STATE"))
        // Media control must never rely on privileged media-control access.
        assertFalse(manifest.contains("android.permission.MEDIA_CONTENT_CONTROL"))
        // Notification access is user-granted through the listener service; the
        // app declares no accessibility service.
        assertFalse(manifest.contains("BIND_ACCESSIBILITY_SERVICE"))
    }

    @Test
    fun networkApisExistOnlyInsideTheDownloadBoundary() {
        val forbidden = listOf(
            "HttpURLConnection",
            "okhttp3",
            "retrofit2",
            "java.net.URL",
            "java.net.Socket",
            "java.net.ServerSocket",
            "java.net.InetAddress",
            "SocketChannel",
        )
        val violations = mutableListOf<String>()
        productionSources().forEach { (relative, text) ->
            if (relative.startsWith(downloadBoundary)) return@forEach
            forbidden.filter { text.contains(it) }.forEach { marker ->
                violations += "$relative contains $marker"
            }
        }
        assertTrue(violations.joinToString("\n"), violations.isEmpty())
    }

    @Test
    fun insecureHttpSchemeNeverLeavesTheRegionPackage() {
        val violations = mutableListOf<String>()
        productionSources().forEach { (relative, text) ->
            if (relative.startsWith("app/src/main/java/com/organicmoto/maps/region/")) return@forEach
            if (text.contains("http://")) violations += "$relative contains http://"
        }
        assertTrue(violations.joinToString("\n"), violations.isEmpty())
    }

    @Test
    fun httpsLiteralsAreConfinedToTheRegionPackage() {
        val violations = mutableListOf<String>()
        productionSources().forEach { (relative, text) ->
            if (relative.startsWith("app/src/main/java/com/organicmoto/maps/region/")) return@forEach
            if (text.contains("https://")) violations += "$relative contains https://"
        }
        assertTrue(violations.joinToString("\n"), violations.isEmpty())
    }

    @Test
    fun theDownloadBoundaryIsActuallyNetworkCode() {
        val boundary = repoRoot().resolve(downloadBoundary)
        assertTrue("download boundary directory missing", boundary.isDirectory)
        val text = boundary.walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
        assertTrue("boundary has no HTTP client", text.contains("HttpURLConnection"))
        assertTrue("boundary has no resumable Range support", text.contains("\"Range\""))
    }

    @Test
    fun offlineFeaturesDoNotDependOnTheDownloadBoundary() {
        // Routing, geocoding, tiles, map rendering, and storage must never
        // reach into the network layer; the dependency goes one way.
        val offlinePackages = listOf(
            "app/src/main/java/com/organicmoto/maps/routing",
            "app/src/main/java/com/organicmoto/maps/geocoding",
            "app/src/main/java/com/organicmoto/maps/tiles",
            "app/src/main/java/com/organicmoto/maps/map",
            "app/src/main/java/com/organicmoto/maps/storage",
            "app/src/main/java/com/organicmoto/maps/media",
        )
        val violations = mutableListOf<String>()
        productionSources().forEach { (relative, text) ->
            if (offlinePackages.none { relative.startsWith(it) }) return@forEach
            if (text.contains("region.net")) violations += "$relative imports the network boundary"
        }
        assertTrue(violations.joinToString("\n"), violations.isEmpty())
    }

    @Test
    fun releaseManifestNeverEnablesCleartext() {
        val release = repoRoot().resolve("app/src/main/AndroidManifest.xml").readText()
        assertFalse(
            "release manifest enables cleartext traffic",
            release.contains("usesCleartextTraffic"),
        )
        assertFalse(release.contains("networkSecurityConfig"))
        val debug = repoRoot().resolve("app/src/debug/AndroidManifest.xml").readText()
        assertTrue(
            "debug builds should allow the explicit LAN opt-in",
            debug.contains("usesCleartextTraffic=\"true\""),
        )
    }

    private fun productionSources(): List<Pair<String, String>> {
        val root = repoRoot()
        val out = mutableListOf<Pair<String, String>>()
        listOf("app/src/main/java", "app/src/main/kotlin").forEach { relative ->
            val dir = root.resolve(relative)
            if (!dir.isDirectory) return@forEach
            Files.walk(dir.toPath()).use { paths ->
                paths.filter { Files.isRegularFile(it) }
                    .filter { it.toString().endsWith(".kt") || it.toString().endsWith(".java") }
                    .forEach { path ->
                        val text = path.toFile().readText()
                        out += root.toPath().relativize(path).toString() to text
                    }
            }
        }
        return out
    }

    private fun repoRoot(): File {
        var directory: File? = File(".").absoluteFile
        while (directory != null) {
            if (directory.resolve("settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("Could not locate repository root")
    }
}
