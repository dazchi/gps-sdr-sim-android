package com.dazchi.livegpssdrsim

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * Tiny GPX reader/writer matching the reference route-planner's format.
 *
 * Read: prefers <wpt> (discrete named waypoints); if none are present, falls
 * back to <trkpt> from the track. <ele> is captured as altitude when present.
 * Write: emits a single <trk>/<trkseg> of <trkpt> with optional <ele>.
 */
object GpxIo {

    /** Parse waypoints from a GPX stream. Returns an empty list if none found. */
    fun parse(input: InputStream): List<GeoPt> {
        val wpt = ArrayList<GeoPt>()
        val trkpt = ArrayList<GeoPt>()

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var event = parser.eventType
        var curTag: String? = null       // "wpt" or "trkpt" while inside one
        var curLat = 0.0
        var curLon = 0.0
        var curEle: Double? = null
        var inEle = false

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.lowercase()) {
                        "wpt", "trkpt" -> {
                            curTag = parser.name.lowercase()
                            curLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: Double.NaN
                            curLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: Double.NaN
                            curEle = null
                        }
                        "ele" -> if (curTag != null) inEle = true
                    }
                }
                XmlPullParser.TEXT -> if (inEle) curEle = parser.text?.trim()?.toDoubleOrNull()
                XmlPullParser.END_TAG -> {
                    when (parser.name.lowercase()) {
                        "ele" -> inEle = false
                        "wpt", "trkpt" -> {
                            if (curTag != null && curLat.isFinite() && curLon.isFinite()) {
                                val p = GeoPt(curLat, curLon, curEle)
                                if (curTag == "wpt") wpt.add(p) else trkpt.add(p)
                            }
                            curTag = null
                        }
                    }
                }
            }
            event = parser.next()
        }
        return if (wpt.isNotEmpty()) wpt else trkpt
    }

    /** Serialize points to a GPX 1.1 document string. */
    fun build(pts: List<GeoPt>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"LiveGpsSdrSim\"\n")
        sb.append("     xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <trk>\n    <name>Route</name>\n    <trkseg>\n")
        for (p in pts) {
            sb.append("      <trkpt lat=\"").append("%.8f".format(p.lat))
              .append("\" lon=\"").append("%.8f".format(p.lon)).append("\">")
            if (p.alt != null) sb.append("\n        <ele>").append("%.1f".format(p.alt)).append("</ele>\n      ")
            sb.append("</trkpt>\n")
        }
        sb.append("    </trkseg>\n  </trk>\n</gpx>\n")
        return sb.toString()
    }
}
