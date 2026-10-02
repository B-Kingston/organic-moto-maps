package com.organicmoto.maps

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import com.organicmoto.maps.media.MediaAccessPrompt
import com.organicmoto.maps.media.MediaPermission
import org.maplibre.android.MapLibre

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            MaterialTheme {
                var showMediaAccessPrompt by rememberSaveable {
                    mutableStateOf(savedInstanceState == null && !MediaPermission.isGranted(this))
                }
                RouteScreen()
                if (showMediaAccessPrompt) {
                    MediaAccessPrompt(
                        onDismiss = { showMediaAccessPrompt = false },
                        onEnable = {
                            showMediaAccessPrompt = false
                            runCatching { startActivity(MediaPermission.settingsIntent()) }
                                .onFailure { android.util.Log.w("OrganicMoto.Media", "Notification settings unavailable", it) }
                        },
                    )
                }
            }
        }
    }
}
