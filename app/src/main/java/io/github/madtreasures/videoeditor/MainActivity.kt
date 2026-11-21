package io.github.madtreasures.videoeditor

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.madtreasures.videoeditor.ui.VideoEditorApp
import io.github.madtreasures.videoeditor.ui.videoUrisFrom

class MainActivity : ComponentActivity() {
    private val viewModel: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) viewModel.addVideos(videoUrisFrom(intent))
        setContent { VideoEditorApp(viewModel) }
    }

    /** A video opened with or shared to the app while it is already running is appended. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.addVideos(videoUrisFrom(intent))
    }
}
