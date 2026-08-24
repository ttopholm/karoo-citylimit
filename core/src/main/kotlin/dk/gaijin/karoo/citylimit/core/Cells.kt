package dk.gaijin.karoo.citylimit.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max

/**
 * A fixed lat/lng grid used to download and cache signs in chunks that are small enough for the
 * 100 KB limit on requests made through the Karoo system, and large enough that a ride does not
 * trigger a download every few minutes.
 *
 * One cell is roughly 5.5 km north-south and 4-7 km east-west at European latitudes.
 */
object Cells {
    const val LAT_STEP = 0.05
    const val LNG_STEP = 0.10

    /** Cells are downloaded with a small overlap so that signs just outside are known too. */
    const val MARGIN_DEGREES = 0.01

    data class Key(val latIndex: Int, val lngIndex: Int) {
        val id: String get() = "$latIndex/$lngIndex"

        fun bounds(): BoundingBox = BoundingBox(
            south = latIndex * LAT_STEP,
            west = lngIndex * LNG_STEP,
            north = (latIndex + 1) * LAT_STEP,
            east = (lngIndex + 1) * LNG_STEP,
        )

        /** Area to query: the cell plus a margin, so signs near the border are included. */
        fun queryBounds(): BoundingBox = bounds().expand(MARGIN_DEGREES, MARGIN_DEGREES)

        companion object {
            fun parse(id: String): Key? {
                val parts = id.split('/')
                if (parts.size != 2) return null
                val lat = parts[0].toIntOrNull() ?: return null
                val lng = parts[1].toIntOrNull() ?: return null
                return Key(lat, lng)
            }
        }
    }

    fun keyFor(position: LatLng): Key = Key(
        latIndex = floor(position.lat / LAT_STEP).toInt(),
        lngIndex = floor(position.lng / LNG_STEP).toInt(),
    )

    /**
     * Every cell that overlaps a circle of [radiusMeters] around [position]. Used to make sure the
     * cell the rider is in - and any cell they are about to ride into - is downloaded.
     */
    fun keysWithin(position: LatLng, radiusMeters: Double): List<Key> {
        val latDelta = radiusMeters / 111_320.0
        val lngDelta = radiusMeters / max(1.0, 111_320.0 * cos(Math.toRadians(position.lat)))
        val south = floor((position.lat - latDelta) / LAT_STEP).toInt()
        val north = floor((position.lat + latDelta) / LAT_STEP).toInt()
        val west = floor((position.lng - lngDelta) / LNG_STEP).toInt()
        val east = floor((position.lng + lngDelta) / LNG_STEP).toInt()
        val keys = ArrayList<Key>()
        for (lat in south..north) {
            for (lng in west..east) {
                keys.add(Key(lat, lng))
            }
        }
        return keys.sortedBy { key ->
            val center = LatLng(
                (key.latIndex + 0.5) * LAT_STEP,
                (key.lngIndex + 0.5) * LNG_STEP,
            )
            position.distanceTo(center)
        }
    }

    /**
     * Cells touched by a route, in the order they are ridden, so a route can be prefetched before
     * leaving home.
     */
    fun keysAlong(points: List<LatLng>): List<Key> {
        val seen = LinkedHashSet<Key>()
        var previous: LatLng? = null
        for (point in points) {
            val last = previous
            if (last != null && shouldInterpolate(last, point)) {
                val steps = interpolationSteps(last, point)
                for (step in 1 until steps) {
                    val fraction = step.toDouble() / steps
                    seen.add(
                        keyFor(
                            LatLng(
                                last.lat + (point.lat - last.lat) * fraction,
                                last.lng + (point.lng - last.lng) * fraction,
                            ),
                        ),
                    )
                }
            }
            seen.add(keyFor(point))
            previous = point
        }
        return seen.toList()
    }

    private fun shouldInterpolate(a: LatLng, b: LatLng): Boolean =
        abs(a.lat - b.lat) > LAT_STEP / 2 || abs(a.lng - b.lng) > LNG_STEP / 2

    private fun interpolationSteps(a: LatLng, b: LatLng): Int {
        val latSteps = abs(a.lat - b.lat) / (LAT_STEP / 2)
        val lngSteps = abs(a.lng - b.lng) / (LNG_STEP / 2)
        return max(2, max(latSteps, lngSteps).toInt() + 1)
    }
}
