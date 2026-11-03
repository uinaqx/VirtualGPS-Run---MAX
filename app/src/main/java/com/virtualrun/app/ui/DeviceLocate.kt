package com.virtualrun.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings

internal const val DEVICE_LOCATE_TIMEOUT_MS = 10_000L

internal enum class LocateFailure {
    NO_PROVIDER,
    LOCATION_OFF,
}

internal sealed class LocatePlan {
    data class LastKnown(val provider: String) : LocatePlan()
    data class SingleUpdate(val provider: String) : LocatePlan()
    data class Unavailable(val failure: LocateFailure) : LocatePlan()
}

/**
 * Chooses a device fix for the map locate button.
 *
 * When the network provider exists and is enabled, this matches v1.0.5:
 * GPS cache, then network cache, otherwise one network update. Passive and
 * GPS updates are used only after that path is unavailable, so phones that
 * already have a network provider keep the same locate behavior.
 * A missing provider is never passed to [LocationManager.requestSingleUpdate].
 */
internal fun planDeviceLocate(
    knownProviders: Set<String>,
    enabledProviders: Set<String>,
    locationEnabled: Boolean,
    hasLastKnown: (String) -> Boolean,
): LocatePlan {
    val gps = LocationManager.GPS_PROVIDER
    val network = LocationManager.NETWORK_PROVIDER
    val passive = LocationManager.PASSIVE_PROVIDER

    if (gps in knownProviders && hasLastKnown(gps)) return LocatePlan.LastKnown(gps)
    if (network in knownProviders && hasLastKnown(network)) return LocatePlan.LastKnown(network)
    if (locationEnabled && network in knownProviders && network in enabledProviders) {
        return LocatePlan.SingleUpdate(network)
    }
    if (!locationEnabled) return LocatePlan.Unavailable(LocateFailure.LOCATION_OFF)

    if (passive in knownProviders && hasLastKnown(passive)) return LocatePlan.LastKnown(passive)
    if (gps in knownProviders && gps in enabledProviders) return LocatePlan.SingleUpdate(gps)
    if (passive in knownProviders && passive in enabledProviders) return LocatePlan.SingleUpdate(passive)

    val anyKnown = gps in knownProviders || network in knownProviders || passive in knownProviders
    return if (anyKnown) {
        LocatePlan.Unavailable(LocateFailure.LOCATION_OFF)
    } else {
        LocatePlan.Unavailable(LocateFailure.NO_PROVIDER)
    }
}

internal fun isSystemLocationEnabled(context: Context, locationManager: LocationManager): Boolean {
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF
            ) != Settings.Secure.LOCATION_MODE_OFF
        }
    }.getOrDefault(true)
}

private val LOCATE_PROVIDER_NAMES = setOf(
    LocationManager.GPS_PROVIDER,
    LocationManager.NETWORK_PROVIDER,
    LocationManager.PASSIVE_PROVIDER,
)

internal fun knownDeviceLocateProviders(locationManager: LocationManager): Set<String> {
    val installed = runCatching { locationManager.allProviders }.getOrDefault(emptyList())
    return LOCATE_PROVIDER_NAMES.filterTo(linkedSetOf()) { it in installed }
}

internal fun enabledDeviceLocateProviders(
    locationManager: LocationManager,
    knownProviders: Set<String>,
): Set<String> {
    return knownProviders.filterTo(linkedSetOf()) { provider ->
        runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
    }
}

@SuppressLint("MissingPermission")
internal fun lastKnownDeviceLocation(locationManager: LocationManager, provider: String): Location? {
    return runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
}

/**
 * One in-flight single update. A later request or [cancel] removes the previous
 * listener, and the timeout removes it if no fix arrives.
 */
internal class DeviceLocateRequest(private val handler: Handler) {
    private var listener: LocationListener? = null
    private var timeout: Runnable? = null
    private var settled = false

    fun cancel(locationManager: LocationManager) {
        settle(locationManager)
    }

    @SuppressLint("MissingPermission")
    fun requestSingleUpdate(
        locationManager: LocationManager,
        provider: String,
        onFix: (Location) -> Unit,
        onProviderDisabled: () -> Unit,
        onTimeout: () -> Unit,
    ) {
        settle(locationManager)
        settled = false
        val callback = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!settle(locationManager)) return
                onFix(location)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) {
                if (!settle(locationManager)) return
                onProviderDisabled()
            }
        }
        listener = callback
        val timeoutRunnable = Runnable {
            if (!settle(locationManager)) return@Runnable
            onTimeout()
        }
        timeout = timeoutRunnable
        // Arm the timeout first so a synchronous failure still removes it.
        handler.postDelayed(timeoutRunnable, DEVICE_LOCATE_TIMEOUT_MS)
        try {
            // Kept for the same API 26–34 behavior as the previous locate button.
            @Suppress("DEPRECATION")
            locationManager.requestSingleUpdate(provider, callback, Looper.getMainLooper())
        } catch (error: RuntimeException) {
            settle(locationManager)
            throw error
        }
    }

    private fun settle(locationManager: LocationManager): Boolean {
        if (settled) return false
        settled = true
        timeout?.let(handler::removeCallbacks)
        timeout = null
        listener?.let { current -> runCatching { locationManager.removeUpdates(current) } }
        listener = null
        return true
    }
}
