package com.mochyy.mochymusic.ui.player.styles

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.mochyy.mochymusic.applecanvas.AppleMusicCanvasProvider
import com.mochyy.mochymusic.canvas.MonochromeApiCanvas
import com.mochyy.mochymusic.constants.CanvasThumbnailAnimationKey
import com.mochyy.mochymusic.constants.CanvasWifiOnlyKey
import com.mochyy.mochymusic.ui.player.CanvasArtworkPlayer
import com.mochyy.mochymusic.ui.player.normalizeCanvasSongTitle
import com.mochyy.mochymusic.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** animated-artwork video urls by song; an empty string records "looked, has none" */
internal object PosterCanvasCache {
    private val map = ConcurrentHashMap<String, String>()
    private fun key(t: StyleTrack) = t.title + "\u0000" + t.artist
    fun peek(t: StyleTrack): String? = map[key(t)]?.takeIf { it.isNotEmpty() }
    fun known(t: StyleTrack) = map.containsKey(key(t))
    fun put(t: StyleTrack, url: String?) { map[key(t)] = url.orEmpty() }
}

/**
 * The Apple Music animated artwork (muted looping video) for [track], or null while looking and
 * when the song has none - the still cover then simply stays, so nothing ever shows black.
 */
@Composable
internal fun rememberPosterCanvasUrl(track: StyleTrack, enabled: Boolean): String? {
    val context = LocalContext.current
    val (canvasOn) = rememberPreference(CanvasThumbnailAnimationKey, defaultValue = true)
    val (wifiOnly) = rememberPreference(CanvasWifiOnlyKey, defaultValue = true)
    var url by remember(track.title, track.artist) { mutableStateOf(PosterCanvasCache.peek(track)) }
    LaunchedEffect(track.title, track.artist, enabled, canvasOn, wifiOnly) {
        if (!enabled || !canvasOn || track.title.isBlank() || track.artist.isBlank()) { url = null; return@LaunchedEffect }
        if (PosterCanvasCache.known(track)) { url = PosterCanvasCache.peek(track); return@LaunchedEffect }
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        if (wifiOnly && cm?.isActiveNetworkMetered == true) return@LaunchedEffect
        if (com.mochyy.mochymusic.utils.DataSaver.isActive(context)) return@LaunchedEffect
        val title = normalizeCanvasSongTitle(track.title)
        val artist = com.mochyy.mochymusic.ui.player.normalizeCanvasArtistName(track.artist)
        if (title.length < 2 || artist.length < 2) return@LaunchedEffect
        val found = runCatching {
            withContext(Dispatchers.IO) {
                MonochromeApiCanvas.getBySongArtist(title, artist, null, null)
                    ?.preferredAnimationUrl?.takeIf { it.isNotBlank() }
                    ?: AppleMusicCanvasProvider.getBySongArtist(title, artist, null, "us", null)
                        ?.preferredAnimationUrl?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
        PosterCanvasCache.put(track, found)
        url = found
    }
    return url
}

/** the looping video; transparent until its first frame, so the still cover beneath is the fallback */
@Composable
internal fun PosterCanvasVideo(url: String, modifier: Modifier = Modifier) {
    CanvasArtworkPlayer(primaryUrl = url, fallbackUrl = null, isPlaying = true, modifier = modifier)
}
