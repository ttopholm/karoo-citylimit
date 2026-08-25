package dk.gaijin.karoo.citylimit.core

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

const val EARTH_RADIUS_METERS = 6371008.8

@Serializable
data class LatLng(val lat: Double, val lng: Double)

/**
 * Great-circle distance in meters between two positions.
 */
fun LatLng.distanceTo(other: LatLng): Double {
    val lat1 = Math.toRadians(lat)
    val lat2 = Math.toRadians(other.lat)
    val dLat = lat2 - lat1
    val dLng = Math.toRadians(other.lng - lng)
    val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(a)))
}

/**
 * Initial bearing in degrees (0 = north, 90 = east) from this position towards [other].
 */
fun LatLng.bearingTo(other: LatLng): Double {
    val lat1 = Math.toRadians(lat)
    val lat2 = Math.toRadians(other.lat)
    val dLng = Math.toRadians(other.lng - lng)
    val y = sin(dLng) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
    return normalizeBearing(Math.toDegrees(atan2(y, x)))
}

/**
 * Position reached by travelling [distanceMeters] along [bearingDeg] from this position.
 */
fun LatLng.destination(distanceMeters: Double, bearingDeg: Double): LatLng {
    val angular = distanceMeters / EARTH_RADIUS_METERS
    val lat1 = Math.toRadians(lat)
    val lng1 = Math.toRadians(lng)
    val bearing = Math.toRadians(bearingDeg)
    val lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearing))
    val lng2 = lng1 + atan2(
        sin(bearing) * sin(angular) * cos(lat1),
        cos(angular) - sin(lat1) * sin(lat2),
    )
    return LatLng(Math.toDegrees(lat2), normalizeLongitude(Math.toDegrees(lng2)))
}

fun normalizeBearing(deg: Double): Double {
    val mod = deg % 360.0
    return if (mod < 0) mod + 360.0 else mod
}

fun normalizeLongitude(deg: Double): Double {
    var lng = deg
    while (lng > 180.0) lng -= 360.0
    while (lng < -180.0) lng += 360.0
    return lng
}

/**
 * Smallest absolute difference between two bearings, in degrees (0..180).
 */
fun bearingDifference(a: Double, b: Double): Double {
    val diff = abs(normalizeBearing(a) - normalizeBearing(b)) % 360.0
    return if (diff > 180.0) 360.0 - diff else diff
}

/**
 * Bounding box in WGS84 degrees.
 */
@Serializable
data class BoundingBox(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {
    fun contains(point: LatLng): Boolean =
        point.lat in south..north && point.lng in west..east

    fun expand(latDegrees: Double, lngDegrees: Double): BoundingBox = BoundingBox(
        south = max(-90.0, south - latDegrees),
        west = max(-180.0, west - lngDegrees),
        north = min(90.0, north + latDegrees),
        east = min(180.0, east + lngDegrees),
    )

    /**
     * Grow the box by roughly [meters] in every direction.
     */
    fun expandMeters(meters: Double): BoundingBox {
        val midLatitude = (south + north) / 2
        val latDegrees = meters / 111_320.0
        val lngDegrees = meters / max(1.0, 111_320.0 * cos(Math.toRadians(midLatitude)))
        return expand(latDegrees, lngDegrees)
    }
}

/**
 * Decoder for Google encoded polylines, used by Karoo for route geometry.
 */
fun decodePolyline(encoded: String, precision: Int = 5): List<LatLng> {
    val factor = Math.pow(10.0, precision.toDouble())
    val points = ArrayList<LatLng>()
    var index = 0
    var lat = 0
    var lng = 0
    while (index < encoded.length) {
        var result = 0
        var shift = 0
        var b: Int
        do {
            if (index >= encoded.length) return points
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

        result = 0
        shift = 0
        do {
            if (index >= encoded.length) return points
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1

        points.add(LatLng(lat / factor, lng / factor))
    }
    return points
}
