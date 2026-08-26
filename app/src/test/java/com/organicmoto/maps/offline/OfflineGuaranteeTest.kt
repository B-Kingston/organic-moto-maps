package com.organicmoto.maps.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.Assert.assertTrue
import java.io.File
import java.nio.file.Files
import java.util.regex.Pattern

class OfflineGuaranteeTest {

    @Test
    fun manifestDeclaresOnlyLocationPermissions() {
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
            ),
            permissions,
        )
        assertFalse(manifest.contains("android.permission.INTERNET"))
        assertFalse(manifest.contains("android.permission.ACCESS_NETWORK_STATE"))
    }

    @Test
    fun productionSourcesContainNoNetworkApis() {
        val sourceRoots = listOf(
            repoRoot().resolve("app/src/main/java"),
            repoRoot().resolve("app/src/main/kotlin"),
        )
        val forbidden = listOf(
            "HttpURLConnection",
            "okhttp3",
            "retrofit2",
            "java.net.URL",
            "java.net.Socket",
            "https://",
            "http://",
        )
        val violations = mutableListOf<String>()
        sourceRoots.filter { it.isDirectory }.forEach { root ->
            Files.walk(root.toPath()).use { paths ->
                paths.filter { Files.isRegularFile(it) }
                    .filter { it.toString().endsWith(".kt") || it.toString().endsWith(".java") }
                    .forEach { path ->
                        val text = path.toFile().readText()
                        forbidden.filter { text.contains(it) }.forEach { marker ->
                            violations += "$path contains $marker"
                        }
                    }
            }
        }
        assertTrue(violations.joinToString("\n"), violations.isEmpty())
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
