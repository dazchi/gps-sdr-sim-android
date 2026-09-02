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
     * Fills every point of [route] with an altitude interpolated from [controls]
     * — typically the user's waypoints, which carry measured (e.g. SRTM) heights.
     * Only the controls need real elevations; each trackpoint between two controls
     * gets a linearly-interpolated height based on its arc-length position along
     * the route. Controls are matched to the nearest route vertex, scanning
     * forward so their order (and the route's direction) is preserved. Returns
     * [route] unchanged when there isn't enough to interpolate.
     */
    fun interpolateAltitudes(route: List<GeoPt>, controls: List<GeoPt>): List<GeoPt> {
        if (route.size < 2 || controls.size < 2) return route

        // Cumulative arc length at each route vertex.
        val cum = DoubleArray(route.size)
        for (i in 1 until route.size) {
            cum[i] = cum[i - 1] +
                haversine(route[i - 1].lat, route[i - 1].lon, route[i].lat, route[i].lon)
        }

        // Distance-along-route of each control (nearest vertex, searched forward).
        val ctrlDist = DoubleArray(controls.size)
        var start = 0
        for (j in controls.indices) {
            var best = start
            var bestD = Double.MAX_VALUE
            for (k in start until route.size) {
                val d = haversine(controls[j].lat, controls[j].lon, route[k].lat, route[k].lon)
                if (d < bestD) { bestD = d; best = k }
            }
            ctrlDist[j] = cum[best]
            start = best
        }
        // Pin the first/last controls to the route's ends.
        ctrlDist[0] = 0.0
        ctrlDist[controls.size - 1] = cum[route.size - 1]

        return route.mapIndexed { i, p ->
            val d = cum[i]
            var seg = 0
            while (seg < controls.size - 2 && ctrlDist[seg + 1] < d) seg++
            val d0 = ctrlDist[seg]
            val d1 = ctrlDist[seg + 1]
            val a0 = controls[seg].alt
            val a1 = controls[seg + 1].alt
            val alt = when {
                a0 != null && a1 != null -> {
                    val t = if (d1 > d0) ((d - d0) / (d1 - d0)).coerceIn(0.0, 1.0) else 0.0
                    a0 + t * (a1 - a0)
                }
                else -> a0 ?: a1
            }
            p.copy(alt = alt)
        }
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
