package dk.gaijin.karoo.citylimit.extension

import android.content.Intent
import dk.gaijin.karoo.citylimit.BuildConfig
import dk.gaijin.karoo.citylimit.R
import dk.gaijin.karoo.citylimit.core.ApproachDetector
import dk.gaijin.karoo.citylimit.core.CityLimitAlert
import dk.gaijin.karoo.citylimit.core.CityLimitSign
import dk.gaijin.karoo.citylimit.core.LatLng
import dk.gaijin.karoo.citylimit.core.decodePolyline
import dk.gaijin.karoo.citylimit.core.distanceTo
import dk.gaijin.karoo.citylimit.data.CityLimitSettings
import dk.gaijin.karoo.citylimit.data.KarooHttp
import dk.gaijin.karoo.citylimit.data.SettingsStore
import dk.gaijin.karoo.citylimit.data.SignCache
import dk.gaijin.karoo.citylimit.data.SignRepository
import dk.gaijin.karoo.citylimit.ui.MainActivity
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.PlayBeepPattern
import io.hammerhead.karooext.models.RideState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The Karoo extension itself: watches the rider's position and shows an in-ride alert when a
 * town-entry sign is coming up.
 */
class CityLimitExtension : KarooExtension(EXTENSION_ID, BuildConfig.VERSION_NAME) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var karooSystem: KarooSystemService
    private lateinit var settingsStore: SettingsStore
    private lateinit var repository: SignRepository
    private lateinit var settings: StateFlow<CityLimitSettings>

    private var detector = ApproachDetector()
    private var detectorSettings: CityLimitSettings? = null

    private val rideState = MutableStateFlow<RideState>(RideState.Idle)
    private var lastLocation: LatLng? = null

    /** Signs held in memory for matching, refreshed as the rider moves. */
    private var nearbySigns: List<CityLimitSign> = emptyList()
    private var nearbySignsAt: LatLng? = null
    private var lastCoverageCheck = 0L
    private var prefetchedRoute: String? = null
    private var coverageJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(applicationContext)
        settingsStore = SettingsStore(applicationContext)
        repository = SignRepository(
            cache = SignCache(java.io.File(applicationContext.filesDir, SignCache.FILE_NAME)),
            http = KarooHttp(karooSystem),
        )
        settings = settingsStore.settings.stateIn(scope, SharingStarted.Eagerly, CityLimitSettings())

        karooSystem.connect { connected ->
            Timber.i("Karoo system connected: %s", connected)
        }

        scope.launch {
            karooSystem.consumerFlow<RideState>().collect { state ->
                if (state is RideState.Recording && rideState.value !is RideState.Recording) {
                    detector.reset()
                }
                rideState.value = state
            }
        }

        scope.launch {
            karooSystem.consumerFlow<OnLocationChanged>().collect(::onLocation)
        }

        scope.launch {
            karooSystem.consumerFlow<OnNavigationState>()
                .distinctUntilChanged()
                .collect(::onNavigationState)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        karooSystem.disconnect()
        super.onDestroy()
    }

    override fun onBonusAction(actionId: String) {
        when (actionId) {
            ACTION_OPEN_SETTINGS -> {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            ACTION_DOWNLOAD_AREA -> {
                val position = lastLocation ?: return
                scope.launch {
                    repository.ensureCoverage(position, settings.value.overpassUrl)
                    refreshNearbySigns(position, force = true)
                }
            }
            else -> Timber.w("Unknown bonus action %s", actionId)
        }
    }

    private suspend fun onLocation(event: OnLocationChanged) {
        val position = LatLng(event.lat, event.lng)
        lastLocation = position
        val current = settings.value
        if (!current.enabled) return
        if (current.downloadWhileRiding && shouldRide(current)) {
            ensureCoverage(position, current)
        }
        if (!shouldRide(current)) return

        refreshNearbySigns(position)
        applyDetectorSettings(current)

        val alert = detector.update(
            position = position,
            heading = event.orientation,
            signs = nearbySigns,
            nowMillis = System.currentTimeMillis(),
        ) ?: return
        announce(alert, current)
    }

    private fun onNavigationState(event: OnNavigationState) {
        val polyline = when (val state = event.state) {
            is OnNavigationState.NavigationState.NavigatingRoute -> state.routePolyline
            is OnNavigationState.NavigationState.NavigatingToDestination -> state.polyline
            OnNavigationState.NavigationState.Idle -> null
        } ?: return
        if (polyline == prefetchedRoute) return
        if (!settings.value.enabled || !settings.value.downloadWhileRiding) return
        prefetchedRoute = polyline
        scope.launch {
            val points = decodePolyline(polyline)
            Timber.i("Prefetching signs along a route with %d points", points.size)
            repository.prefetchRoute(points, settings.value.overpassUrl)
            lastLocation?.let { refreshNearbySigns(it, force = true) }
        }
    }

    private fun shouldRide(current: CityLimitSettings): Boolean =
        !current.onlyWhileRecording || rideState.value is RideState.Recording

    private fun ensureCoverage(position: LatLng, current: CityLimitSettings) {
        if (coverageJob?.isActive == true) return
        val timestamp = System.currentTimeMillis()
        if (timestamp - lastCoverageCheck < COVERAGE_CHECK_INTERVAL_MILLIS) return
        lastCoverageCheck = timestamp
        coverageJob = scope.launch {
            repository.ensureCoverage(position, current.overpassUrl)
            refreshNearbySigns(position, force = true)
        }
    }

    private suspend fun refreshNearbySigns(position: LatLng, force: Boolean = false) {
        val anchor = nearbySignsAt
        if (!force && anchor != null && anchor.distanceTo(position) < SIGN_REFRESH_DISTANCE_METERS) return
        nearbySigns = repository.signsNear(position)
        nearbySignsAt = position
    }

    private fun applyDetectorSettings(current: CityLimitSettings) {
        if (detectorSettings?.toDetectorConfig() == current.toDetectorConfig()) return
        detectorSettings = current
        detector = ApproachDetector(current.toDetectorConfig())
    }

    private fun announce(alert: CityLimitAlert, current: CityLimitSettings) {
        val town = alert.sign.name ?: getString(R.string.alert_title_unnamed)
        val distance = alert.distanceMeters.toInt().roundedToTens()
        val detail = alert.sign.maxSpeed
            ?.let { getString(R.string.alert_detail_with_speed, distance, it) }
            ?: getString(R.string.alert_detail, distance)
        Timber.i("Announcing %s in %d m", town, distance)

        karooSystem.dispatch(
            InRideAlert(
                id = "citylimit-${alert.sign.id}",
                icon = R.drawable.ic_city_limit,
                title = town,
                detail = detail,
                autoDismissMs = current.alertDurationSeconds * 1_000L,
                backgroundColor = R.color.alert_background,
                textColor = R.color.alert_text,
            ),
        )
        if (current.beep) {
            karooSystem.dispatch(
                PlayBeepPattern(
                    listOf(
                        PlayBeepPattern.Tone(frequency = 1_000, durationMs = 150),
                        PlayBeepPattern.Tone(frequency = null, durationMs = 80),
                        PlayBeepPattern.Tone(frequency = 1_400, durationMs = 250),
                    ),
                ),
            )
        }
    }

    private fun Int.roundedToTens(): Int = ((this + 5) / 10) * 10

    companion object {
        const val EXTENSION_ID = "citylimit"
        const val ACTION_OPEN_SETTINGS = "open-settings"
        const val ACTION_DOWNLOAD_AREA = "download-area"

        /** Re-read signs from the cache after moving this far. */
        const val SIGN_REFRESH_DISTANCE_METERS = 750.0

        /**
         * How often the cached area around the rider is checked. Cells that are already cached or
         * backing off after an error cost nothing, so this can be frequent.
         */
        const val COVERAGE_CHECK_INTERVAL_MILLIS = 30_000L
    }
}
