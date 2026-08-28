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

    /** Without a name on the sign, no place further away than this is considered at all. */
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
     * Rough importance of a place, used when a sign's name matches several places. A town's outlying
     * hamlets often carry its name plus a qualifier - "Nykøbing Lyng" next to "Nykøbing Sjælland" -
     * and the sign belongs to the main settlement.
     */
    private val PLACE_RANK = mapOf(
        "city" to 6,
        "town" to 5,
        "village" to 4,
        "suburb" to 3,
        "borough" to 3,
        "quarter" to 2,
        "neighbourhood" to 2,
        "hamlet" to 1,
    )

    /**
     * The place a sign belongs to.
     *
     * A sign that carries a name names its own town, so only a place with that name will do. Falling
     * back to the nearest place for a named sign points the arrow at whatever village happens to be
     * closest — signs reading "Lyngen" in Odsherred, where no such place is mapped, end up pointing
     * at neighbouring hamlets, and at a different one depending on which area was downloaded. No
     * direction at all is the honest answer there.
     *
     * Signs and places do not always spell a name the same way. Spacing is ignored, so the sign
     * "Vesterlyng" finds the hamlet "Vester Lyng". And signs drop the regional qualifier a place
     * name carries: the sign into Nykøbing Sjælland reads "Nykøbing". A name that is a whole-word
     * prefix of a place name therefore counts too, but only when it is unambiguous — "Nykøbing" also
     * prefixes the hamlet "Nykøbing Lyng", so the most significant place wins, and a tie means no
     * match rather than a guess.
     *
     * Where those two find nothing, [almostMatch] allows for a name written a little differently,
     * but only for a place close enough to be the one on the sign.
     *
     * An unnamed sign has nothing to match on, and [mostPulling] settles it instead.
     */
    internal fun matchPlace(signPosition: LatLng, signName: String?, places: List<PlaceNode>): PlaceNode? {
        val normalized = signName?.trim()?.lowercase()
        if (!normalized.isNullOrEmpty()) {
            val nearby = places.filter {
                signPosition.distanceTo(it.position) <= MAX_NAMED_PLACE_DISTANCE_METERS
            }
            val spaced = normalized.replace(WHITESPACE, " ")
            return exactMatch(signPosition, spaced, nearby)
                ?: qualifiedMatch(spaced, nearby)
                ?: almostMatch(signPosition, spaced, nearby)
        }
        return mostPulling(signPosition, places)
    }

    /**
     * The town a sign with no name on it belongs to.
     *
     * The nearest place is not the answer. A town sign stands at the boundary, and a larger town's
     * boundary is kilometres from its centre, so the nearest mapped place out there is usually an
     * outlying farm: the sign into Vimmerby found Åbro, the one into Ystad found Öja, the one into
     * Aarhus found Saralyst.
     *
     * A place reaches for a sign as far as it is large. The distance is divided by that reach, and
     * the place that wins is the one the sign lies deepest inside. The weights are not guessed:
     * Denmark's named signs say which town they belong to, and with the name hidden the rule has to
     * answer on its own. The nearest place gets 87.1% of 7,717 signs right, these weights 90.3%.
     * Tried on Sweden, where they were not found, it goes from 76% to 89%.
     *
     * That a suburb reaches no further than a hamlet is not an oversight: place=suburb is a part of
     * a town rather than a town beside it, and must not take the sign from the town it belongs to.
     */
    private val PLACE_PULL = mapOf(
        "city" to 8.0,
        "town" to 6.0,
        "village" to 3.0,
        "suburb" to 1.0,
        "borough" to 1.0,
        "quarter" to 1.0,
        "neighbourhood" to 1.0,
        "hamlet" to 1.0,
    )

    private fun mostPulling(signPosition: LatLng, places: List<PlaceNode>): PlaceNode? = places
        .mapNotNull { place ->
            val away = signPosition.distanceTo(place.position)
            if (away > MAX_NEAREST_PLACE_DISTANCE_METERS) null
            else place to away / (PLACE_PULL[place.kind.lowercase()] ?: 1.0)
        }
        .minByOrNull { it.second }
        ?.first

    private fun exactMatch(signPosition: LatLng, name: String, places: List<PlaceNode>): PlaceNode? {
        val compact = compact(name)
        return places
            .filter { compact(it.name) == compact }
            .minByOrNull { signPosition.distanceTo(it.position) }
    }

    /** Name reduced to letters only, so "Vesterlyng" and "Vester Lyng" compare equal. */
    private fun compact(name: String?): String? =
        name?.trim()?.lowercase()?.replace(COMPACT_PATTERN, "")?.ifEmpty { null }

    private val COMPACT_PATTERN = Regex("[\\s\\-]+")

    /**
     * Match a sign name against place names that only differ by a trailing qualifier, in either
     * direction: sign "Nykøbing" against place "Nykøbing Sjælland", or sign "Ellinge Lyng" against
     * place "Ellinge". Only the most significant candidate counts, and only when it stands alone.
     */
    private fun qualifiedMatch(name: String, places: List<PlaceNode>): PlaceNode? {
        val candidates = places.filter { place ->
            val placeName = place.name?.trim()?.lowercase() ?: return@filter false
            placeName.startsWith("$name ") || name.startsWith("$placeName ")
        }
        if (candidates.isEmpty()) return null
        val topRank = candidates.maxOf { rankOf(it) }
        val best = candidates.filter { rankOf(it) == topRank }
        return best.singleOrNull()
    }

    /**
     * A sign and a place naming the same town do not always spell it the same way.
     *
     * Some of it is grammar - the sign into Strandhuse reads "Strandhusene" - and some is one side
     * or the other being wrong: "Feldbulle" for Feldballe, "Ganløsev" for Ganløse, "Åes" for Ås.
     * Neither the exact nor the qualifier rule can see past a single letter, and 431 named signs in
     * Denmark find no town at all, so they carry no direction and are left out.
     *
     * A place is accepted here when it is within [ALMOST_METERS] and its name is the sign's with the
     * Danish definite ending added or dropped, or one letter away from it once the two are written
     * the same way: without spacing or punctuation, with the common abbreviations spelt out, and
     * with the letters that sound alike folded together. One letter and a kilometre is a deliberately
     * small opening. Held against the signs that do find their town today, with that town taken away
     * so the rule has to answer on its own, it invents a place for 6 of 8064 - and all six are the
     * same town under another label, "Randers SV" for Randers, "Højslev" for "Højslev K.".
     *
     * Two candidates equally close in spelling mean no answer, and so does a difference that is only
     * a direction word: Øster Sottrup and Vester Sottrup are one letter apart and two different
     * villages. That word is the name, not a slip of the pen.
     */
    private fun almostMatch(signPosition: LatLng, name: String, places: List<PlaceNode>): PlaceNode? {
        val plain = writtenAlike(name)
        val signKey = soundAlike(plain)

        val scored = places.mapNotNull { place ->
            val placeName = place.name?.trim()?.lowercase() ?: return@mapNotNull null
            val metres = signPosition.distanceTo(place.position)
            if (metres > ALMOST_METERS) return@mapNotNull null
            val placePlain = writtenAlike(placeName)
            val placeKey = soundAlike(placePlain)
            if (differOnlyByDirection(plain, placePlain)) return@mapNotNull null
            val cost = when {
                definiteFormOf(signKey, placeKey) -> 0
                // A letter out of three is a different place, not a slip; grammar is not a guess,
                // so only the spelling rule needs a name long enough to be sure of.
                maxOf(signKey.length, placeKey.length) < MIN_ALMOST_LETTERS -> 2
                else -> editDistance(signKey, placeKey)
            }
            if (cost > 1) null else Triple(place, cost, metres)
        }.sortedWith(compareBy({ it.second }, { it.third }))

        val best = scored.firstOrNull() ?: return null
        if (scored.size > 1 && scored[1].second == best.second) return null
        return best.first
    }

    /** How close a place has to be before a name written a little differently is worth believing. */
    private const val ALMOST_METERS = 1_000.0

    /** Shorter than this, one letter is too much of the name to let go of. */
    private const val MIN_ALMOST_LETTERS = 4

    /** Punctuation and spacing dropped, and the abbreviations Danish signs use written out. */
    private fun writtenAlike(name: String): String {
        var text = name
        for ((short, long) in ABBREVIATIONS) text = text.replace(short, long)
        return text.replace(NOT_A_LETTER, "")
    }

    private val ABBREVIATIONS = listOf(
        Regex("\\bkr\\b\\.?") to "kirke",
        Regex("\\bgl\\b\\.?") to "gammel",
        Regex("\\bnr\\b\\.?") to "nørre",
        Regex("\\bsdr\\b\\.?") to "sønder",
        Regex("\\bskt\\b\\.?") to "sankt",
        Regex("\\bst\\b\\.?") to "store",
        Regex("\\bll\\b\\.?") to "lille",
    )

    private val NOT_A_LETTER = Regex("[\\s.\\-']+")

    /** Letters that sound alike written the same way, so "Åes" is one letter from "Ås", not three. */
    private fun soundAlike(text: String): String = text
        .replace("å", "aa")
        .replace("æ", "ae")
        .replace("ø", "oe")
        .replace(ACCENTED_E, "e")
        .replace("ck", "k")

    private val ACCENTED_E = Regex("[éèê]")

    /** "Strandhusene" is "Strandhuse" spoken of as a place one is entering, not another town. */
    private fun definiteFormOf(one: String, other: String): Boolean =
        DEFINITE_ENDINGS.any { one == other + it || other == one + it }

    private val DEFINITE_ENDINGS = listOf("erne", "rne", "ne", "en", "et", "e")

    /**
     * Whether two names are the same but for a word that tells two settlements apart. Øster and
     * Vester Sottrup differ by one letter and are two villages a kilometre apart.
     */
    private fun differOnlyByDirection(one: String, other: String): Boolean =
        one != other && one.replace(DIRECTIONS, "") == other.replace(DIRECTIONS, "")

    private val DIRECTIONS =
        Regex("(øster|vester|nørre|sønder|nord|syd|øst|vest|over|neder|store|lille|gammel|ny|indre|ydre)")

    /** Levenshtein distance: how many letters have to change for one name to become the other. */
    private fun editDistance(one: String, other: String): Int {
        if (one == other) return 0
        var previous = IntArray(other.length + 1) { it }
        for (i in 1..one.length) {
            val current = IntArray(other.length + 1)
            current[0] = i
            for (j in 1..other.length) {
                val substitute = previous[j - 1] + if (one[i - 1] == other[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitute)
            }
            previous = current
        }
        return previous[other.length]
    }

    private fun rankOf(place: PlaceNode): Int = PLACE_RANK[place.kind.lowercase()] ?: 0

    private val WHITESPACE = Regex("\\s+")

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
