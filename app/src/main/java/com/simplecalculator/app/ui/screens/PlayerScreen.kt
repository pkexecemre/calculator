package com.simplecalculator.app.ui.screens

import android.app.PictureInPictureParams
import android.media.AudioManager
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.simplecalculator.app.R
import com.simplecalculator.app.core.formatDuration
import com.simplecalculator.app.core.formatViews
import com.simplecalculator.app.data.db.VideoEntity
import com.simplecalculator.app.data.source.CommentItem
import com.simplecalculator.app.data.source.VideoDetail
import com.simplecalculator.app.data.source.VideoSummary
import com.simplecalculator.app.data.subtitle.SubtitleTrack
import com.simplecalculator.app.data.source.ph.PhClient
import com.simplecalculator.app.ui.DurationBadge
import com.simplecalculator.app.ui.LocalContainer
import com.simplecalculator.app.ui.Thumb
import com.simplecalculator.app.ui.theme.Surface2
import com.simplecalculator.app.ui.theme.TextMuted
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * How long a caption stays after it lands. The minimum bridges the gap until
 * the next four-second window finishes, and longer lines get more reading time.
 */
private fun captionHoldMs(text: String): Long =
    (3_500L + text.length * 45L).coerceIn(4_000L, 8_000L)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(
    videoKey: String,
    onBack: () -> Unit,
    onOpenVideo: (String) -> Unit,
    onOpenStar: (String) -> Unit = {}
) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun prefsOf(ctx: android.content.Context) =
        ctx.getSharedPreferences("calc_state", android.content.Context.MODE_PRIVATE)

    var entity by remember { mutableStateOf<VideoEntity?>(null) }
    var streamUrlForSubs by remember { mutableStateOf<String?>(null) }
    var comments by remember { mutableStateOf<List<CommentItem>>(emptyList()) }
    var related by remember { mutableStateOf<List<VideoSummary>>(emptyList()) }
    var playUri by remember { mutableStateOf<String?>(null) }
    var following by remember { mutableStateOf(false) }
    var isLocal by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var queued by remember { mutableStateOf(false) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var embedUrl by remember { mutableStateOf<String?>(null) }
    var fullscreen by remember { mutableStateOf(false) }
    var isFavorite by remember { mutableStateOf(false) }
    var resumeMs by remember { mutableStateOf(0L) }
    var streams by remember { mutableStateOf<List<com.simplecalculator.app.data.source.StreamVariant>>(emptyList()) }
    var streamIndex by remember { mutableStateOf(0) }
    var qualityLabel by remember { mutableStateOf<String?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    val playerPrefs = remember {
        context.getSharedPreferences("player_state", android.content.Context.MODE_PRIVATE)
    }
    var speed by remember { mutableStateOf(playerPrefs.getFloat("speed", 1f)) }
    var autoplay by remember { mutableStateOf(playerPrefs.getBoolean("autoplay", true)) }
    var autoplayFired by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var seekHint by remember { mutableStateOf<String?>(null) }
    var seekSide by remember { mutableStateOf<Int?>(null) } // 0 = left, 1 = right
    // Side-loaded subtitle tracks the site publishes (HLS SUBTITLES renditions).
    var subtitleTracks by remember { mutableStateOf<List<SubtitleTrack>>(emptyList()) }
    var subtitleFile by remember { mutableStateOf<java.io.File?>(null) }
    var liveSubs by remember { mutableStateOf(prefsOf(context).getString("live_subs", "off") ?: "off") }
    var captionText by remember { mutableStateOf<String?>(null) }
    var captionSerial by remember { mutableStateOf(0) }
    var sttState by remember { mutableStateOf("idle") }
    val positionRef = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    var modelProgress by remember { mutableStateOf<Int?>(null) }
    var subtitleChoice by remember { mutableStateOf("off") }
    var loadingSubs by remember { mutableStateOf(false) }
    var resumeNoticeMs by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(videoKey, reload) {
        // A new video must not inherit the previous one's stream or player mode.
        playUri = null
        embedUrl = null
        isLocal = false
        qualityLabel = null
        streamIndex = 0
        autoplayFired = false
        streams = emptyList()
        comments = emptyList()
        related = emptyList()
        resumeMs = 0L
        playbackError = null
        queued = false
        isFavorite = false
        following = false
        resumeNoticeMs = null
        // The row is normally cached by the feed; retry briefly in case a write
        // from the series screen is still in flight.
        var cached = container.content.videoFor(videoKey)
        var tries = 0
        while (cached == null && tries < 3) {
            kotlinx.coroutines.delay(250)
            cached = container.content.videoFor(videoKey)
            tries++
        }
        entity = cached
        if (cached == null) {
            loading = false
            return@LaunchedEffect
        }
        following = container.content.isFollowing(cached.uploaderSlug)
        isFavorite = container.content.isFavorite(cached.key)
        resumeMs = container.content.progressFor(cached.key)

        val local = container.downloads.localFile(videoKey)
        if (local != null) {
            playUri = Uri.fromFile(local).toString()
            isLocal = true
        }

        val parsed = runCatching { container.content.detailFor(cached) }.getOrNull()
        streams = parsed?.streams.orEmpty().sortedByDescending { it.quality }
        if (qualityLabel == null && playUri == null) {
            qualityLabel = streams.firstOrNull()?.label
        }
        if (parsed?.playerMode == com.simplecalculator.app.data.source.PlayerMode.EMBED) {
            embedUrl = parsed.embedUrl
            loading = false
            return@LaunchedEffect
        }
        if (cached.uploaderSlug == null && parsed?.uploaderSlug != null) {
            container.content.applyUploader(cached.key, parsed.uploaderSlug, parsed.uploaderName)
            container.content.videoFor(cached.key)?.let { entity = it }
        }
        if (playUri == null) {
            playUri = streams.firstOrNull()?.url
            android.util.Log.i(
                "Player",
                "streams=" + parsed?.streams?.joinToString { it.label + ":" + it.hls } + " picked=" + playUri
            )
        }
        streamUrlForSubs = playUri
        // Show the player/metadata now; comments and related videos are
        // secondary and should not keep the page behind a spinner.
        loading = false
        container.content.recordView(cached, false)
        kotlinx.coroutines.coroutineScope {
            val commentsJob = async {
                runCatching {
                    container.content.comments(
                        cached.vkey, cached.sourceId, parsed?.commentsUrl, 0
                    )
                }.getOrDefault(emptyList())
            }
            val relatedJob = async {
                runCatching {
                    container.content.related(cached.vkey, cached.sourceId, parsed?.relatedUrl)
                        .take(12)
                }.getOrDefault(emptyList())
            }
            comments = commentsJob.await()
            related = relatedJob.await()
        }
    }

    fun buildItem(
        uri: Uri,
        subs: java.io.File?,
        metadata: MediaMetadata? = null
    ): MediaItem {
        val builder = MediaItem.Builder().setUri(uri)
        if (metadata != null) builder.setMediaMetadata(metadata)
        if (subs != null) {
            builder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(subs))
                        .setMimeType(if (subs.extension == "srt") "application/x-subrip" else "text/vtt")
                        .setLanguage(subtitleChoice)
                        .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            )
        }
        return builder.build()
    }

    var player by remember { mutableStateOf<androidx.media3.common.Player?>(null) }
    var controller by remember { mutableStateOf<androidx.media3.session.MediaController?>(null) }

    fun seekBy(deltaMs: Long, rightSide: Boolean) {
        val exo = player ?: return
        exo.seekTo((exo.currentPosition + deltaMs).coerceAtLeast(0L))
        seekHint = if (deltaMs >= 0) "+10s" else "−10s"
        seekSide = if (rightSide) 1 else 0
    }

    androidx.compose.runtime.DisposableEffect(context) {
        val token = androidx.media3.session.SessionToken(
            context,
            android.content.ComponentName(context, com.simplecalculator.app.PlaybackService::class.java)
        )
        val future = androidx.media3.session.MediaController.Builder(context, token).buildAsync()
        future.addListener({
            runCatching { future.get() }.onSuccess { controller = it }
                .onFailure { android.util.Log.e("Player", "controller failed", it) }
        }, androidx.core.content.ContextCompat.getMainExecutor(context))
        onDispose {
            if (future.isDone) runCatching { future.get().release() } else future.cancel(true)
            controller = null
        }
    }

    // `controller` is a key: the controller is built asynchronously, so an
    // early `playUri` must re-run this effect once the session connects.
    DisposableEffect(playUri, controller, entity?.key) {
        val uri = playUri
        val client = controller
        if (uri == null || client == null) {
            onDispose { }
        } else {
            val listener = object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    android.util.Log.e("Player", "playback failed: " + uri, error)
                    playbackError = error.errorCodeName
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    // Pausing strands the last <4 s of audio; transcribe the
                    // tail once the sink has settled instead of dropping it.
                    container.transcriber.onPlaybackState(isPlaying)
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                        // A seek invalidates buffered audio and captions that are
                        // still being transcribed for the old position.
                        positionRef.set(newPosition.positionMs)
                        container.transcriber.onSeek(newPosition.positionMs)
                        captionText = null
                        captionSerial += 1
                    }
                }
            }
            client.addListener(listener)
            val artwork = entity?.thumb?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
            val metadata = MediaMetadata.Builder()
                .setTitle(entity?.title.orEmpty())
                .setArtist(entity?.uploaderName.orEmpty())
                .setArtworkUri(artwork)
                .build()
            client.setMediaItem(buildItem(Uri.parse(uri), subtitleFile, metadata))
            client.prepare()
            val target = resumeMs
            if (target > 5_000L) {
                // Seeking before the timeline exists throws IllegalSeekPosition;
                // wait for READY and clamp to the real duration.
                val seeker = object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            runCatching {
                                val duration = client.duration
                                if (duration > 0) client.seekTo(target.coerceAtMost(duration - 1_000L))
                            }
                            client.removeListener(this)
                        }
                    }
                }
                client.addListener(seeker)
                resumeNoticeMs = target
            }
            client.setPlaybackSpeed(speed)
            client.playWhenReady = true
            player = client
            onDispose {
                client.removeListener(listener)
                // Keep the service playing; only drop our reference.
                player = null
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(subtitleChoice, subtitleTracks) {
        if (subtitleChoice == "off") {
            subtitleFile = null
            return@LaunchedEffect
        }
        val track = subtitleTracks.firstOrNull { it.language.startsWith(subtitleChoice) }
            ?: subtitleTracks.firstOrNull()
            ?: return@LaunchedEffect
        loadingSubs = true
        subtitleFile = runCatching {
            container.subtitles.translated(track, subtitleChoice, null)
        }.getOrNull()
        loadingSubs = false
        // Re-attach the item so the track is picked up, keeping the position.
        val exo = player
        if (exo != null && subtitleFile != null) {
            val resume = exo.currentPosition
            val uri = exo.currentMediaItem?.localConfiguration?.uri
            if (uri != null) {
                resumeMs = resume
                exo.setMediaItem(buildItem(uri, subtitleFile))
                exo.prepare()
                exo.seekTo(resume)
            }
        }
    }

    // Live speech-to-text: tap the audio that is playing and caption it locally.
    androidx.compose.runtime.DisposableEffect(liveSubs, videoKey, entity?.category) {
        captionText = null
        captionSerial += 1
        if (liveSubs == "off") {
            com.simplecalculator.app.player.PcmBridge.listener = null
            container.transcriber.captions = {}
            container.transcriber.onState = {}
            container.transcriber.stop()
            onDispose { }
        } else {
            val targetLang = liveSubs
            // JAV and hentai are Japanese and get translated to English;
            // everything else is transcribed as English. This avoids the
            // expensive per-window language detection.
            val languageHint = when (entity?.category?.uppercase()) {
                "JAV", "HENTAI" -> "ja"
                else -> "en"
            }
            container.transcriber.onState = { state ->
                scope.launch { sttState = state.name.lowercase() }
            }
            container.transcriber.captions = { cues: List<com.simplecalculator.app.data.stt.Caption> ->
                // One window can contain several sentences: show all of them,
                // not just the last cue, or most of the speech is silently lost.
                val text = cues.joinToString(" ") { it.text }.trim()
                scope.launch {
                    captionSerial += 1
                    if (text.isEmpty()) {
                        captionText = null
                        return@launch
                    }
                    val serial = captionSerial
                    captionText = text
                    if (targetLang == "tr") {
                        val translated = runCatching {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                container.translator.translateCaption(text, "tr")
                            }
                        }.getOrNull()
                        // A newer window may have arrived (or the line may have
                        // expired) while the request was in flight; never revive
                        // or replace an older line.
                        if (serial == captionSerial && captionText != null && translated != null) {
                            captionText = translated
                        }
                    }
                }
            }
            com.simplecalculator.app.player.PcmBridge.listener = { buffer, rate, channels, encoding ->
                container.transcriber.onPcm(
                    buffer,
                    rate,
                    channels,
                    encoding,
                    positionRef.get()
                )
            }
            container.transcriber.start(languageHint)
            onDispose {
                com.simplecalculator.app.player.PcmBridge.listener = null
                container.transcriber.captions = {}
                container.transcriber.onState = {}
                container.transcriber.stop()
            }
        }
    }

    // Captions disappear on their own during silence instead of staying on
    // screen until the next window finishes.
    androidx.compose.runtime.LaunchedEffect(captionSerial) {
        val text = captionText ?: return@LaunchedEffect
        kotlinx.coroutines.delay(captionHoldMs(text))
        captionText = null
    }

    val embedded = embedUrl
    if (embedded != null) {
        EmbedPlayerScreen(
            url = embedded,
            title = entity?.title ?: "",
            onBack = onBack
        )
        return
    }

    androidx.compose.runtime.LaunchedEffect(streamUrlForSubs) {
        val url = streamUrlForSubs ?: return@LaunchedEffect
        subtitleTracks = runCatching {
            container.subtitles.forStream(url, null)
        }.getOrDefault(emptyList())
    }

    androidx.compose.runtime.LaunchedEffect(player) {
        while (true) {
            kotlinx.coroutines.delay(500)
            val exo = player ?: continue
            positionMs = exo.currentPosition
            positionRef.set(positionMs)
            durationMs = exo.duration.coerceAtLeast(0L)
            playing = exo.isPlaying
            if (!autoplayFired && exo.playbackState == Player.STATE_ENDED &&
                autoplay && related.isNotEmpty()
            ) {
                autoplayFired = true
                onOpenVideo(related.first().key)
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(showControls) {
        if (showControls) {
            kotlinx.coroutines.delay(3_500)
            showControls = false
        }
    }

    androidx.compose.runtime.LaunchedEffect(player, entity) {
        while (true) {
            kotlinx.coroutines.delay(5_000)
            val exo = player ?: continue
            val video = entity ?: continue
            if (exo.isPlaying) {
                container.content.recordProgress(
                    key = video.key,
                    sourceId = video.sourceId,
                    title = video.title,
                    thumb = video.thumb,
                    positionMs = exo.currentPosition,
                    durationMs = exo.duration.coerceAtLeast(0L)
                )
            }
        }
    }

    val activity = context as? android.app.Activity
    androidx.compose.runtime.DisposableEffect(fullscreen) {
        val window = activity?.window
        if (window != null) {
            val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            if (fullscreen) {
                controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        onDispose {
            if (window != null) {
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                    .show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    androidx.activity.compose.BackHandler(enabled = fullscreen) { fullscreen = false }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    // In landscape a 16:9 box is taller than the screen, which pushed the whole
    // metadata column out of view; cap the player at 60% of the height.
    val playerHeight = minOf(
        configuration.screenWidthDp.dp * 9f / 16f,
        configuration.screenHeightDp.dp * 0.6f
    )

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .then(
                    if (fullscreen) Modifier.fillMaxSize()
                    else Modifier.fillMaxWidth().height(playerHeight)
                )
                .background(Color.Black)
        ) {
            if (player != null) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        }
                    },
                    update = { view -> view.player = player },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            val notice = resumeNoticeMs
            if (notice != null && playbackError == null) {
                LaunchedEffect(notice) {
                    kotlinx.coroutines.delay(4_000)
                    resumeNoticeMs = null
                }
                Surface(
                    color = Color(0xE6000000),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(
                                R.string.player_resumed,
                                formatDuration((notice / 1000).toInt())
                            ),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                        TextButton(onClick = {
                            player?.seekTo(0L)
                            resumeNoticeMs = null
                        }) {
                            Text(stringResource(R.string.player_start_over), fontSize = 12.sp)
                        }
                    }
                }
            }
            if (liveSubs != "off" && captionText == null) {
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp)
                ) {
                    Text(
                        text = when (sttState) {
                            "loading" -> stringResource(R.string.stt_status_loading)
                            "unavailable" -> stringResource(R.string.stt_status_unavailable)
                            else -> stringResource(R.string.stt_status_listening)
                        },
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
            if (captionText != null) {
                Text(
                    text = captionText ?: "",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 64.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
            if (playbackError != null) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.player_playback_error),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            playbackError = null
                            playUri = null
                            isLocal = false
                            reload += 1
                        }) { Text(stringResource(R.string.common_retry)) }
                        if (streams.size > 1) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = {
                                val next = (streamIndex + 1) % streams.size
                                val resume = player?.currentPosition ?: 0L
                                if (resume > 0L) resumeMs = resume
                                streamIndex = next
                                qualityLabel = streams[next].label
                                playbackError = null
                                playUri = streams[next].url
                            }) { Text(stringResource(R.string.player_try_next)) }
                        }
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(fullscreen) {
                        if (!fullscreen) return@pointerInput
                        detectVerticalDragGestures { change, dragAmount ->
                            val host = context as? android.app.Activity
                                ?: return@detectVerticalDragGestures
                            if (change.position.x < size.width / 2f) {
                                val window = host.window ?: return@detectVerticalDragGestures
                                val attrs = window.attributes
                                val current = if (attrs.screenBrightness < 0f) 0.5f else attrs.screenBrightness
                                val next = (current - dragAmount / 3000f).coerceIn(0.01f, 1f)
                                attrs.screenBrightness = next
                                window.attributes = attrs
                                seekHint = "☀ " + (next * 100).toInt() + "%"
                                seekSide = 0
                            } else {
                                val audio = context.getSystemService(AudioManager::class.java)
                                    ?: return@detectVerticalDragGestures
                                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                                val next = (current - dragAmount / 120f).toInt().coerceIn(0, max)
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                                seekHint = "Vol " + (next * 100 / max) + "%"
                                seekSide = 1
                            }
                        }
                    }
            ) {
                // YouTube-style halves: double-tap anywhere on the right for
                // +10s, anywhere on the left for -10s. Top/bottom controls stay
                // interactive because the zones stop short of those edges.
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .fillMaxWidth(0.5f)
                        .padding(top = 56.dp, bottom = 68.dp)
                        .pointerInput(player) {
                            detectTapGestures(
                                onTap = { showControls = !showControls },
                                onDoubleTap = { seekBy(-10_000L, rightSide = false) }
                            )
                        }
                )
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .fillMaxWidth(0.5f)
                        .padding(top = 56.dp, bottom = 68.dp)
                        .pointerInput(player) {
                            detectTapGestures(
                                onTap = { showControls = !showControls },
                                onDoubleTap = { seekBy(10_000L, rightSide = true) }
                            )
                        }
                )
                if (seekHint != null) {
                    androidx.compose.runtime.LaunchedEffect(seekHint) {
                        kotlinx.coroutines.delay(700)
                        seekHint = null
                        seekSide = null
                    }
                    Box(
                        Modifier
                            .align(
                                if (seekSide == 0) Alignment.CenterStart else Alignment.CenterEnd
                            )
                            .padding(horizontal = 48.dp)
                            .size(84.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.62f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = seekHint ?: "",
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }
                if (showControls) {
                    // Scrims keep the controls readable over bright frames.
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(96.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent)
                                )
                            )
                    )
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(110.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))
                                )
                            )
                    )
                    Surface(
                        color = Color.Black.copy(alpha = 0.45f),
                        contentColor = Color.White,
                        shape = CircleShape,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(64.dp)
                            .clickable {
                                val exo = player
                                if (exo != null && exo.isPlaying) exo.pause() else exo?.play()
                                showControls = true
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                    }
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = formatDuration((positionMs / 1000).toInt()),
                                color = Color.White,
                                fontSize = 11.sp
                            )
                            androidx.compose.material3.Slider(
                                value = if (durationMs > 0) (positionMs.toFloat() / durationMs) else 0f,
                                onValueChange = { fraction ->
                                    val exo = player ?: return@Slider
                                    exo.seekTo((fraction * durationMs).toLong())
                                    positionMs = (fraction * durationMs).toLong()
                                },
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                            )
                            Text(
                                text = formatDuration((durationMs / 1000).toInt()),
                                color = Color.White,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
            IconButton(
                onClick = onBack,
                modifier = Modifier.padding(4.dp).align(Alignment.TopStart)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = Color.White
                )
            }
            Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                Row {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.Tune, contentDescription = null, tint = Color.White)
                    }
                    IconButton(onClick = { fullscreen = !fullscreen }) {
                        Icon(
                            if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                            contentDescription = null,
                            tint = Color.White
                        )
                    }
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    if (streams.size > 1) {
                        streams.forEachIndexed { index, variant ->
                            DropdownMenuItem(
                                text = { Text(variant.label + if (variant.label == qualityLabel) " ✓" else "") },
                                onClick = {
                                    val resume = player?.currentPosition ?: 0L
                                    streamIndex = index
                                    qualityLabel = variant.label
                                    playUri = variant.url
                                    resumeMs = resume
                                    showMenu = false
                                }
                            )
                        }
                        HorizontalDivider()
                    }
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.toString() + "x" + if (option == speed) " ✓" else "") },
                            onClick = {
                                speed = option
                                playerPrefs.edit().putFloat("speed", option).apply()
                                player?.setPlaybackSpeed(option)
                                showMenu = false
                            }
                        )
                    }
                    HorizontalDivider()
                    listOf(
                        "off" to stringResource(R.string.stt_off),
                        "en" to stringResource(R.string.stt_english),
                        "tr" to stringResource(R.string.stt_turkish)
                    ).forEach { (code, label) ->
                        DropdownMenuItem(
                            text = { Text(label + if (liveSubs == code) " ✓" else "") },
                            onClick = {
                                liveSubs = code
                                prefsOf(context).edit().putString("live_subs", code).apply()
                                showMenu = false
                            }
                        )
                    }
                    if (!container.modelStore.isReady()) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (modelProgress != null) {
                                        stringResource(R.string.stt_downloading, modelProgress ?: 0)
                                    } else {
                                        stringResource(R.string.stt_download)
                                    }
                                )
                            },
                            onClick = {
                                scope.launch {
                                    modelProgress = 0
                                    val ok = container.modelStore.download(
                                        container.modelStore.selected()
                                    ) { modelProgress = it }
                                    modelProgress = null
                                    if (ok) {
                                        liveSubs = "en"
                                        prefsOf(context).edit().putString("live_subs", "en").apply()
                                    }
                                }
                            }
                        )
                    }
                    HorizontalDivider()
                    if (subtitleTracks.isNotEmpty()) {
                        listOf(
                            "off" to stringResource(R.string.subs_off),
                            "en" to stringResource(R.string.subs_english),
                            "tr" to stringResource(R.string.subs_turkish)
                        ).forEach { (code, label) ->
                            DropdownMenuItem(
                                text = {
                                    Text(label + if (subtitleChoice == code) " ✓" else "")
                                },
                                onClick = {
                                    subtitleChoice = code
                                    showMenu = false
                                }
                            )
                        }
                        HorizontalDivider()
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.player_pip)) },
                        onClick = {
                            activity?.enterPictureInPictureMode(PictureInPictureParams.Builder().build())
                            showMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (autoplay) R.string.player_autoplay_on
                                    else R.string.player_autoplay_off
                                )
                            )
                        },
                        onClick = {
                            autoplay = !autoplay
                            playerPrefs.edit().putBoolean("autoplay", autoplay).apply()
                            showMenu = false
                        }
                    )
                }
            }
            if (isLocal) {
                Surface(
                    color = Color(0xE6000000),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                ) {
                    Text(
                        stringResource(R.string.player_offline),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        val video = entity
        if (video == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.player_missing), color = TextMuted)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onBack) {
                        Text(stringResource(R.string.common_back))
                    }
                }
            }
            return@Column
        }

        if (!fullscreen) LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        text = video.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = video.uploaderName ?: stringResource(R.string.player_unknown_uploader),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.clickable {
                                video.uploaderSlug?.let(onOpenStar)
                            }
                        )
                        if (video.views > 0) {
                            Text(
                                text = "   " + formatViews(video.views) + " views",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (queued) return@Button
                                scope.launch {
                                    container.downloads.enqueue(video, qualityLabel)
                                    queued = true
                                }
                            },
                            enabled = !isLocal
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (isLocal) stringResource(R.string.player_downloaded)
                                else if (queued) stringResource(R.string.player_download_queued)
                                else stringResource(R.string.player_download)
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    isFavorite = container.content.toggleFavorite(
                                        video.key, video.sourceId, video.title, video.thumb
                                    )
                                }
                            }
                        ) {
                            Icon(
                                if (isFavorite) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (isFavorite) stringResource(R.string.player_saved)
                                else stringResource(R.string.player_save)
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                val slug = video.uploaderSlug ?: return@OutlinedButton
                                val name = video.uploaderName ?: slug
                                scope.launch {
                                    container.content.toggleFollow(slug, name, null)
                                    following = !following
                                }
                            }
                        ) {
                            Icon(
                                if (following) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (following) stringResource(R.string.star_following)
                                else stringResource(R.string.star_follow)
                            )
                        }
                    }
                }
            }

            if (related.isNotEmpty()) {
                item {
                    Column {
                        Text(
                            text = stringResource(R.string.player_related),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 6.dp)
                        )
                        LazyRow(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(related, key = { it.key }) { item ->
                                Column(
                                    Modifier.width(160.dp).clickable { onOpenVideo(item.key) }
                                ) {
                                    Box {
                                        Thumb(item.thumb, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                                        DurationBadge(
                                            item.durationSec,
                                            Modifier.align(Alignment.BottomEnd).padding(4.dp)
                                        )
                                    }
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.player_comments, comments.size),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 6.dp)
                )
            }

            if (comments.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.player_no_comments),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }

            items(comments) { comment -> CommentRow(comment) }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun CommentRow(comment: CommentItem) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(Surface2),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = comment.author.take(1).uppercase(),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = comment.author,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (comment.verified) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(12.dp)
                    )
                }
                Text(
                    text = "  " + comment.date,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
            }
            Text(
                text = comment.text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp)
            )
            if (comment.votes > 0) {
                Text(
                    text = comment.votes.toString() + " likes",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}
