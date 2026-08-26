package com.organicmoto.maps.assets

import com.organicmoto.maps.geocoding.GeocodeResultType
import com.organicmoto.maps.geocoding.GeocoderIndex
import com.organicmoto.maps.geocoding.SearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.Locale

class GraphAndGeocoderAssetIntegrityTest {

    @Test
    fun graphAssetContainsTheStoredMotorcycleProfile() {
        val root = repoRoot()
        val graph = root.resolve("app/src/main/assets/graph-cache")
        listOf("nodes", "edges", "properties").forEach { name ->
            assertTrue(
                "Missing graph asset $name; run JAVA_HOME=/opt/homebrew/opt/openjdk@17 " +
                    "\$JAVA_HOME/bin/java -Xmx12g -jar tools/gh/graphhopper-web-11.0.jar " +
                    "import tools/gh/config.yml; cp -R data/graph-cache app/src/main/assets/graph-cache",
                graph.resolve(name).isFile,
            )
        }
        val properties = graph.resolve("properties").readBytes().toString(Charsets.ISO_8859_1)
        assertTrue(
            "Graph profile marker is missing; run JAVA_HOME=/opt/homebrew/opt/openjdk@17 " +
                "\$JAVA_HOME/bin/java -Xmx12g -jar tools/gh/graphhopper-web-11.0.jar " +
                "import tools/gh/config.yml; cp -R data/graph-cache app/src/main/assets/graph-cache",
            properties.contains("profiles=motorcycle|198752012"),
        )
    }

    @Test
    fun realGeocoderAssetParsesAndAnswersRepresentativeQueries() = runTest {
        val file = repoRoot().resolve("app/src/main/assets/geocoder/geocoder.dat")
        assertTrue(
            "Build it with JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :geocoder-tool:run --args=\"data/queensland.osm.pbf app/src/main/assets/geocoder\"",
            file.isFile,
        )
        val index = FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
            GeocoderIndex.parse(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()).order(ByteOrder.LITTLE_ENDIAN))
        }
        assertTrue(index.docCount > 0)
        assertTrue(index.termCount > 0)
        assertTrue(index.localityCount > 0)
        val engine = SearchEngine(index, clockMs = { 0L }, logTiming = {})

        val brisbane = engine.search("brisbane")
        assertTrue(brisbane.any { it.type == GeocodeResultType.LOCALITY && it.name.lowercase(Locale.ROOT).contains("brisbane") })
        assertTrue(engine.search("queen street").any { it.type == GeocodeResultType.STREET })
        assertTrue(engine.search("fuel").any { it.type == GeocodeResultType.POI })
        val coordinate = engine.search("-27.4698, 153.0251")
        assertEquals(1, coordinate.size)
        assertEquals(GeocodeResultType.COORDINATE, coordinate.single().type)
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
