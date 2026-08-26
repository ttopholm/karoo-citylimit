package dk.gaijin.karoo.citylimit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dk.gaijin.karoo.citylimit.R
import dk.gaijin.karoo.citylimit.data.CityLimitSettings
import dk.gaijin.karoo.citylimit.data.DownloadStatus
import dk.gaijin.karoo.citylimit.data.PackStatus
import dk.gaijin.karoo.citylimit.data.RegionPack
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val status by viewModel.downloadStatus.collectAsStateWithLifecycle()
    val position by viewModel.position.collectAsStateWithLifecycle()
    val regions by viewModel.regions.collectAsStateWithLifecycle()
    val packStatus by viewModel.packStatus.collectAsStateWithLifecycle()
    val installedPacks by viewModel.installedPacks.collectAsStateWithLifecycle()
    val testDetail = stringResource(R.string.alert_detail, settings.alertDistanceMeters)
    val testNotification = stringResource(R.string.action_test_alert)
    val testAlert by viewModel.testAlert.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.settings_intro),
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        )

        StatusCard(
            cellCount = stats.cellCount,
            signCount = stats.signCount,
            newestFetchAt = stats.newestFetchAt,
            status = status,
            hasPosition = position != null,
            connected = connected,
        )

        SettingSwitch(
            title = stringResource(R.string.setting_enabled),
            subtitle = stringResource(R.string.setting_enabled_desc),
            checked = settings.enabled,
            onCheckedChange = viewModel::setEnabled,
        )
        SettingSwitch(
            title = stringResource(R.string.setting_only_recording),
            subtitle = stringResource(R.string.setting_only_recording_desc),
            checked = settings.onlyWhileRecording,
            onCheckedChange = viewModel::setOnlyWhileRecording,
        )
        SettingSwitch(
            title = stringResource(R.string.setting_beep),
            subtitle = stringResource(R.string.setting_beep_desc),
            checked = settings.beep,
            onCheckedChange = viewModel::setBeep,
        )

        HorizontalDivider()

        ChoiceRow(
            title = stringResource(R.string.setting_distance),
            subtitle = stringResource(R.string.setting_distance_desc),
            choices = CityLimitSettings.ALERT_DISTANCE_CHOICES,
            selected = settings.alertDistanceMeters,
            label = { stringResource(R.string.meters_format, it) },
            onSelected = viewModel::setAlertDistance,
        )
        ChoiceRow(
            title = stringResource(R.string.setting_duration),
            subtitle = stringResource(R.string.setting_duration_desc),
            choices = CityLimitSettings.ALERT_DURATION_CHOICES,
            selected = settings.alertDurationSeconds,
            label = { stringResource(R.string.seconds_format, it) },
            onSelected = viewModel::setAlertDuration,
        )

        HorizontalDivider()

        SettingSwitch(
            title = stringResource(R.string.setting_download_riding),
            subtitle = stringResource(R.string.setting_download_riding_desc),
            checked = settings.downloadWhileRiding,
            onCheckedChange = viewModel::setDownloadWhileRiding,
        )
        SettingSwitch(
            title = stringResource(R.string.setting_unknown_direction),
            subtitle = stringResource(R.string.setting_unknown_direction_desc),
            checked = settings.alertWhenDirectionUnknown,
            onCheckedChange = viewModel::setAlertWhenDirectionUnknown,
        )

        RegionSection(
            regions = regions,
            installed = installedPacks,
            status = packStatus,
            onInstall = viewModel::installRegion,
            onRetry = viewModel::loadRegions,
        )

        Button(
            onClick = viewModel::downloadHere,
            enabled = position != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_download_here))
        }
        OutlinedButton(
            onClick = { viewModel.showTestAlert(testDetail, testNotification) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_test_alert))
        }
        testAlert?.let { outcome ->
            Text(
                text = when (outcome) {
                    SettingsViewModel.TestAlert.SENT -> stringResource(R.string.test_alert_sent)
                    SettingsViewModel.TestAlert.NOT_CONNECTED -> stringResource(R.string.test_alert_not_connected)
                },
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedButton(
            onClick = viewModel::clearCache,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_clear_cache))
        }

        Text(
            text = stringResource(R.string.attribution),
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        )
    }
}

@Composable
private fun RegionSection(
    regions: List<RegionPack>,
    installed: Map<String, String>,
    status: PackStatus,
    onInstall: (RegionPack) -> Unit,
    onRetry: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider()
        Text(
            text = stringResource(R.string.regions_title),
            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = stringResource(R.string.regions_desc),
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        )

        val busy = status is PackStatus.Downloading || status is PackStatus.LoadingCatalog
        regions.forEach { region ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val installedAt = installed[region.id]
                val outdated = installedAt != null && region.generatedAt != null && installedAt != region.generatedAt
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = region.name, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                    Text(
                        text = stringResource(R.string.regions_size, region.signs, region.bytes / 1024),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = when {
                            outdated -> stringResource(R.string.regions_update, shortDate(region.generatedAt))
                            installedAt != null -> stringResource(R.string.regions_installed_at, shortDate(installedAt))
                            else -> stringResource(R.string.regions_not_installed)
                        },
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                }
                Button(onClick = { onInstall(region) }, enabled = !busy) {
                    Text(
                        stringResource(
                            if (installedAt == null) R.string.regions_download else R.string.regions_refresh,
                        ),
                    )
                }
            }
        }

        Text(
            text = when (status) {
                PackStatus.Idle ->
                    if (regions.isEmpty()) stringResource(R.string.regions_none) else ""
                PackStatus.LoadingCatalog -> stringResource(R.string.regions_loading)
                is PackStatus.Downloading ->
                    stringResource(R.string.regions_progress, status.region, status.completed, status.total)
                is PackStatus.Installed ->
                    stringResource(R.string.regions_installed, status.region, status.signs)
                is PackStatus.Failed -> stringResource(R.string.regions_failed, status.message)
            },
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (regions.isEmpty() && status !is PackStatus.LoadingCatalog) {
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.regions_retry))
            }
        }
    }
}

@Composable
private fun StatusCard(
    cellCount: Int,
    signCount: Int,
    newestFetchAt: Long?,
    status: DownloadStatus,
    hasPosition: Boolean,
    connected: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.status_cache, signCount, cellCount),
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = if (connected) {
                    stringResource(R.string.status_karoo_connected)
                } else {
                    stringResource(R.string.status_karoo_disconnected)
                },
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )
            newestFetchAt?.let {
                Text(
                    text = stringResource(R.string.status_updated, formatTimestamp(it)),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = when (status) {
                    DownloadStatus.Idle ->
                        if (hasPosition) {
                            stringResource(R.string.status_idle)
                        } else {
                            stringResource(R.string.status_no_position)
                        }
                    is DownloadStatus.Downloading ->
                        stringResource(R.string.status_downloading, status.completed + 1, status.total)
                    is DownloadStatus.Done ->
                        stringResource(R.string.status_done, status.signs, status.cells)
                    is DownloadStatus.Failed ->
                        stringResource(R.string.status_failed, status.message)
                },
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
            Text(text = subtitle, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun <T> ChoiceRow(
    title: String,
    subtitle: String,
    choices: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = title, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
        Text(text = subtitle, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            choices.forEach { choice ->
                FilterChip(
                    selected = choice == selected,
                    onClick = { onSelected(choice) },
                    label = { Text(label(choice)) },
                )
            }
        }
    }
}

private fun formatTimestamp(millis: Long): String =
    DateFormat.getDateInstance(DateFormat.SHORT).format(Date(millis))

/** Packs report their build time as an ISO timestamp; the date is all the screen needs. */
private fun shortDate(isoTimestamp: String?): String =
    isoTimestamp?.substringBefore('T').orEmpty()
