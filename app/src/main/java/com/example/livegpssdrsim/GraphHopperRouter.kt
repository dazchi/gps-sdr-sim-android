package com.example.livegpssdrsim

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal GraphHopper Directions API client. Mirrors the reference frontend:
 * requests `points_encoded=false` so the response carries plain GeoJSON
 * coordinates, and `elevation=true` so points include an altitude we can use
 * directly (no separate elevation service). The API key is supplied by the user.
 *
 * GraphHopper's free tier caps points-per-request, so a long waypoint list is
 * split into overlapping segments of [MAX_PTS] that share one junction point,
 * then stitched back into a continuous line.
 */
object GraphHopperRouter {

    private const val MAX_PTS = 5

    class RoutingException(message: String) : Exception(message)

    /**
     * Route through [waypoints] for the given [profile] ("foot" | "bike" | "car").
     * Returns the road geometry as points (with elevation when available).
     * Throws [RoutingException] on any API/parse error.
     */
    suspend fun route(
        waypoints: List<GeoPt>,
        profile: String,
        apiKey: String
    ): List<GeoPt> = withContext(Dispatchers.IO) {
        require(waypoints.size >= 2) { "need at least two waypoints" }
        if (apiKey.isBlank()) throw RoutingException("No GraphHopper API key set")

        val segments = ArrayList<List<GeoPt>>()
        var i = 0
        while (i < waypoints.size - 1) {
            val end = minOf(i + MAX_PTS, waypoints.size)
            segments.add(waypoints.subList(i, end))
            i += MAX_PTS - 1
        }

        val all = ArrayList<GeoPt>()
        for ((idx, seg) in segments.withIndex()) {
            val coords = fetchSegment(seg, profile, apiKey)
            if (idx == 0) all.addAll(coords)
            else if (coords.size > 1) all.addAll(coords.subList(1, coords.size)) // drop shared junction
        }
        all
    }

    private fun fetchSegment(wps: List<GeoPt>, profile: String, key: String): List<GeoPt> {
        val sb = StringBuilder("https://graphhopper.com/api/1/route?")
        for (w in wps) {
            sb.append("point=")
              .append(URLEncoder.encode("${"%.6f".format(w.lat)},${"%.6f".format(w.lon)}", "UTF-8"))
              .append('&')
        }
        sb.append("profile=").append(profile)
          .append("&points_encoded=false&elevation=true&locale=en&key=")
          .append(URLEncoder.encode(key, "UTF-8"))

        val conn = (URL(sb.toString()).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "LiveGpsSdrSim")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()

            if (code !in 200..299) {
                val msg = runCatching { JSONObject(body).optString("message") }.getOrNull()
                throw RoutingException(
                    if (!msg.isNullOrBlank()) msg else "GraphHopper HTTP $code"
                )
            }

            val paths = JSONObject(body).optJSONArray("paths")
                ?: throw RoutingException("No route in response")
            if (paths.length() == 0) throw RoutingException("No route found")
            val coords = paths.getJSONObject(0)
                .getJSONObject("points")
                .getJSONArray("coordinates")

            val out = ArrayList<GeoPt>(coords.length())
            for (j in 0 until coords.length()) {
                val c = coords.getJSONArray(j)
                // GeoJSON order is [lon, lat, (ele)]
                val lon = c.getDouble(0)
                val lat = c.getDouble(1)
                val alt = if (c.length() > 2) c.getDouble(2) else null
                out.add(GeoPt(lat, lon, alt))
            }
            return out
        } finally {
            conn.disconnect()
        }
    }
}
