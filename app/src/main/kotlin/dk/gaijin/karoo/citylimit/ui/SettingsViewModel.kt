package dk.gaijin.karoo.citylimit.ui

import dk.gaijin.karoo.citylimit.R
import dk.gaijin.karoo.citylimit.core.LatLng
import dk.gaijin.karoo.citylimit.data.CacheStats
import dk.gaijin.karoo.citylimit.data.CityLimitSettings
import dk.gaijin.karoo.citylimit.data.DownloadStatus
import dk.gaijin.karoo.citylimit.data.SettingsStore
import dk.gaijin.karoo.citylimit.data.SignRepository
import dk.gaijin.karoo.citylimit.extension.consumerFlow
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.OnLocationChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * State holder behind [SettingsScreen].
 */
class SettingsViewModel(
    private val settingsStore: SettingsStore,
    private val repository: SignRepository,
    private val karooSystem: KarooSystemService,
    private val scope: CoroutineScope,
) {
    val settings: StateFlow<CityLimitSettings> =
        settingsStore.settings.stateIn(scope, SharingStarted.Eagerly, CityLimitSettings())

    val downloadStatus: StateFlow<DownloadStatus> = repository.status

    private val _stats = MutableStateFlow(CacheStats(0, 0, null))
    val stats: StateFlow<CacheStats> = _stats.asStateFlow()

    private val _position = MutableStateFlow<LatLng?>(null)
    val position: StateFlow<LatLng?> = _position.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var locationJob: Job? = null

    fun onKarooConnected(connected: Boolean) {
        _connected.value = connected
        if (connected && locationJob?.isActive != true) {
            locationJob = scope.launch {
                karooSystem.consumerFlow<OnLocationChanged>().collect { event ->
                    _position.value = LatLng(event.lat, event.lng)
                }
            }
        }
    }

    suspend fun refreshStats() {
        _stats.value = repository.stats()
    }

    fun setEnabled(value: Boolean) = scope.launch { settingsStore.setEnabled(value) }

    fun setAlertDistance(meters: Int) = scope.launch { settingsStore.setAlertDistance(meters) }

    fun setAlertDuration(seconds: Int) = scope.launch { settingsStore.setAlertDuration(seconds) }

    fun setBeep(value: Boolean) = scope.launch { settingsStore.setBeep(value) }

    fun setOnlyWhileRecording(value: Boolean) = scope.launch { settingsStore.setOnlyWhileRecording(value) }

    fun setAlertWhenDirectionUnknown(value: Boolean) = scope.launch { settingsStore.setAlertWhenDirectionUnknown(value) }

    fun setDownloadWhileRiding(value: Boolean) = scope.launch { settingsStore.setDownloadWhileRiding(value) }

    /** Download signs for the area around the current position. */
    fun downloadHere() {
        val position = _position.value ?: return
        scope.launch {
            repository.ensureCoverage(position, settings.value.overpassUrl)
            refreshStats()
        }
    }

    fun clearCache() {
        scope.launch {
            repository.clearCache()
            refreshStats()
        }
    }

    /** Show what an alert looks like, so the rider can check colours and duration. */
    fun showTestAlert(detail: String) {
        karooSystem.dispatch(
            InRideAlert(
                id = "citylimit-test",
                icon = R.drawable.ic_city_limit,
                title = "Nørre Herlev",
                detail = detail,
                autoDismissMs = settings.value.alertDurationSeconds * 1_000L,
                backgroundColor = R.color.alert_background,
                textColor = R.color.alert_text,
            ),
        )
    }
}
