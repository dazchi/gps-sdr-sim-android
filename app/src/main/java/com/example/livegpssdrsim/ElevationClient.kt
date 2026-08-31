package com.example.livegpssdrsim

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Fills terrain altitude along a polyline from OpenTopoData's public SRTM 30 m
 * dataset (ported from the reference route-planner's elevation enrichment).
 * The geometry is downsampled to [MAX_PTS] sample points (the API's per-request
 * limit), queried in one call, then the sampled elevations are interpolated
 * back onto every point.
 */
object ElevationClient {

    private const val MAX_PTS = 100

    class ElevationException(message: String) : Exception(message)

    /** Returns a copy of [pts] with `alt` filled from SRTM. Throws on API error. */
    suspend fun enrich(pts: List<GeoPt>): List<GeoPt> = withContext(Dispatchers.IO) {
        if (pts.size < 2) return@withContext pts

        val step = maxOf(1, pts.size / MAX_PTS)
        val indices = ArrayList<Int>()
        var i = 0
        while (i < pts.size) { indices.add(i); i += step }
        if (indices.last() != pts.size - 1) indices.add(pts.size - 1)

        val locations = indices.joinToString("|") { "%.6f,%.6f".format(pts[it].lat, pts[it].lon) }
        val url = "https://api.opentopodata.org/v1/srtm30m?locations=" +
            URLEncoder.encode(locations, "UTF-8")

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "LiveGpsSdrSim")
        }
        val elevs: DoubleArray
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = JSONObject(body)
            val results = json.optJSONArray("results")
                ?: throw ElevationException(json.optString("error").ifBlank { "no elevation results" })
            elevs = DoubleArray(results.length()) {
                results.getJSONObject(it).optDouble("elevation", 0.0)
            }
        } finally {
            conn.disconnect()
        }

        if (elevs.size < 2) return@withContext pts.map { it.copy(alt = elevs.getOrNull(0) ?: it.alt) }

        pts.mapIndexed { idx, p ->
            val pos = idx.toDouble() / step
            val lo = minOf(Math.floor(pos).toInt(), elevs.size - 2)
            val t = pos - lo
            p.copy(alt = elevs[lo] + t * (elevs[lo + 1] - elevs[lo]))
        }
    }
}
