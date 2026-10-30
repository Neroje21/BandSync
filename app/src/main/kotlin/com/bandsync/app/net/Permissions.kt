package com.bandsync.app.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * What Nearby Connections needs granted at runtime on this Android version. PDFs need nothing here:
 * the system file picker grants access to just the files the user chooses.
 */
fun requiredPermissions(sdk: Int = Build.VERSION.SDK_INT): List<String> = buildList {
    if (sdk <= 32) {
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.READ_EXTERNAL_STORAGE) // Reading a received FILE payload.
    }
    if (sdk in 29..32) add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (sdk >= 31) {
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BLUETOOTH_SCAN)
    }
    if (sdk >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    if (sdk >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
}

fun missingPermissions(context: Context): List<String> = requiredPermissions().filter {
    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
}
