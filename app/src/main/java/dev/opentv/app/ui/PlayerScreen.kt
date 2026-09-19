package dev.opentv.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import dev.opentv.app.R
import dev.opentv.app.data.Channel
import dev.opentv.app.data.DEFAULT_USER_AGENT
import kotlinx.coroutines.delay

private enum class Status { Loading, Playing, Failed }

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    channels: List<Channel>,
    position: Int,
    onPositionChange: (Int) -> Unit,
    onChannelStarted: (Channel) -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val channel = channels[position.coerceIn(channels.indices)]

    val player = remember {
        ExoPlayer.Builder(context).build().apply { playWhenReady = true }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    var status by remember { mutableStateOf(Status.Loading) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var triedAsHls by remember { mutableStateOf(false) }
    var overlayNonce by remember { mutableIntStateOf(0) }
    var showOverlay by remember { mutableStateOf(true) }

    val currentChannel by rememberUpdatedState(channel)
    val onStarted by rememberUpdatedState(onChannelStarted)

    // Player events: track state and retry extension-less URLs as HLS once.
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> if (status != Status.Failed) status = Status.Loading
                    Player.STATE_READY -> status = Status.Playing
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val ch = currentChannel
                if (!triedAsHls && !ch.url.contains(".m3u8", ignoreCase = true)) {
                    triedAsHls = true
                    player.playChannel(ch, forceHls = true)
                } else {
                    status = Status.Failed
                    errorText = error.errorCodeName
                    showOverlay = true
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Start the channel after a short pause, so rapid zapping doesn't load every stream on the way.
    LaunchedEffect(channel) {
        status = Status.Loading
        errorText = null
        triedAsHls = false
        delay(300)
        player.playChannel(channel, forceHls = false)
        onStarted(channel)
    }

    // Show the channel banner on every switch (or OK press), hide it after 4 s unless failed.
    LaunchedEffect(channel, overlayNonce) {
        showOverlay = true
        delay(4000)
        if (status != Status.Failed) showOverlay = false
    }

    // Pause when the app goes to the background (Home button), resume when it returns.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> player.pause()
                Lifecycle.Event.ON_START -> player.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(onBack = onExit)

    val keyFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { keyFocus.requestFocus() } }

    fun zap(delta: Int) {
        if (channels.isEmpty()) return
        val next = (position + delta).mod(channels.size)
        onPositionChange(next)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(keyFocus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionUp, Key.ChannelDown -> { zap(-1); true }
                    Key.DirectionDown, Key.ChannelUp -> { zap(+1); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Info -> {
                        overlayNonce++
                        true
                    }
                    else -> false
                }
            },
    ) {
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.player_view, null) as PlayerView).apply {
                    this.player = player
                    useController = false
                    keepScreenOn = true
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        AnimatedVisibility(
            visible = showOverlay || status == Status.Failed,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ChannelBanner(
                channel = channel,
                number = position + 1,
                total = channels.size,
                status = status,
                errorText = errorText,
            )
        }
    }
}

@Composable
private fun ChannelBanner(
    channel: Channel,
    number: Int,
    total: Int,
    status: Status,
    errorText: String?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.75f))
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (channel.logo != null) {
            AsyncImage(
                model = channel.logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(width = 96.dp, height = 56.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                "$number  ${channel.name}",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when (status) {
                    Status.Loading -> "Connecting…"
                    Status.Playing -> channel.groups.joinToString(" · ")
                    Status.Failed -> "Channel unavailable" + (errorText?.let { " ($it)" } ?: "")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (status == Status.Failed) Color(0xFFFF8A80) else Color(0xFFB0B8C8),
            )
        }
        Text(
            "$number / $total   ▲▼ switch · OK info · Back list",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF8A93A6),
        )
    }
}

/** Builds a media source with the channel's own User-Agent / Referer and starts playback. */
@OptIn(UnstableApi::class)
private fun ExoPlayer.playChannel(channel: Channel, forceHls: Boolean) {
    val headers = buildMap {
        channel.referrer?.let { put("Referer", it) }
    }
    val dataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(channel.userAgent ?: DEFAULT_USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(10_000)
        .setReadTimeoutMs(15_000)
        .setDefaultRequestProperties(headers)

    val item = MediaItem.Builder()
        .setUri(channel.url)
        .apply {
            if (forceHls || channel.url.contains(".m3u8", ignoreCase = true)) {
                setMimeType(MimeTypes.APPLICATION_M3U8)
            }
        }
        .build()

    val source = DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(item)
    setMediaSource(source)
    prepare()
    play()
}
