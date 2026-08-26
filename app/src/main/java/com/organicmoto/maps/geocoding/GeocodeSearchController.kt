package com.organicmoto.maps.geocoding

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "OrganicMoto.GeocodeCtrl"

/**
 * Controller for offline place search. The geocoding index (asset copy + mmap)
 * is loaded lazily on the first search and cached for the app process lifetime;
 * all searches run on [Dispatchers.IO].
 */
class GeocodeSearchController(context: Context) : GeocodeController {

    private val appContext = context.applicationContext
    private val lock = Any()

    @Volatile
    private var index: GeocoderIndex? = null

    /**
     * Searches the offline index for [query]. Returns an empty list for blank
     * queries. The first call can take a few seconds while the index is copied
     * out of assets and memory-mapped; subsequent calls are fast.
     */
    override suspend fun search(query: String, limit: Int): List<GeocodeResult> {
        if (query.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            val loaded = index ?: synchronized(lock) {
                index ?: run {
                    Log.i(TAG, "First search — loading geocoder index")
                    val started = SystemClock.elapsedRealtime()
                    GeocoderIndex.load(appContext).also {
                        index = it
                        Log.i(
                            TAG,
                            "Geocoder index ready in ${SystemClock.elapsedRealtime() - started} ms: " +
                                "${it.docCount} docs, ${it.termCount} terms, ${it.localityCount} localities"
                        )
                    }
                }
            }
            SearchEngine(loaded).search(query, limit = limit)
        }
    }
}
