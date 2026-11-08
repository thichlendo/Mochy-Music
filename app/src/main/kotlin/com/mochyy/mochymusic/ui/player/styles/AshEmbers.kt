package com.mochyy.mochymusic.ui.player.styles

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import coil3.compose.AsyncImage
import com.mochyy.mochymusic.LocalPlayerConnection
import com.mochyy.mochymusic.lyrics.LyricsEntry
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** how small the covers get at the edge of the hand-over */
private const val AshScale = 0.85f
private const val EmberCount = 80

/**
 * State of the Poster's "Ash Embers" hand-over: for [durationMs] after a song change the old cover
 * shrinks and comes apart into embers that swirl across to the new one, which blooms back out; the
 * old song's lyrics stay on screen for as long as the old song is still sounding.
 *
 * The plain vars are only touched from the main thread and are written during composition on
 * purpose: the frame a song changes in must already draw the hand-over, not a flash of the new song.
 */
@Stable
internal class AshTransition {
    var enabled = false
    var durationMs = 3000
    val progress = Animatable(0f)
    var active by mutableStateOf(false)

    /** composition saw a new song that [active] hasn't been started for yet */
    var pending = false
    var curKey: String? = null
    var seenKey: String? = null

    var lastTrack: StyleTrack? = null
    var lastLines: List<LyricsEntry> = emptyList()
    var samplePos = 0L
    var sampleDuration = C.TIME_UNSET

    var oldTrack: StyleTrack? = null
    var oldLines: List<LyricsEntry> = emptyList()
    var oldStartMs = 0L
    var holdLyrics = false

    val showing: Boolean get() = enabled && (active || pending)
    val heldTrack: StyleTrack? get() = if (pending) lastTrack else oldTrack
    val heldLines: List<LyricsEntry> get() = if (pending) lastLines else oldLines
    val heldUrl: String? get() = heldTrack?.artwork
    val holding: Boolean get() = showing && (if (pending) decideHold() else holdLyrics)

    /** only a song that really ran into its end gets its lyrics held: a skip has nothing left to sing */
    fun decideHold(): Boolean =
        lastLines.isNotEmpty() && sampleDuration > 0 &&
            abs(samplePos - (sampleDuration - durationMs)) < 2500

    val embers = Embers(EmberCount, Random.nextInt())
}

/** the embers: start and end of each one's drift across the cover, as fractions of the cover box */
internal class Embers(val count: Int, seed: Int) {
    private val rnd = Random(seed)
    private val sx = FloatArray(count) { 0.1f + rnd.nextFloat() * 0.8f }
    private val sy = FloatArray(count) { 0.05f + rnd.nextFloat() * 0.8f }
    private val tx = FloatArray(count) { 0.3f + rnd.nextFloat() * 0.35f }
    private val ty = FloatArray(count) { 0.25f + rnd.nextFloat() * 0.3f }
    private val lag = FloatArray(count) { rnd.nextFloat() * 0.35f }
    private val life = FloatArray(count) { 0.45f + rnd.nextFloat() * 0.2f }
    private val radius = FloatArray(count) { 1.2f + rnd.nextFloat() * 2.8f }
    private val swirl = FloatArray(count) { (if (rnd.nextBoolean()) 1f else -1f) * (3f + rnd.nextFloat() * 4f) }
    private val phase = FloatArray(count) { rnd.nextFloat() * 6.2832f }
    private val warm = FloatArray(count) { rnd.nextFloat() * 0.35f }

    fun DrawScope.draw(p: Float) {
        val w = size.width
        val h = size.height
        val reach = min(w, h) * 0.18f
        val unit = 1.dp.toPx()
        for (i in 0 until count) {
            val t = (p - lag[i]) / life[i]
            if (t <= 0f || t >= 1f) continue
            val k = t * t * (3f - 2f * t)
            val envelope = sin(PI.toFloat() * t)
            val angle = phase[i] + t * swirl[i]
            val x = (sx[i] + (tx[i] - sx[i]) * k) * w + cos(angle) * envelope * reach
            val y = (sy[i] + (ty[i] - sy[i]) * k) * h + sin(angle) * envelope * reach
            val tint = Color(1f, 1f - warm[i] * 0.3f, 1f - warm[i], envelope * 0.9f)
            drawCircle(tint, radius = radius[i] * unit, center = Offset(x, y))
        }
    }
}

/**
 * Tracks song changes for [AshTransition]. The old song's position is sampled while it plays, so
 * the lyrics held on screen go on from where it was, in step with the old song still fading out.
 */
@Composable
internal fun rememberAshTransition(
    enabled: Boolean,
    durationMs: Int,
    track: StyleTrack,
    lines: List<LyricsEntry>,
    lyricsMediaId: String?,
    position: LivePosition,
): AshTransition {
    val ash = remember { AshTransition() }
    val connection = LocalPlayerConnection.current
    val key = track.title + "\u0000" + track.artist
    ash.enabled = enabled
    ash.durationMs = durationMs
    ash.curKey = key
    ash.pending = enabled && ash.seenKey != null && ash.seenKey != key

    // while the song is the one last seen, keep its details and what it's up to
    if (ash.seenKey == key) {
        ash.lastTrack = track
        if (lines.isNotEmpty() && lyricsMediaId != null && lyricsMediaId == connection?.player?.currentMediaItem?.mediaId) {
            ash.lastLines = lines
        }
    }

    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        while (true) {
            if (ash.seenKey == ash.curKey) {
                ash.samplePos = position.positionMs()
                ash.sampleDuration = connection?.player?.duration ?: C.TIME_UNSET
            }
            delay(100)
        }
    }

    LaunchedEffect(key, enabled) {
        val before = ash.seenKey
        if (!enabled || before == null || before == key) {
            ash.seenKey = key
            if (before == null) ash.lastTrack = track
            return@LaunchedEffect
        }
        ash.oldTrack = ash.lastTrack
        ash.oldLines = ash.lastLines
        ash.holdLyrics = ash.decideHold()
        ash.oldStartMs = ash.samplePos
        ash.seenKey = key
        ash.lastTrack = track
        ash.lastLines = emptyList()
        ash.progress.snapTo(0f)
        ash.active = true
        try {
            ash.progress.animateTo(1f, tween(durationMs, easing = LinearEasing))
        } finally {
            ash.active = false
        }
    }
    return ash
}

private fun ashSmooth(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * The cover as the hand-over draws it: the old one shrinking and turning to embers, the embers
 * drifting over, the new one blooming out of them to fill the oval again.
 */
@Composable
internal fun AshCover(ash: AshTransition, newUrl: String?, modifier: Modifier = Modifier) {
    val progress = ash.progress
    Box(modifier) {
        AsyncImage(
            model = ash.heldUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val v = progress.value
                val s = 1f - (1f - AshScale) * ashSmooth(v / 0.25f)
                scaleX = s
                scaleY = s
                alpha = 1f - ashSmooth((v - 0.15f) / 0.55f)
            },
        )
        Canvas(Modifier.fillMaxSize()) {
            with(ash.embers) { draw(progress.value) }
        }
        AsyncImage(
            model = newUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val e = FastOutSlowInEasing.transform(((progress.value - 0.3f) / 0.7f).coerceIn(0f, 1f))
                val s = AshScale + (1f - AshScale) * e
                scaleX = s
                scaleY = s
                alpha = e
            },
        )
    }
}

/**
 * The words under the cover through a hand-over. The old song's words hold until its last line has
 * sung (the hand-over's end); then the old line slides up and away as the new song's rises in.
 * The cluster of buttons and the gradient are outside this and never move.
 */
@Composable
internal fun AshWords(
    ash: AshTransition,
    track: StyleTrack,
    lines: List<LyricsEntry>,
    position: LivePosition,
    content: @Composable (track: StyleTrack, lines: List<LyricsEntry>, position: LivePosition) -> Unit,
) {
    val liveTrack by rememberUpdatedState(track)
    val liveLines by rememberUpdatedState(lines)
    // the old song's clock: where it was when the songs changed, running on while it's still heard
    val heldPosition = remember(ash, position) {
        position.shifted { ash.oldStartMs + (ash.progress.value * ash.durationMs).toLong() - position.positionMs() }
    }
    val held = ash.holding
    AnimatedContent(
        targetState = held,
        transitionSpec = {
            if (targetState) {
                // going into the hold is instant: the old song's words were already there
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                (slideInVertically(tween(480, easing = FastOutSlowInEasing)) { it / 5 } + fadeIn(tween(480))) togetherWith
                    (slideOutVertically(tween(480, easing = FastOutSlowInEasing)) { -it / 4 } + fadeOut(tween(360)))
            }
        },
        label = "ashWords",
        modifier = Modifier.fillMaxSize(),
    ) { isHeld ->
        if (isHeld) {
            content(ash.heldTrack ?: liveTrack, ash.heldLines, heldPosition)
        } else {
            content(liveTrack, liveLines, position)
        }
    }
}
