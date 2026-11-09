package com.mochyy.mochymusic.ui.player.flagship

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mochyy.mochymusic.ui.player.styles.PosterCanvasCache
import com.mochyy.mochymusic.ui.player.styles.PosterCanvasVideo
import com.mochyy.mochymusic.ui.player.styles.StyleTrack
import kotlin.math.min

/** how small the two covers stand side by side while the songs mix */
private const val SideScale = 0.6f

/** the last stretch of the mix: the new cover slides to the middle and grows back to full size */
private const val SettleFrom = 0.8f

/**
 * The flagship's upper half while two songs mix: the old cover (left) and the new one (right) as
 * whole rounded squares at [SideScale], then, over the last 20%, the new one slides to the centre
 * and grows to 1.0 where the ordinary cover takes over again. Animated artwork plays over the
 * still when a song has it; otherwise the still stays. Draws nothing when no mix is running.
 *
 * [artSize] is the side of the full-size cover square and [artTop] where its top edge sits.
 */
@Composable
internal fun FlagshipCrossmixCover(
    mixing: Boolean,
    track: StyleTrack,
    newCanvasUrl: String?,
    durationMs: Int,
    artSize: androidx.compose.ui.unit.Dp,
    artTop: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    // the cover of the song that was playing before the mix started
    var stable by remember { mutableStateOf(track) }
    var old by remember { mutableStateOf<StyleTrack?>(null) }
    val progress = remember { Animatable(0f) }
    var running by remember { mutableStateOf(false) }

    if (!mixing && !running) stable = track

    LaunchedEffect(mixing) {
        if (!mixing) { running = false; return@LaunchedEffect }
        old = stable
        progress.snapTo(0f)
        running = true
        progress.animateTo(1f, tween(durationMs.coerceAtLeast(500), easing = LinearEasing))
    }
    val shownOld = old
    if (!running || shownOld == null) return

    val density = LocalDensity.current
    val corner = 12.dp
    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthPx = with(density) { maxWidth.toPx() }
        val sidePx = with(density) { artSize.toPx() }
        val topPx = with(density) { artTop.toPx() }
        val centreX = widthPx / 2f
        val centreY = topPx + sidePx / 2f
        // a dim plate under the pair, so the full cover behind doesn't show between them
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val v = progress.value
                    alpha = min(1f, v / 0.05f) * (1f - ((v - 0.97f) / 0.03f).coerceIn(0f, 1f))
                }
                .background(Color.Black.copy(alpha = 0.55f)),
        )
        val sideOffset = sidePx * SideScale / 2f + sidePx * 0.04f
        // the old cover: shrinks to the left half and fades out as the new one settles
        MixCard(
            track = shownOld,
            canvasUrl = PosterCanvasCache.peek(shownOld),
            sidePx = sidePx, centreX = centreX, centreY = centreY, cornerDp = corner,
            scale = { 1f - (1f - SideScale) * smooth(progress.value / 0.2f) },
            shiftX = { -sideOffset * smooth(progress.value / 0.2f) },
            alpha = { 1f - smooth((progress.value - SettleFrom) / (1f - SettleFrom)) },
        )
        // the new cover: on the right, then slides to the middle growing to 1.0
        MixCard(
            track = track,
            canvasUrl = newCanvasUrl,
            sidePx = sidePx, centreX = centreX, centreY = centreY, cornerDp = corner,
            scale = {
                val settle = FastOutSlowInEasing.transform(((progress.value - SettleFrom) / (1f - SettleFrom)).coerceIn(0f, 1f))
                val enter = smooth(progress.value / 0.2f)
                val sideScale = 1f - (1f - SideScale) * enter
                sideScale + (1f - sideScale) * settle
            },
            shiftX = {
                val settle = FastOutSlowInEasing.transform(((progress.value - SettleFrom) / (1f - SettleFrom)).coerceIn(0f, 1f))
                sideOffset * smooth(progress.value / 0.2f) * (1f - settle)
            },
            alpha = { 1f },
        )
    }
}

@Composable
private fun MixCard(
    track: StyleTrack,
    canvasUrl: String?,
    sidePx: Float,
    centreX: Float,
    centreY: Float,
    cornerDp: androidx.compose.ui.unit.Dp,
    scale: () -> Float,
    shiftX: () -> Float,
    alpha: () -> Float,
) {
    val density = LocalDensity.current
    Box(
        Modifier
            .offset { IntOffset((centreX - sidePx / 2f).toInt(), (centreY - sidePx / 2f).toInt()) }
            .size(with(density) { sidePx.toDp() })
            .graphicsLayer {
                val s = scale()
                scaleX = s
                scaleY = s
                translationX = shiftX()
                this.alpha = alpha()
            }
            .clip(RoundedCornerShape(cornerDp)),
    ) {
        AsyncImage(
            model = track.artwork,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // the animated artwork over the still; clear until it has a frame
        if (canvasUrl != null) PosterCanvasVideo(canvasUrl, Modifier.fillMaxSize())
    }
}

private fun smooth(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
