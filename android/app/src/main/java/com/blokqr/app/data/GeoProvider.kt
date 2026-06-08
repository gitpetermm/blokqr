package com.blokqr.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat

/**
 * Fournit un GÉOHASH GROSSIER (≈ ville/région) pour la détection de cloaking
 * géographique. Jamais de position précise : la dernière localisation connue
 * est tronquée à 4 caractères de géohash.
 *
 * Entièrement optionnel : si la permission n'est pas accordée, renvoie null et
 * l'application fonctionne normalement.
 */
class GeoProvider(private val context: Context) {

    fun coarseGeohash(): String? {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val loc = runCatching {
            lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
        }.getOrNull() ?: return null

        return geohash(loc.latitude, loc.longitude, precision = 4)
    }

    // Encodage géohash standard, tronqué à faible précision.
    private fun geohash(lat: Double, lon: Double, precision: Int): String {
        val base32 = "0123456789bcdefghjkmnpqrstuvwxyz"
        var latMin = -90.0; var latMax = 90.0
        var lonMin = -180.0; var lonMax = 180.0
        val out = StringBuilder()
        var bit = 0; var ch = 0; var even = true
        while (out.length < precision) {
            if (even) {
                val mid = (lonMin + lonMax) / 2
                if (lon >= mid) { ch = ch or (1 shl (4 - bit)); lonMin = mid } else lonMax = mid
            } else {
                val mid = (latMin + latMax) / 2
                if (lat >= mid) { ch = ch or (1 shl (4 - bit)); latMin = mid } else latMax = mid
            }
            even = !even
            if (bit < 4) bit++ else { out.append(base32[ch]); bit = 0; ch = 0 }
        }
        return out.toString()
    }
}
