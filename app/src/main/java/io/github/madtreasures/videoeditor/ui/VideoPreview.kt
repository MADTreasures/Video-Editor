package io.github.madtreasures.videoeditor.ui

import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player

/**
 * Shows the player's picture the way the exported video will look: inside a black frame with
 * the output's aspect ratio, turned by the project's rotation.
 *
 * @param canvasAspect width / height of the output frame, rotation already applied.
 * @param clipAspect width / height of the clip that is playing, before rotation.
 * @param rotation clockwise degrees: 0, 90, 180 or 270.
 */
@Composable
fun VideoPreview(
    player: Player,
    canvasAspect: Float,
    clipAspect: Float,
    rotation: Int,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        if (maxWidth <= 0.dp || maxHeight <= 0.dp || canvasAspect <= 0f || clipAspect <= 0f) {
            return@BoxWithConstraints
        }
        // Fit the output frame into the space there is.
        val canvasWidth: Dp
        val canvasHeight: Dp
        if (maxWidth / maxHeight > canvasAspect) {
            canvasHeight = maxHeight
            canvasWidth = maxHeight * canvasAspect
        } else {
            canvasWidth = maxWidth
            canvasHeight = maxWidth / canvasAspect
        }
        // The box that gets rotated has the frame's size from before the rotation.
        val sideways = rotation % 180 != 0
        val frameWidth = if (sideways) canvasHeight else canvasWidth
        val frameHeight = if (sideways) canvasWidth else canvasHeight
        Box(
            Modifier
                .requiredSize(frameWidth, frameHeight)
                .rotate(rotation.toFloat())
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            // A TextureView, unlike a SurfaceView, can be rotated together with its container.
            AndroidView(
                factory = { context -> TextureView(context).also(player::setVideoTextureView) },
                onRelease = { view -> player.clearVideoTextureView(view) },
                modifier = Modifier.aspectRatio(
                    clipAspect,
                    matchHeightConstraintsFirst = clipAspect < frameWidth / frameHeight,
                ),
            )
        }
    }
}
