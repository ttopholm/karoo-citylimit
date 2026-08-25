package dk.gaijin.karoo.citylimit.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import dk.gaijin.karoo.citylimit.data.DirectNetworkClient
import dk.gaijin.karoo.citylimit.data.FallbackNetworkClient
import dk.gaijin.karoo.citylimit.data.KarooHttp
import dk.gaijin.karoo.citylimit.data.KarooNetworkClient
import dk.gaijin.karoo.citylimit.data.PackRepository
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
        val cache = SignCache(File(applicationContext.filesDir, SignCache.FILE_NAME))
        val karooHttp = KarooHttp(karooSystem)
        val repository = SignRepository(cache = cache, http = karooHttp)
        val packs = PackRepository(
            cache = cache,
            // Over Wi-Fi the plain connection is quicker and has no size limit; the Karoo system's
            // relay through the phone stands in when the device has no network of its own.
            http = FallbackNetworkClient(DirectNetworkClient(), KarooNetworkClient(karooHttp)),
        )
        viewModel = SettingsViewModel(
            settingsStore = SettingsStore(applicationContext),
            repository = repository,
            packs = packs,
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
        viewModel.loadRegions()
    }

    override fun onStop() {
        karooSystem.disconnect()
        super.onStop()
    }
}
