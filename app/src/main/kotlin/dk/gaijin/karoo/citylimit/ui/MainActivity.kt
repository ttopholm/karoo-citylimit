package dk.gaijin.karoo.citylimit.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import dk.gaijin.karoo.citylimit.data.KarooHttp
import dk.gaijin.karoo.citylimit.data.SettingsStore
import dk.gaijin.karoo.citylimit.data.SignCache
import dk.gaijin.karoo.citylimit.data.SignRepository
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * Settings and status screen for the extension.
 */
class MainActivity : ComponentActivity() {
    private lateinit var karooSystem: KarooSystemService
    private lateinit var viewModel: SettingsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        karooSystem = KarooSystemService(applicationContext)
        val repository = SignRepository(
            cache = SignCache(File(applicationContext.filesDir, SignCache.FILE_NAME)),
            http = KarooHttp(karooSystem),
        )
        viewModel = SettingsViewModel(
            settingsStore = SettingsStore(applicationContext),
            repository = repository,
            karooSystem = karooSystem,
            scope = lifecycleScope,
        )
        setContent {
            CityLimitTheme {
                SettingsScreen(viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        karooSystem.connect { connected ->
            Timber.i("Karoo system connected: %s", connected)
            viewModel.onKarooConnected(connected)
        }
        lifecycleScope.launch { viewModel.refreshStats() }
    }

    override fun onStop() {
        karooSystem.disconnect()
        super.onStop()
    }
}
