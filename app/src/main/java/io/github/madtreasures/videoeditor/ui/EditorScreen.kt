package io.github.madtreasures.videoeditor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.madtreasures.videoeditor.EditorViewModel
import io.github.madtreasures.videoeditor.R

private val Speeds = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)

private val ToolsPadding = 8.dp
private val ToolSpacing = 2.dp
private val MinToolWidth = 64.dp
private val MaxToolWidth = 96.dp

@Composable
fun EditorScreen(
    vm: EditorViewModel,
    onPickGallery: () -> Unit,
    onPickFiles: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showClose by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }

    BackHandler { showClose = true }

    // Move the playhead with the video, once per display frame.
    LaunchedEffect(vm.isPlaying) {
        if (vm.isPlaying) {
            while (true) {
                withFrameNanos { }
                vm.onFrame()
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.pause() }

    val project = vm.project
    val currentClip = project.clips.getOrNull(vm.currentClipIndex)

    val preview: @Composable (Modifier) -> Unit = { previewModifier ->
        VideoPreview(
            player = vm.player,
            canvasAspect = project.canvasAspectRatio,
            clipAspect = currentClip?.source?.aspectRatio ?: project.canvasAspectRatio,
            rotation = project.rotation,
            modifier = previewModifier
                .padding(8.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = stringResource(if (vm.isPlaying) R.string.action_pause else R.string.action_play),
                    onClick = vm::togglePlay,
                ),
        )
    }
    val topBar: @Composable () -> Unit = {
        TopBar(
            canUndo = vm.canUndo,
            canRedo = vm.canRedo,
            onClose = { showClose = true },
            onUndo = vm::undo,
            onRedo = vm::redo,
            onExport = {
                vm.pause()
                onExport()
            },
        )
    }
    val controls: @Composable () -> Unit = {
        TransportRow(
            positionMs = { vm.positionMs },
            durationMs = project.durationMs,
            isPlaying = vm.isPlaying,
            onTogglePlay = vm::togglePlay,
            onZoomIn = { vm.zoomBy(1.6f) },
            onZoomOut = { vm.zoomBy(1 / 1.6f) },
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val widthDp = maxWidth.value
            LaunchedEffect(widthDp, project.isEmpty) { vm.initZoom(widthDp) }
            Timeline(
                project = project,
                currentIndex = vm.currentClipIndex,
                positionMs = { vm.positionMs },
                dpPerSecond = vm.zoom ?: 1f,
                thumbnails = vm.thumbnails,
                onZoom = vm::zoomBy,
                onScrub = vm::scrubTo,
                onScrubEnd = vm::scrubEnd,
                onTrimStart = vm::trimStart,
                onTrim = vm::trimTo,
                onTrimEnd = vm::trimEnd,
            )
        }
        ToolsRow(
            vm = vm,
            onSpeed = { showSpeed = true },
            onPickGallery = onPickGallery,
            onPickFiles = onPickFiles,
        )
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // A phone on its side has too little height to stack everything.
        val sideBySide = maxWidth > maxHeight && maxHeight < 500.dp
        if (sideBySide) {
            Row(Modifier.fillMaxSize()) {
                preview(Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    topBar()
                    Spacer(Modifier.weight(1f))
                    controls()
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                topBar()
                preview(Modifier.weight(1f).fillMaxWidth())
                controls()
            }
        }
    }

    if (showSpeed) {
        SpeedSheet(
            current = project.speed,
            onSelect = {
                vm.setSpeed(it)
                showSpeed = false
            },
            onDismiss = { showSpeed = false },
        )
    }

    if (showClose) {
        AlertDialog(
            onDismissRequest = { showClose = false },
            title = { Text(stringResource(R.string.close_title)) },
            text = { Text(stringResource(R.string.close_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showClose = false
                    vm.closeProject()
                }) { Text(stringResource(R.string.close_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showClose = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun TopBar(
    canUndo: Boolean,
    canRedo: Boolean,
    onClose: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onExport: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_close))
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onUndo, enabled = canUndo) {
            Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = stringResource(R.string.action_undo))
        }
        IconButton(onClick = onRedo, enabled = canRedo) {
            Icon(Icons.AutoMirrored.Rounded.Redo, contentDescription = stringResource(R.string.action_redo))
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = onExport, modifier = Modifier.padding(end = 8.dp)) {
            Text(stringResource(R.string.action_export))
        }
    }
}

@Composable
private fun TransportRow(
    positionMs: () -> Long,
    durationMs: Long,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            // Reading the position here keeps the per-frame updates local to this text.
            text = formatTime(positionMs()) + " / " + formatTime(durationMs),
            style = TextStyle(fontSize = 13.sp, fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
        FilledIconButton(onClick = onTogglePlay, modifier = Modifier.size(48.dp)) {
            Icon(
                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = stringResource(if (isPlaying) R.string.action_pause else R.string.action_play),
                modifier = Modifier.size(28.dp),
            )
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onZoomOut) {
                Icon(Icons.Rounded.ZoomOut, contentDescription = stringResource(R.string.action_zoom_out))
            }
            IconButton(onClick = onZoomIn) {
                Icon(Icons.Rounded.ZoomIn, contentDescription = stringResource(R.string.action_zoom_in))
            }
        }
    }
}

@Composable
private fun ToolsRow(
    vm: EditorViewModel,
    onSpeed: () -> Unit,
    onPickGallery: () -> Unit,
    onPickFiles: () -> Unit,
) {
    val project = vm.project
    val index = vm.currentClipIndex
    val canReorder = project.clips.size > 1
    var showAdd by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Share the width if every tool fits. Otherwise size the tools so that the last one in
        // view is cut in half, which shows that the row can be scrolled.
        val count = if (canReorder) 8 else 6
        val fitting = (maxWidth - ToolsPadding * 2 - ToolSpacing * (count - 1)) / count
        val toolWidth = if (fitting >= MinToolWidth) {
            minOf(fitting, MaxToolWidth)
        } else {
            (maxWidth - ToolsPadding) / 5.5f - ToolSpacing
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = ToolsPadding, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(ToolSpacing, Alignment.CenterHorizontally),
        ) {
            ToolButton(
                Icons.Rounded.ContentCut, stringResource(R.string.tool_split), toolWidth,
                enabled = vm.canSplit, onClick = vm::split,
            )
            ToolButton(
                Icons.Rounded.DeleteOutline, stringResource(R.string.tool_delete), toolWidth,
                enabled = canReorder, onClick = vm::deleteCurrentClip,
            )
            ToolButton(
                Icons.Rounded.Speed,
                if (project.speed == 1f) stringResource(R.string.tool_speed) else formatSpeed(project.speed),
                toolWidth,
                highlighted = project.speed != 1f, onClick = onSpeed,
            )
            ToolButton(
                if (project.muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                stringResource(if (project.muted) R.string.tool_sound_off else R.string.tool_sound_on),
                toolWidth,
                highlighted = project.muted, onClick = vm::toggleMute,
            )
            ToolButton(
                Icons.Rounded.Rotate90DegreesCw, stringResource(R.string.tool_rotate), toolWidth,
                highlighted = project.rotation != 0, onClick = vm::rotate,
            )
            Box {
                ToolButton(
                    Icons.Rounded.Add, stringResource(R.string.tool_add), toolWidth,
                    onClick = { showAdd = true },
                )
                DropdownMenu(expanded = showAdd, onDismissRequest = { showAdd = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.add_from_gallery)) },
                        onClick = {
                            showAdd = false
                            vm.pause()
                            onPickGallery()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.add_from_files)) },
                        onClick = {
                            showAdd = false
                            vm.pause()
                            onPickFiles()
                        },
                    )
                }
            }
            if (canReorder) {
                ToolButton(
                    Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.tool_move_left), toolWidth,
                    enabled = index > 0, onClick = { vm.moveCurrentClip(-1) },
                )
                ToolButton(
                    Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.tool_move_right), toolWidth,
                    enabled = index < project.clips.lastIndex, onClick = { vm.moveCurrentClip(1) },
                )
            }
        }
    }
}

@Composable
private fun ToolButton(
    icon: ImageVector,
    label: String,
    width: Dp,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f)
        highlighted -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        Modifier
            .width(width)
            .height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides color) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(4.dp))
            // Shrinks a little before it would cut off a long label such as "Hinzufügen".
            Text(
                label,
                autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 11.sp, stepSize = 0.5.sp),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SpeedSheet(current: Float, onSelect: (Float) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text(stringResource(R.string.speed_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (speed in Speeds) {
                    FilterChip(
                        selected = speed == current,
                        onClick = { onSelect(speed) },
                        label = { Text(formatSpeed(speed)) },
                    )
                }
            }
        }
    }
}
