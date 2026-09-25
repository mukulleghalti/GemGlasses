package com.lpecom.gemglasses.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Simple lat/lng snapshot used to ground `search_places`. */
data class LatLng(val latitude: Double, val longitude: Double)

/** Thin wrapper over FusedLocationProvider returning the current fix, or null. */
@Singleton
class LocationProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val fused = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    suspend fun current(): LatLng? {
        if (!hasPermission()) return null
        return suspendCancellableCoroutine { cont ->
            fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    cont.resume(loc?.let { LatLng(it.latitude, it.longitude) })
                }
                .addOnFailureListener { cont.resume(null) }
        }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
}
