package com.househunter.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng

class MainActivity : ComponentActivity() {
    /**
     * Dev aid for emulator testing (adb can't pinch): `am start ... --ef lat 33.77 --ef lon -84.36 --ef zoom 13.5`
     * opens the map at that camera. Null for a normal launch.
     */
    private fun debugStartCamera(): CameraPosition? {
        val lat = intent.getFloatExtra("lat", Float.NaN)
        val lon = intent.getFloatExtra("lon", Float.NaN)
        val zoom = intent.getFloatExtra("zoom", Float.NaN)
        if (lat.isNaN() || lon.isNaN() || zoom.isNaN()) return null
        return CameraPosition.Builder().target(LatLng(lat.toDouble(), lon.toDouble())).zoom(zoom.toDouble()).build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MapScreen(startCamera = debugStartCamera())
            }
        }
    }
}
