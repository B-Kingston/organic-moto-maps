package com.organicmoto.maps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** Shared runtime-location permission policy for the screen and GPS source. */
internal object LocationPermission {
    /** Ask for both so Android can offer the user precise or approximate access. */
    val requestedPermissions: Array<String>
        get() = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

    /** A normal Android grant persists across launches until the user revokes it. */
    fun isGranted(context: Context): Boolean = requestedPermissions.any { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
