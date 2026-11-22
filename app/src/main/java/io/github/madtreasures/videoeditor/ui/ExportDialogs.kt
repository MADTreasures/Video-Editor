package io.github.madtreasures.videoeditor.ui

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.madtreasures.videoeditor.R
import io.github.madtreasures.videoeditor.export.ExportState
import io.github.madtreasures.videoeditor.export.encodableCodecs
import io.github.madtreasures.videoeditor.model.Container
import io.github.madtreasures.videoeditor.model.ExportSettings
import io.github.madtreasures.videoeditor.model.Project
import io.github.madtreasures.videoeditor.model.Quality
import io.github.madtreasures.videoeditor.model.Resolution
import io.github.madtreasures.videoeditor.model.VideoCodec
import io.github.madtreasures.videoeditor.model.planExport
import kotlin.math.min

/** The codecs offered for [container]: those this device can encode, plus the source's own if it can be copied. */
private fun offeredCodecs(container: Container, sourceMime: String): List<VideoCodec> =
    container.videoCodecs.filter { it.mime in encodableCodecs || it.mime == sourceMime }

/** The source's own codec if the file type takes it (so it can be copied), else the first offered. */
private fun defaultCodec(container: Container, sourceMime: String): VideoCodec {
    val offered = offeredCodecs(container, sourceMime).ifEmpty { container.videoCodecs }
    return offered.firstOrNull { it.mime == sourceMime } ?: offered.firstOrNull() ?: VideoCodec.H264
}

@StringRes
private fun describe(container: Container): Int = when (container) {
    Container.MP4 -> R.string.format_mp4
    Container.MOV -> R.string.format_mov
    Container.MKV -> R.string.format_mkv
    Container.WEBM -> R.string.format_webm
    Container.THREE_GP -> R.string.format_3gp
    Container.M4A -> R.string.format_m4a
    Container.OGG -> R.string.format_ogg
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExportSettingsDialog(
    project: Project,
    initialContainer: Container,
    onExport: (ExportSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val sourceMime = project.clips.firstOrNull()?.source?.videoMime ?: ""
    var settings by remember {
        mutableStateOf(ExportSettings(initialContainer, defaultCodec(initialContainer, sourceMime)))
    }
    val plan = remember(project, settings) { planExport(project, settings, encodableCodecs) }
    val context = LocalContext.current
    val container = settings.container

    // Only offer sizes that are smaller than the video; it is never scaled up.
    val originalShortSide = remember(project) {
        planExport(project, ExportSettings()).let { min(it.width, it.height) }
    }
    val resolutions = Resolution.entries.filter { it.shortSide == null || it.shortSide < originalShortSide }
    val codecs = offeredCodecs(container, sourceMime)

    fun choose(next: Container) {
        settings = settings.copy(container = next, videoCodec = defaultCodec(next, sourceMime))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SectionLabel(stringResource(R.string.export_format))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (option in Container.entries.filter { !it.audioOnly }) {
                        FilterChip(
                            selected = container == option,
                            onClick = { choose(option) },
                            label = { Text(option.label) },
                        )
                    }
                }
                Text(
                    stringResource(R.string.export_audio_only),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (option in Container.entries.filter { it.audioOnly }) {
                        FilterChip(
                            selected = container == option,
                            onClick = { choose(option) },
                            label = { Text(option.label) },
                        )
                    }
                }
                Text(
                    stringResource(describe(container)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(12.dp))

                if (!container.audioOnly && codecs.size > 1) {
                    SectionLabel(stringResource(R.string.export_codec))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (codec in codecs) {
                            FilterChip(
                                selected = plan.videoCodec == codec,
                                onClick = { settings = settings.copy(videoCodec = codec) },
                                label = { Text(codec.label) },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                if (!container.audioOnly && resolutions.size > 1) {
                    SectionLabel(stringResource(R.string.export_resolution))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (resolution in resolutions) {
                            FilterChip(
                                selected = settings.resolution == resolution,
                                onClick = { settings = settings.copy(resolution = resolution) },
                                label = {
                                    Text(
                                        resolution.shortSide?.let { "${it}p" }
                                            ?: stringResource(R.string.export_original),
                                    )
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                SectionLabel(stringResource(R.string.export_quality))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (quality in Quality.entries) {
                        FilterChip(
                            selected = settings.quality == quality,
                            onClick = { settings = settings.copy(quality = quality) },
                            label = {
                                Text(
                                    stringResource(
                                        when (quality) {
                                            Quality.HIGH -> R.string.quality_high
                                            Quality.MEDIUM -> R.string.quality_medium
                                            Quality.LOW -> R.string.quality_low
                                        },
                                    ),
                                )
                            },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

                val size = Formatter.formatShortFileSize(context, plan.estimatedBytes)
                Text(
                    when {
                        !plan.canExport -> stringResource(R.string.export_no_sound)
                        plan.passthrough && (plan.audioCopied || !plan.hasAudio) ->
                            stringResource(R.string.export_passthrough)
                        plan.passthrough && !plan.hasVideo -> stringResource(R.string.export_passthrough)
                        plan.passthrough -> stringResource(R.string.export_passthrough_picture)
                        !plan.hasVideo -> stringResource(R.string.export_estimate_audio, plan.audioBitrate / 1000, size)
                        else -> stringResource(R.string.export_estimate, plan.width, plan.height, size)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (plan.canExport) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
                if (plan.audioDropped && plan.canExport) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.export_audio_dropped, container.label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onExport(settings.copy(videoCodec = plan.videoCodec ?: settings.videoCodec)) }, enabled = plan.canExport) {
                Text(stringResource(R.string.action_export))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

/** Progress, result or error of the running export; nothing while no export is active. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExportStatusDialog(
    state: ExportState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onShare: (ExportState.Done) -> Unit,
    onOpen: (ExportState.Done) -> Unit,
) {
    when (state) {
        ExportState.Idle -> Unit

        is ExportState.Running, ExportState.Saving -> {
            val progress = (state as? ExportState.Running)?.progress
            val saving = state is ExportState.Saving
            AlertDialog(
                onDismissRequest = {},
                properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
                title = {
                    Text(stringResource(if (saving) R.string.exporting_saving else R.string.exporting_title))
                },
                text = {
                    Column {
                        if (progress != null && !saving) {
                            LinearProgressIndicator(
                                progress = { progress / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("$progress %", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.exporting_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    if (!saving) {
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                    }
                },
            )
        }

        is ExportState.Done -> {
            val context = LocalContext.current
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.done_title)) },
                text = {
                    Column {
                        Text(state.location)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            Formatter.formatShortFileSize(context, state.sizeBytes),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                        TextButton(onClick = { onShare(state) }) { Text(stringResource(R.string.action_share)) }
                        TextButton(onClick = { onOpen(state) }) { Text(stringResource(R.string.action_open)) }
                        Button(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
                    }
                },
            )
        }

        is ExportState.Failed -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.failed_title)) },
                text = {
                    Column {
                        Text(stringResource(R.string.failed_text))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            state.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
                },
            )
        }
    }
}
