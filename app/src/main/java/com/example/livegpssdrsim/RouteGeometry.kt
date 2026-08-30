package com.example.livegpssdrsim

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A geographic point. [alt] is metres, or null when unknown. */
data class GeoPt(val lat: Double, val lon: Double, val alt: Double? = null)

/**
 * Route math ported from the reference route-planner frontend: great-circle
 * distances between points, and interpolation of a position a given distance
 * along the polyline. Used to advance a playback cursor at a constant speed.
 */
object RouteGeometry {

    private const val EARTH_R = 6_371_000.0 // metres

    /** Great-circle distance in metres between two lat/lon points. */
    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2.0 * EARTH_R * atan2(sqrt(a), sqrt(1.0 - a))
    }

    /** Total length of the polyline in metres (0 for < 2 points). */
    fun totalDistance(pts: List<GeoPt>): Double {
        var sum = 0.0
        for (i in 0 until pts.size - 1) {
            sum += haversine(pts[i].lat, pts[i].lon, pts[i + 1].lat, pts[i + 1].lon)
        }
        return sum
    }

    /**
     * Point [dist] metres along the polyline. Clamps to the ends. Altitude is
     * interpolated per-segment when both endpoints carry one, else falls back to
     * [fallbackAlt]. Returns null only for an empty list.
     */
    fun interpolateAt(pts: List<GeoPt>, dist: Double, fallbackAlt: Double): GeoPt? {
        if (pts.isEmpty()) return null
        if (pts.size == 1) return pts[0].copy(alt = pts[0].alt ?: fallbackAlt)

        var rem = if (dist < 0.0) 0.0 else dist
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val d = haversine(a.lat, a.lon, b.lat, b.lon)
            if (rem <= d || i == pts.size - 2) {
                val t = if (d > 0.0) min(rem / d, 1.0) else 0.0
                val alt = if (a.alt != null && b.alt != null) a.alt + t * (b.alt - a.alt)
                          else fallbackAlt
                return GeoPt(
                    lat = a.lat + t * (b.lat - a.lat),
                    lon = a.lon + t * (b.lon - a.lon),
                    alt = alt
                )
            }
            rem -= d
        }
        val last = pts.last()
        return last.copy(alt = last.alt ?: fallbackAlt)
    }
}
