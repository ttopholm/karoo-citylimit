package dk.gaijin.karoo.citylimit.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Builds and parses the Overpass API requests used to look up town-entry signs.
 */
object Overpass {
    /** `place` values that are considered a town centre for the purposes of this extension. */
    const val PLACE_REGEX = "^(city|town|village|hamlet|suburb|borough|quarter)$"

    /** A place with the same name is accepted as the sign's town within this distance. */
    const val MAX_NAMED_PLACE_DISTANCE_METERS = 15_000.0

    /** Without a name on the sign, the nearest place within this distance is used instead. */
    const val MAX_NEAREST_PLACE_DISTANCE_METERS = 3_000.0

    /** Below this distance the bearing from sign to town centre is too noisy to be useful. */
    const val MIN_PLACE_DISTANCE_METERS = 25.0

    /**
     * Places are collected from a wider area than the signs, so a sign near the edge of a cell can
     * still find the town it names. Without this the same sign resolves differently depending on
     * which cell it is looked up from.
     */
    const val PLACE_SEARCH_MARGIN_METERS = 5_000.0

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Overpass QL query returning every city-limit sign node plus the places needed to tell entry
     * from exit within [bbox].
     *
     * Places are collected as nodes, ways and relations: many towns are mapped only as an area, and
     * `out center` gives those a usable centre point without pulling in their full geometry.
     */
    fun query(bbox: BoundingBox, timeoutSeconds: Int = 60): String {
        val signBox = box(bbox)
        val placeBox = box(bbox.expandMeters(PLACE_SEARCH_MARGIN_METERS))
        return """
            [out:json][timeout:$timeoutSeconds];
            (
              node($signBox)[~"^traffic_sign(:(forward|backward|both))?${'$'}"~"${TrafficSignCodes.OVERPASS_VALUE_REGEX}",i];
              node($placeBox)["place"~"$PLACE_REGEX"];
              way($placeBox)["place"~"$PLACE_REGEX"];
              relation($placeBox)["place"~"$PLACE_REGEX"];
            );
            out center qt;
        """.trimIndent()
    }

    /**
     * Parse an Overpass JSON response into the town-entry signs it contains.
     *
     * Signs that only carry the crossed-out "leaving town" variant are dropped, and the direction
     * a rider must be travelling to be entering the town is derived from the nearby place node.
     */
    fun parseSigns(body: String): List<CityLimitSign> {
        val response = json.decodeFromString<OverpassResponse>(body)
        val places = response.elements.mapNotNull { it.toPlace() }
        return response.elements.mapNotNull { element ->
            if (element.isArea) return@mapNotNull null
            val position = element.position() ?: return@mapNotNull null
            val sides = TrafficSignCodes.classify(element.tags)
            if (!sides.entry) return@mapNotNull null
            val signName = (element.tags["name"] ?: element.tags["city_limit:name"])?.trim()?.ifEmpty { null }
            val place = matchPlace(position, signName, places)
            CityLimitSign(
                id = element.id,
                position = position,
                name = signName ?: place?.name?.trim()?.ifEmpty { null },
                maxSpeed = element.tags["maxspeed"]?.trim()?.ifEmpty { null },
                entryHeading = entryHeading(position, place),
                townId = place?.id,
                genericBoundary = !sides.directional,
            )
        }
    }

    /**
     * The heading of a rider entering the town: the bearing from the sign towards the centre of the
     * town it belongs to. A rider heading roughly that way is driving in, a rider heading the other
     * way is driving out and passes the crossed-out sign instead.
     */
    internal fun entryHeading(signPosition: LatLng, place: PlaceNode?): Double? {
        if (place == null) return null
        val distance = signPosition.distanceTo(place.position)
        if (distance < MIN_PLACE_DISTANCE_METERS) return null
        return signPosition.bearingTo(place.position)
    }

    /**
     * The place a sign belongs to.
     *
     * A sign that carries a name names its own town, so only a place with that name will do. Falling
     * back to the nearest place for a named sign points the arrow at whatever village happens to be
     * closest — signs reading "Lyngen" in Odsherred, where no such place is mapped, end up pointing
     * at neighbouring hamlets, and at a different one depending on which area was downloaded. No
     * direction at all is the honest answer there.
     *
     * An unnamed sign has nothing to match on, so the nearest place is the best available guess.
     * Mapped nodes win over area centres, since a node sits at the town centre while an area centre
     * is only the middle of its bounding box.
     */
    internal fun matchPlace(signPosition: LatLng, signName: String?, places: List<PlaceNode>): PlaceNode? {
        val normalized = signName?.trim()?.lowercase()
        if (!normalized.isNullOrEmpty()) {
            return places
                .filter { it.name?.trim()?.lowercase() == normalized }
                .minByOrNull { signPosition.distanceTo(it.position) }
                ?.takeIf { signPosition.distanceTo(it.position) <= MAX_NAMED_PLACE_DISTANCE_METERS }
        }
        val (nodes, areas) = places.partition { !it.isArea }
        return nearestWithin(signPosition, nodes) ?: nearestWithin(signPosition, areas)
    }

    private fun nearestWithin(signPosition: LatLng, places: List<PlaceNode>): PlaceNode? =
        places
            .minByOrNull { signPosition.distanceTo(it.position) }
            ?.takeIf { signPosition.distanceTo(it.position) <= MAX_NEAREST_PLACE_DISTANCE_METERS }

    @Serializable
    private data class OverpassResponse(
        val elements: List<OverpassElement> = emptyList(),
    )

    @Serializable
    private data class OverpassElement(
        val type: String = "node",
        val id: Long = 0,
        val lat: Double? = null,
        val lon: Double? = null,
        /** Present for ways and relations queried with `out center`. */
        val center: Center? = null,
        @SerialName("tags") val tags: Map<String, String> = emptyMap(),
    ) {
        val isArea: Boolean get() = type != "node"

        fun position(): LatLng? {
            val lat = lat ?: center?.lat ?: return null
            val lon = lon ?: center?.lon ?: return null
            return LatLng(lat, lon)
        }

        fun toPlace(): PlaceNode? {
            val kind = tags["place"] ?: return null
            val position = position() ?: return null
            return PlaceNode(id = id, position = position, name = tags["name"], kind = kind, isArea = isArea)
        }
    }

    @Serializable
    private data class Center(val lat: Double, val lon: Double)

    private fun box(bbox: BoundingBox): String =
        "${fmt(bbox.south)},${fmt(bbox.west)},${fmt(bbox.north)},${fmt(bbox.east)}"

    private fun fmt(value: Double): String = String.format(Locale.ROOT, "%.6f", value)
}
