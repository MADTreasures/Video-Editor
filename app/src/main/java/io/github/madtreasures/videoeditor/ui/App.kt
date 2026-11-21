package io.github.madtreasures.videoeditor.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.madtreasures.videoeditor.EditorViewModel
import io.github.madtreasures.videoeditor.R
import io.github.madtreasures.videoeditor.export.ExportState
import io.github.madtreasures.videoeditor.model.ExportSettings
import kotlinx.coroutines.launch

private const val MAX_PICKED_VIDEOS = 20

@Composable
fun VideoEditorApp(vm: EditorViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showExportSettings by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<ExportSettings?>(null) }
    val exportState by vm.exportState.collectAsStateWithLifecycle()

    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PICKED_VIDEOS),
    ) { uris -> vm.addVideos(uris) }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> vm.addVideos(uris) }
    val pickGallery = {
        galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    }
    val pickFiles = { filePicker.launch(arrayOf("video/*")) }

    // Android 9 and older need a permission to save into the shared Movies folder.
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val settings = pendingExport
        pendingExport = null
        if (granted && settings != null) {
            vm.startExport(settings)
        } else if (!granted) {
            scope.launch { snackbar.showSnackbar(context.getString(R.string.msg_storage_permission)) }
        }
    }

    LaunchedEffect(vm) {
        vm.messages.collect { message ->
            val text = if (message.arg != null) {
                context.getString(message.text, message.arg)
            } else {
                context.getString(message.text)
            }
            launch { snackbar.showSnackbar(text) }
        }
    }

    VideoEditorTheme {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (vm.project.isEmpty) {
                StartScreen(onPickGallery = pickGallery, onPickFiles = pickFiles, enabled = !vm.isLoading)
            } else {
                EditorScreen(
                    vm = vm,
                    onPickGallery = pickGallery,
                    onPickFiles = pickFiles,
                    onExport = { showExportSettings = true },
                )
            }

            if (vm.isLoading) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.loading), color = MaterialTheme.colorScheme.onBackground)
                    }
                }
            }

            SnackbarHost(
                snackbar,
                Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(bottom = 72.dp),
            )
        }

        if (showExportSettings && !vm.project.isEmpty && exportState == ExportState.Idle) {
            ExportSettingsDialog(
                project = vm.project,
                onDismiss = { showExportSettings = false },
                onExport = { settings ->
                    showExportSettings = false
                    val needsPermission = Build.VERSION.SDK_INT < 29 &&
                        ContextCompat.checkSelfPermission(
                            context, Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        ) != PackageManager.PERMISSION_GRANTED
                    if (needsPermission) {
                        pendingExport = settings
                        storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else {
                        vm.startExport(settings)
                    }
                },
            )
        }

        KeepScreenOn(
            vm.isPlaying || exportState is ExportState.Running || exportState is ExportState.Saving,
        )
        ExportStatusDialog(
            state = exportState,
            onCancel = vm::cancelExport,
            onDismiss = vm::dismissExport,
            onShare = { done ->
                val send = Intent(Intent.ACTION_SEND)
                    .setType("video/mp4")
                    .putExtra(Intent.EXTRA_STREAM, done.uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.launch(Intent.createChooser(send, null)) {
                    scope.launch { snackbar.showSnackbar(context.getString(R.string.msg_no_app)) }
                }
            },
            onOpen = { done ->
                val view = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(done.uri, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.launch(view) {
                    scope.launch { snackbar.showSnackbar(context.getString(R.string.msg_no_app)) }
                }
            },
        )
    }
}

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

private inline fun Context.launch(intent: Intent, onMissing: () -> Unit) {
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        onMissing()
    }
}

/** The videos an "open with" or "share" intent points at. */
fun videoUrisFrom(intent: Intent?): List<Uri> {
    intent ?: return emptyList()
    return when (intent.action) {
        Intent.ACTION_VIEW -> listOfNotNull(intent.data)
        Intent.ACTION_SEND -> listOfNotNull(intent.streamExtra())
        Intent.ACTION_SEND_MULTIPLE -> intent.streamExtras()
        else -> emptyList()
    }
}

@Suppress("DEPRECATION")
private fun Intent.streamExtra(): Uri? =
    if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        getParcelableExtra(Intent.EXTRA_STREAM)
    }

@Suppress("DEPRECATION")
private fun Intent.streamExtras(): List<Uri> =
    if (Build.VERSION.SDK_INT >= 33) {
        getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        getParcelableArrayListExtra(Intent.EXTRA_STREAM)
    }.orEmpty()
