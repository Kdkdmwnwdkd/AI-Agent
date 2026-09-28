package com.ai.assistance.operit.ui.features.chat.components.attachments

import android.net.Uri
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaItem
import androidx.media3.ui.PlayerView

@Composable
fun AudioAttachmentPlayer(
    uri: Uri,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = false
) {
    val context = LocalContext.current

    val player = remember(uri) {
        ExoPlayer.Builder(context).build()
    }

    LaunchedEffect(uri) {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = autoPlay
    }

    DisposableEffect(player) {
        onDispose {
            try {
                player.stop()
                player.clearMediaItems()
                player.release()
            } catch (_: Exception) {
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
            }
        },
        update = { view ->
            if (view.player !== player) {
                view.player = player
            }
        },
        modifier = modifier
    )
}
