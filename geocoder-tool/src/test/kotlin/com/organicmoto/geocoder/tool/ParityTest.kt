package com.organicmoto.geocoder.tool

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ParityTest {

    @Test
    fun textCopiesMatchExceptForTheirPackageLine() {
        val root = repoRoot()
        val tool = root.resolve("geocoder-tool/src/main/kotlin/com/organicmoto/geocoder/tool/Text.kt")
        val app = root.resolve("app/src/main/java/com/organicmoto/maps/geocoding/Text.kt")
        assertEquals(tool.readLines().drop(1), app.readLines().drop(1))
    }

    @Test
    fun poiKeywordResourceAndEmbeddedRulesStayAligned() {
        val resourceRules = PoiKeywords::class.java.getResourceAsStream("/poi_keywords.tsv")!!
            .bufferedReader()
            .use { reader ->
                reader.readLines()
                    .filter { line -> line.isNotBlank() && !line.startsWith("#") }
                    .map { line ->
                        val (left, right) = line.split('\t', limit = 2)
                        val separator = left.indexOf('=')
                        PoiKeywords.Rule(
                            key = if (separator < 0) left.trim() else left.substring(0, separator).trim(),
                            value = if (separator < 0) null else left.substring(separator + 1).trim(),
                            keywords = right.trim().split(',').map(String::trim),
                        )
                    }
            }
        val field = PoiKeywords::class.java.getDeclaredField("rules").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val loadedRules = field.get(PoiKeywords.load()) as List<PoiKeywords.Rule>
        assertEquals(resourceRules, loadedRules)
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
