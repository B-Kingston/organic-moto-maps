package com.organicmoto.maps.map

import androidx.test.platform.app.InstrumentationRegistry
import com.organicmoto.maps.tiles.OfflineTileStore
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Installs committed Monaco tiles before Activity launch, restoring the previous
 * archive after Activity teardown. Put this outside the Activity/Compose rule.
 * These tiles prove style/camera behavior, not Queensland rendering or POI content.
 */
class OfflineBasemapRule internal constructor(
    private val tiles: File,
    private val openArchive: () -> InputStream,
    private val enabled: (Description) -> Boolean = { true },
) : TestRule {
    constructor(enabled: (Description) -> Boolean = { true }) : this(
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "tiles/basemap.pmtiles"),
        { InstrumentationRegistry.getInstrumentation().context.assets.open("regions/monaco.motomap") },
        enabled,
    )

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            if (!enabled(description)) {
                base.evaluate()
                return
            }
            check(tiles.parentFile!!.mkdirs() || tiles.parentFile!!.isDirectory) {
                "Basemap prerequisite: cannot create ${tiles.parent}"
            }
            val backup = File(tiles.parentFile, "basemap.pmtiles.backup-${System.nanoTime()}")
            val staging = File(tiles.parentFile, "basemap.pmtiles.staging-${System.nanoTime()}")
            val archive = File(tiles.parentFile, "basemap.motomap.fixture-${System.nanoTime()}")
            val hadMap = tiles.exists()
            check(!hadMap || tiles.renameTo(backup)) { "Basemap prerequisite: cannot preserve installed map" }
            var failure: Throwable? = null
            try {
                archive.outputStream().buffered().use { output ->
                    openArchive().use { input -> input.copyTo(output) }
                }
                ZipFile(archive).use { zip ->
                    val entry = checkNotNull(zip.getEntry("tiles/basemap.pmtiles")) {
                        "Basemap prerequisite: Monaco archive has no tiles/basemap.pmtiles"
                    }
                    zip.getInputStream(entry).use { input ->
                        staging.outputStream().buffered().use { output -> input.copyTo(output) }
                    }
                }
                check(OfflineTileStore.hasValidHeader(staging.inputStream())) {
                    "Basemap prerequisite: Monaco tiles are not a valid PMTiles v3 archive"
                }
                check(staging.renameTo(tiles)) { "Basemap prerequisite: cannot install validated tiles" }
                base.evaluate()
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                try {
                    check(!archive.exists() || archive.delete()) { "Cannot remove test package" }
                    check(!staging.exists() || staging.delete()) { "Cannot remove staged test tiles" }
                    check(!tiles.exists() || tiles.delete()) { "Cannot remove test tiles" }
                    check(!hadMap || backup.renameTo(tiles)) { "Cannot restore previous basemap: $backup" }
                } catch (cleanup: Throwable) {
                    if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
                }
            }
        }
    }
}
