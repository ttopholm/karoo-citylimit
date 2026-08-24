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
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val status by viewModel.downloadStatus.collectAsStateWithLifecycle()
    val position by viewModel.position.collectAsStateWithLifecycle()
    val testDetail = stringResource(R.string.alert_detail, settings.alertDistanceMeters)

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

        Button(
            onClick = viewModel::downloadHere,
            enabled = position != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_download_here))
        }
        OutlinedButton(
            onClick = { viewModel.showTestAlert(testDetail) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_test_alert))
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
private fun StatusCard(
    cellCount: Int,
    signCount: Int,
    newestFetchAt: Long?,
    status: DownloadStatus,
    hasPosition: Boolean,
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
