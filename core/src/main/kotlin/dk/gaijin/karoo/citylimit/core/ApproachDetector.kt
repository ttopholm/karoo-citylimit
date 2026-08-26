package dk.gaijin.karoo.citylimit.core

/**
 * Tunables for [ApproachDetector].
 */
data class DetectorConfig(
    /** How far ahead of the sign the alert is raised. */
    val alertDistanceMeters: Double = 200.0,
    /** The sign has to be within this many degrees of straight ahead. */
    val approachConeDeg: Double = 55.0,
    /** The rider's heading has to be within this many degrees of the direction into the town. */
    val entryConeDeg: Double = 80.0,
    /**
     * How far the rider's heading may differ from the road the sign stands on. A sign at a junction
     * can sit a few metres from the road you are riding and still belong to the side road; the road
     * it stands on tells them apart. Only checked for signs that know their road.
     */
    val roadConeDeg: Double = 45.0,
    /**
     * Whether to alert for signs where it could not be worked out which direction is "into town".
     * Off by default: alerting on those risks announcing a town while riding *out* of it.
     */
    val alertWhenDirectionUnknown: Boolean = false,
    /** The same town is not announced again within this period. */
    val cooldownMillis: Long = 10 * 60 * 1000L,
)

/**
 * The alert to show when a town-entry sign is coming up.
 */
data class CityLimitAlert(
    val sign: CityLimitSign,
    val distanceMeters: Double,
)

/**
 * Decides when a rider is approaching a town-entry sign - and only then.
 *
 * A sign is announced when all of the following hold:
 *  1. it is within the alert distance,
 *  2. it lies ahead of the rider (their heading points at it),
 *  3. the rider is heading *into* the town rather than out of it,
 *  4. the same town has not just been announced.
 */
class ApproachDetector(
    private val config: DetectorConfig = DetectorConfig(),
) {
    private val announced = HashMap<String, Long>()

    /**
     * @param position current position
     * @param heading current direction of travel in degrees, or null when unknown (e.g. standing still)
     * @param signs known town-entry signs, typically everything cached nearby
     * @param nowMillis current time, used for the per-town cooldown
     * @return the alert to show, or null
     */
    fun update(
        position: LatLng,
        heading: Double?,
        signs: List<CityLimitSign>,
        nowMillis: Long,
    ): CityLimitAlert? {
        if (heading == null) return null
        prune(nowMillis)

        val candidate = signs
            .asSequence()
            .map { sign -> sign to position.distanceTo(sign.position) }
            .filter { (_, distance) -> distance <= config.alertDistanceMeters }
            .filter { (sign, _) -> isAhead(position, heading, sign) }
            .filter { (sign, _) -> isEnteringTown(heading, sign) }
            .filter { (sign, _) -> isOnTheRoadAhead(heading, sign) }
            .filter { (sign, _) -> !recentlyAnnounced(sign, nowMillis) }
            .minByOrNull { (_, distance) -> distance }
            ?: return null

        val (sign, distance) = candidate
        announced[sign.dedupeKey] = nowMillis
        return CityLimitAlert(sign = sign, distanceMeters = distance)
    }

    /** Forget which towns have been announced, e.g. when a new ride starts. */
    fun reset() {
        announced.clear()
    }

    private fun isAhead(position: LatLng, heading: Double, sign: CityLimitSign): Boolean {
        val bearingToSign = position.bearingTo(sign.position)
        return bearingDifference(heading, bearingToSign) <= config.approachConeDeg
    }

    private fun isEnteringTown(heading: Double, sign: CityLimitSign): Boolean {
        val entryHeading = sign.entryHeading ?: return config.alertWhenDirectionUnknown
        return bearingDifference(heading, entryHeading) <= config.entryConeDeg
    }

    /**
     * A sign belongs to the road it stands on. Riding across a junction, a sign on the side road can
     * be a few metres away, ahead of you, and pointing into the same town — the road it stands on is
     * what rules it out.
     */
    private fun isOnTheRoadAhead(heading: Double, sign: CityLimitSign): Boolean {
        val roadBearing = sign.roadBearing ?: return true
        return lineDifference(heading, roadBearing) <= config.roadConeDeg
    }

    private fun recentlyAnnounced(sign: CityLimitSign, nowMillis: Long): Boolean {
        val last = announced[sign.dedupeKey] ?: return false
        return nowMillis - last < config.cooldownMillis
    }

    private fun prune(nowMillis: Long) {
        announced.entries.removeAll { nowMillis - it.value >= config.cooldownMillis }
    }
}
