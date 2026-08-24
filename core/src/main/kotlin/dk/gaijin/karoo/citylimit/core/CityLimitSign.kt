package dk.gaijin.karoo.citylimit.core

import kotlinx.serialization.Serializable

/**
 * A mapped town-entry sign, ready to be matched against the rider's position.
 *
 * Only signs that are shown to traffic *entering* a built-up area are represented; the crossed-out
 * "leaving" signs are dropped while parsing.
 */
@Serializable
data class CityLimitSign(
    /** OSM node id. */
    val id: Long,
    val position: LatLng,
    /** Town name, taken from the sign when mapped, otherwise from the matched place node. */
    val name: String? = null,
    /** `maxspeed` tagged on the sign node, e.g. "50". */
    val maxSpeed: String? = null,
    /**
     * Direction of travel (degrees, 0 = north) of a rider entering the town at this sign, or null
     * when it could not be derived.
     */
    val entryHeading: Double? = null,
    /** OSM id of the place node this sign was matched to, when one was found. */
    val townId: Long? = null,
    /** True when the node is only tagged as a generic boundary, without begin/end distinction. */
    val genericBoundary: Boolean = false,
) {
    /**
     * Key used to avoid alerting twice for the same town. Towns are often signed on several roads,
     * and some boundary nodes carry no name at all, so the matched place node is the better
     * identity when it is known.
     */
    val dedupeKey: String get() = townId?.let { "place/$it" }
        ?: name?.trim()?.lowercase()?.ifEmpty { null }
        ?: "node/$id"
}

/**
 * A `place=*` node used to work out which side of a sign the town is on.
 */
@Serializable
data class PlaceNode(
    val id: Long,
    val position: LatLng,
    val name: String?,
    val kind: String,
)
