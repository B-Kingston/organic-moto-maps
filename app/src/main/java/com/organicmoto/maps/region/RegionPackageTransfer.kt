package com.organicmoto.maps.region

import java.io.File
import java.io.OutputStream

/**
 * The one place a staged package is copied to the user-visible destination.
 *
 * The copy reports exactly how many bytes reached the output stream; callers
 * compare that with the staged file's length and refuse to report success on a
 * short write. A failed copy leaves the staged file intact so the user can
 * retry the save without downloading again.
 */
object RegionPackageTransfer {

    /** Copies [source] into [output], closing both, and returns bytes written. */
    fun copyTo(source: File, output: OutputStream): Long =
        source.inputStream().buffered().use { input ->
            output.use { out -> input.copyTo(out) }
        }
}
