package com.mochyy.mochymusic.ui.player.flagship

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.mochyy.mochymusic.ui.player.styles.StyleTrack
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class FlagshipScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    private val track = StyleTrack("Song", "Artist", "Album", null)
    private val dir = "build/outputs/roborazzi"

    private fun stage(mixing: Boolean, progress: Float, lyrics: Boolean = false) {
        rule.setContent {
            Box(Modifier.fillMaxSize().background(Color(0xFF121212))) {
                if (lyrics) {
                    Column(Modifier.padding(24.dp)) {
                        Box(Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(Color.Gray))
                        Text("Lyrics line 1", color = Color.White)
                        Text("Lyrics line 2", color = Color.White)
                        Text("Lyrics line 3", color = Color.White)
                    }
                } else {
                    // the full-size cover the flagship shows when nothing is mixing
                    Box(
                        Modifier.padding(top = 120.dp, start = 36.dp).size(340.dp)
                            .clip(RoundedCornerShape(12.dp)).background(Color.Gray),
                    )
                    FlagshipCrossmixCover(
                        mixing = mixing,
                        track = track,
                        newCanvasUrl = null,
                        progressProvider = { progress },
                        artSize = 340.dp,
                        artTop = 120.dp,
                    )
                }
            }
        }
    }

    @Test fun flagship_normal() {
        stage(mixing = false, progress = 0f)
        rule.onRoot().captureRoboImage("$dir/flagship_normal.png")
    }

    @Test fun flagship_crossmix_50() {
        stage(mixing = true, progress = 0.5f)
        rule.onRoot().captureRoboImage("$dir/flagship_crossmix_50.png")
    }

    @Test fun flagship_crossmix_90() {
        stage(mixing = true, progress = 0.9f)
        rule.onRoot().captureRoboImage("$dir/flagship_crossmix_90.png")
    }

    @Test fun flagship_lyrics() {
        stage(mixing = false, progress = 0f, lyrics = true)
        rule.onRoot().captureRoboImage("$dir/flagship_lyrics.png")
    }
}
