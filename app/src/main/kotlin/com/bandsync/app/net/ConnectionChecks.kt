package com.bandsync.app.net

import android.bluetooth.BluetoothManager
import android.content.Context
import android.location.LocationManager
import android.net.wifi.WifiManager
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

data class ConnectionCheck(val label: String, val ok: Boolean)

data class ConnectionChecks(val checks: List<ConnectionCheck>, val locationOn: Boolean)

/** The things that stop phones finding each other, without any error from Nearby to explain why. */
fun connectionChecks(context: Context): ConnectionChecks {
    val app = context.applicationContext
    val bluetooth = app.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    val wifi = app.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
    val location = app.getSystemService(LocationManager::class.java)
        ?.let(LocationManagerCompat::isLocationEnabled) == true
    val playServices = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app) == ConnectionResult.SUCCESS
    return ConnectionChecks(
        checks = listOf(
            ConnectionCheck("Bluetooth on", bluetooth),
            ConnectionCheck("Wi-Fi on", wifi),
            ConnectionCheck("Location on", location),
            ConnectionCheck("Permissions allowed", missingPermissions(app).isEmpty()),
            ConnectionCheck("Google Play services ready", playServices),
        ),
        locationOn = location,
    )
}
