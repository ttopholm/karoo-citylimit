package dk.gaijin.karoo.citylimit.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dk.gaijin.karoo.citylimit.core.DetectorConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.io.IOException

/**
 * User configurable behaviour of the extension.
 */
data class CityLimitSettings(
    /** Master switch. */
    val enabled: Boolean = true,
    /** How far ahead of the sign the alert is shown. */
    val alertDistanceMeters: Int = 200,
    /** Play the beeper along with the alert. */
    val beep: Boolean = true,
    /** How long the alert stays on screen. */
    val alertDurationSeconds: Int = 8,
    /** Only alert while a ride is being recorded. */
    val onlyWhileRecording: Boolean = true,
    /**
     * Alert for boundary signs where the direction into town could not be worked out. Off by
     * default, because those signs could just as well be the crossed-out sign on the way out.
     */
    val alertWhenDirectionUnknown: Boolean = false,
    /** Download sign data for the area around the rider while riding. */
    val downloadWhileRiding: Boolean = true,
    /** Overpass API endpoint used to look up signs. */
    val overpassUrl: String = DEFAULT_OVERPASS_URL,
) {
    fun toDetectorConfig(): DetectorConfig = DetectorConfig(
        alertDistanceMeters = alertDistanceMeters.toDouble(),
        alertWhenDirectionUnknown = alertWhenDirectionUnknown,
    )

    companion object {
        const val DEFAULT_OVERPASS_URL = "https://overpass-api.de/api/interpreter"
        val ALERT_DISTANCE_CHOICES = listOf(100, 150, 200, 300, 500)
        val ALERT_DURATION_CHOICES = listOf(4, 6, 8, 12)
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "citylimit-settings")

/**
 * Reads and writes [CityLimitSettings]; shared by the settings screen and the extension service.
 */
class SettingsStore(context: Context) {
    private val dataStore = context.applicationContext.dataStore

    val settings: Flow<CityLimitSettings> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) {
                Timber.w(throwable, "Failed to read settings, falling back to defaults")
                emit(emptyPreferences())
            } else {
                throw throwable
            }
        }
        .map { preferences ->
            val defaults = CityLimitSettings()
            CityLimitSettings(
                enabled = preferences[KEY_ENABLED] ?: defaults.enabled,
                alertDistanceMeters = preferences[KEY_DISTANCE] ?: defaults.alertDistanceMeters,
                beep = preferences[KEY_BEEP] ?: defaults.beep,
                alertDurationSeconds = preferences[KEY_DURATION] ?: defaults.alertDurationSeconds,
                onlyWhileRecording = preferences[KEY_ONLY_RECORDING] ?: defaults.onlyWhileRecording,
                alertWhenDirectionUnknown = preferences[KEY_UNKNOWN_DIRECTION] ?: defaults.alertWhenDirectionUnknown,
                downloadWhileRiding = preferences[KEY_DOWNLOAD_RIDING] ?: defaults.downloadWhileRiding,
                overpassUrl = preferences[KEY_OVERPASS_URL]?.ifBlank { null } ?: defaults.overpassUrl,
            )
        }

    suspend fun setEnabled(value: Boolean) = edit { it[KEY_ENABLED] = value }

    suspend fun setAlertDistance(meters: Int) = edit { it[KEY_DISTANCE] = meters }

    suspend fun setBeep(value: Boolean) = edit { it[KEY_BEEP] = value }

    suspend fun setAlertDuration(seconds: Int) = edit { it[KEY_DURATION] = seconds }

    suspend fun setOnlyWhileRecording(value: Boolean) = edit { it[KEY_ONLY_RECORDING] = value }

    suspend fun setAlertWhenDirectionUnknown(value: Boolean) = edit { it[KEY_UNKNOWN_DIRECTION] = value }

    suspend fun setDownloadWhileRiding(value: Boolean) = edit { it[KEY_DOWNLOAD_RIDING] = value }

    suspend fun setOverpassUrl(url: String) = edit { it[KEY_OVERPASS_URL] = url.trim() }

    /**
     * When each region pack was built, as the pack itself reported it. Lets the screen say which
     * regions are installed and which have a newer build waiting.
     */
    val installedPacks: Flow<Map<String, String>> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { preferences ->
            preferences[KEY_INSTALLED_PACKS]
                ?.split('\n')
                ?.mapNotNull { line ->
                    val id = line.substringBefore('=', "")
                    val generatedAt = line.substringAfter('=', "")
                    if (id.isEmpty() || generatedAt.isEmpty()) null else id to generatedAt
                }
                ?.toMap()
                .orEmpty()
        }

    suspend fun recordInstalledPack(id: String, generatedAt: String) {
        dataStore.edit { preferences ->
            val current = preferences[KEY_INSTALLED_PACKS]
                ?.split('\n')
                ?.filter { it.isNotEmpty() && it.substringBefore('=') != id }
                .orEmpty()
            preferences[KEY_INSTALLED_PACKS] = (current + "$id=$generatedAt").joinToString("\n")
        }
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("enabled")
        val KEY_DISTANCE = intPreferencesKey("alert_distance_meters")
        val KEY_BEEP = booleanPreferencesKey("beep")
        val KEY_DURATION = intPreferencesKey("alert_duration_seconds")
        val KEY_ONLY_RECORDING = booleanPreferencesKey("only_while_recording")
        val KEY_UNKNOWN_DIRECTION = booleanPreferencesKey("alert_unknown_direction")
        val KEY_DOWNLOAD_RIDING = booleanPreferencesKey("download_while_riding")
        val KEY_OVERPASS_URL = stringPreferencesKey("overpass_url")
        val KEY_INSTALLED_PACKS = stringPreferencesKey("installed_packs")
    }
}
