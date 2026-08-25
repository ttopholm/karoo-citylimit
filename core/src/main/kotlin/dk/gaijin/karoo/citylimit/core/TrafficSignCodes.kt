package dk.gaijin.karoo.citylimit.core

/**
 * Which side(s) of a town boundary a mapped traffic sign node describes.
 *
 * @property entry the node carries a sign that is shown to traffic *entering* the built-up area
 *   (DK E55 "Tættere bebygget område", DE 310, ...).
 * @property exit the node carries the crossed-out counterpart shown when *leaving*
 *   (DK E56, DE 311, ...).
 * @property directional true when the tagging distinguishes entry from exit, false when the node
 *   is only tagged as a generic `traffic_sign=city_limit` boundary that is an entry sign in one
 *   direction of travel and an exit sign in the other.
 */
data class SignSides(
    val entry: Boolean,
    val exit: Boolean,
    val directional: Boolean,
) {
    val isBoundary: Boolean get() = entry || exit

    companion object {
        val NONE = SignSides(entry = false, exit = false, directional = false)
    }
}

/**
 * Recognises the national traffic sign codes used in OpenStreetMap for the start and end of a
 * built-up area ("byskilt" and "byskilt med streg over" in Danish).
 */
object TrafficSignCodes {
    private const val GENERIC = "city_limit"

    private val ENTRY = Regex(
        "^(" +
            "city_limit:begin|" +
            "dk:e55|" +
            "de:310|" +
            "at:53-?17a|at:53a|" +
            "ch:4\\.(27|29)|" +
            "se:e5|" +
            "no:365|" +
            "fi:571|" +
            "nl:h01|" +
            "be:f1[ab]?|" +
            "fr:eb10|" +
            "es:s-?500|" +
            "pl:d-?42|" +
            "cz:iz4a" +
            ")$",
    )

    private val EXIT = Regex(
        "^(" +
            "city_limit:end|" +
            "dk:e56|" +
            "de:311|" +
            "at:53-?17b|at:53b|" +
            "ch:4\\.(28|30)|" +
            "se:e6|" +
            "no:366|" +
            "fi:572|" +
            "nl:h02|" +
            "be:f3[ab]?|" +
            "fr:eb20|" +
            "es:s-?510|" +
            "pl:d-?43|" +
            "cz:iz4b" +
            ")$",
    )

    /**
     * Keys that may hold a sign code. The `:forward`/`:backward` suffixed variants tell which
     * direction of travel the sign applies to relative to the way it sits on.
     */
    private val SIGN_KEY = Regex("^traffic_sign(:(forward|backward|both))?$")

    /**
     * Regex fragment (Overpass syntax) matching every value this class understands, used to keep
     * the amount of data downloaded from Overpass small.
     */
    const val OVERPASS_VALUE_REGEX =
        "city_limit|E5[56]|31[01]|53-?17[ab]|4\\.(27|28|29|30)|36[56]|57[12]|H0[12]|F[13][ab]?|EB[12]0|S-?5[01]0|D-?4[23]|IZ4[ab]"

    /**
     * Classify the tags of an OSM node into the sides of the town boundary it represents.
     */
    fun classify(tags: Map<String, String>): SignSides {
        var entry = false
        var exit = false
        var directional = false
        var generic = false

        for ((key, value) in tags) {
            if (!SIGN_KEY.matches(key)) continue
            for (token in splitCodes(value)) {
                when {
                    token == GENERIC -> generic = true
                    EXIT.matches(token) -> {
                        exit = true
                        directional = true
                    }
                    ENTRY.matches(token) -> {
                        entry = true
                        directional = true
                    }
                }
            }
        }

        if (generic) {
            // `city_limit=begin|end` refines a generic boundary node when present.
            when (tags["city_limit"]?.lowercase()?.trim()) {
                "begin", "start" -> {
                    entry = true
                    directional = true
                }
                "end" -> {
                    exit = true
                    directional = true
                }
                else -> {
                    // A plain boundary node: entering from one side, leaving from the other.
                    entry = true
                    exit = true
                }
            }
        }

        return if (!entry && !exit) SignSides.NONE else SignSides(entry, exit, directional)
    }

    /**
     * `traffic_sign` values are `;`-separated lists and may carry sign parameters in brackets,
     * e.g. `DE:310[Berlin];DE:1000-30`.
     */
    private fun splitCodes(value: String): List<String> =
        value.split(';')
            .map { it.substringBefore('[').trim().lowercase() }
            .filter { it.isNotEmpty() }
}
