package com.organicmoto.maps.region

import android.content.Context
import com.organicmoto.maps.tiles.OfflineTileStore
import java.io.File

/** Which dataset the app is currently using. */
enum class RegionKind { BUNDLED, INSTALLED }

/**
 * The resolved active dataset. Everything the app needs to route, search, and
 * render comes from here:
 *
 *  - [graphDir] + [copyGraphFromAssets]: the GraphHopper graph directory. The
 *    bundled fallback still copies from APK assets on first use; an installed
 *    region reads its immutable directory directly.
 *  - [geocoderFile]: an installed region's `geocoder.dat`, or null to use the
 *    packaged asset (bundled fallback).
 *  - [tilesFile]: the PMTiles basemap. Installed regions carry their own; the
 *    bundled fallback uses the legacy "Load map file" import.
 *
 * [generation] increments on every activation. Screens key their router,
 * geocoder, map style, and route searches on it, so a switch cannot leave a
 * stale result from the previous dataset on screen.
 */
data class RegionDataset(
    val kind: RegionKind,
    val installId: String?,
    val regionId: String,
    val displayName: String,
    val coverage: String?,
    val sourceDate: String?,
    val generation: Int,
    val graphDir: File,
    val copyGraphFromAssets: Boolean,
    val geocoderFile: File?,
    val tilesFile: File?,
) {
    /** MapLibre URL for the basemap, or null when no tiles are installed. */
    val tilesUrl: String?
        get() = tilesFile?.let(OfflineTileStore::mapUrl)

    val isBundled: Boolean get() = kind == RegionKind.BUNDLED
}

/** Builds [RegionDataset]s for the bundled fallback and installed regions. */
object RegionDatasets {

    const val BUNDLED_REGION_ID = "bundled-queensland"
    const val BUNDLED_DISPLAY_NAME = "Queensland (bundled)"

    /** The packaged Queensland fallback, always available. */
    fun bundled(context: Context, generation: Int): RegionDataset {
        val filesDir = context.applicationContext.filesDir
        val legacyTiles = OfflineTileStore.installedFileOrNull(context.applicationContext)
        return RegionDataset(
            kind = RegionKind.BUNDLED,
            installId = null,
            regionId = BUNDLED_REGION_ID,
            displayName = BUNDLED_DISPLAY_NAME,
            coverage = "Queensland, Australia",
            sourceDate = null,
            generation = generation,
            graphDir = File(filesDir, "gh-cache"),
            copyGraphFromAssets = true,
            geocoderFile = null,
            tilesFile = legacyTiles,
        )
    }

    /** An installed package's immutable directory. */
    fun installed(context: Context, install: InstalledRegion, generation: Int): RegionDataset {
        val dir = File(File(context.applicationContext.filesDir, "regions/installs"), install.dirName)
        val tiles = File(dir, RegionPackageContract.TILES_PATH)
        return RegionDataset(
            kind = RegionKind.INSTALLED,
            installId = install.installId,
            regionId = install.regionId,
            displayName = install.regionName,
            coverage = install.coverage,
            sourceDate = install.sourceDate,
            generation = generation,
            graphDir = File(dir, RegionPackageContract.GRAPH_PATH),
            copyGraphFromAssets = false,
            geocoderFile = File(dir, RegionPackageContract.GEOCODER_PATH),
            tilesFile = tiles.takeIf { it.isFile },
        )
    }
}
