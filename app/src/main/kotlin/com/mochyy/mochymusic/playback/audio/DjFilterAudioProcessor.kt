package com.mochyy.mochymusic.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow

/**
 * The DJ Automix filter: a two-pole low-pass that closes on the song leaving, or a two-pole
 * high-pass that opens on the song arriving. Off, the audio passes through untouched.
 * [setSweep] is written from the crossfade loop and read on the audio thread.
 */
@UnstableApi
@Suppress("DEPRECATION")
class DjFilterAudioProcessor : AudioProcessor {
    enum class Mode { OFF, LOW_PASS, HIGH_PASS }

    @Volatile private var mode = Mode.OFF
    @Volatile private var cutoffHz = 20_000f

    private var inputFormat = AudioProcessor.AudioFormat.NOT_SET
    private var supported = false
    private var outputBuffer: ByteBuffer = EMPTY
    private var scratch: ByteBuffer = EMPTY
    private var inputEnded = false

    // two cascaded one-poles per channel; [stage][channel]
    private val state = Array(2) { FloatArray(MAX_CHANNELS) }

    /** [progress] 0..1 through the hand-over: the low-pass closes from 18 kHz to 250 Hz, the high-pass opens from 1.6 kHz to nothing */
    fun setSweep(mode: Mode, progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        cutoffHz = when (mode) {
            Mode.LOW_PASS -> 18_000f * (250f / 18_000f).pow(p)
            Mode.HIGH_PASS -> 1_600f * (1f - p).pow(2f)
            Mode.OFF -> 20_000f
        }
        this.mode = if (mode == Mode.HIGH_PASS && cutoffHz < 25f) Mode.OFF else mode
    }

    fun off() {
        mode = Mode.OFF
    }

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        inputFormat = inputAudioFormat
        supported = (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) &&
            inputAudioFormat.channelCount in 1..MAX_CHANNELS
        // an encoding it can't filter just passes through
        return inputAudioFormat
    }

    // always in the chain, so a filter switched on mid-song needs no reconfigure
    override fun isActive(): Boolean = true

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        if (scratch.capacity() < size) scratch = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        scratch.clear()
        val currentMode = mode
        if (!supported || currentMode == Mode.OFF) {
            scratch.put(inputBuffer)
        } else {
            val channels = inputFormat.channelCount
            val a = 1f - exp(-2f * PI.toFloat() * cutoffHz / inputFormat.sampleRate)
            val src = inputBuffer.order(ByteOrder.nativeOrder())
            val float = inputFormat.encoding == C.ENCODING_PCM_FLOAT
            var ch = 0
            while (src.hasRemaining()) {
                val x = if (float) src.getFloat() else src.getShort() / 32768f
                val s0 = state[0]
                val s1 = state[1]
                s0[ch] += a * (x - s0[ch])
                s1[ch] += a * (s0[ch] - s1[ch])
                val y = if (currentMode == Mode.LOW_PASS) s1[ch] else x - s1[ch]
                if (float) scratch.putFloat(y)
                else scratch.putShort((y * 32767f).coerceIn(-32768f, 32767f).toInt().toShort())
                ch = (ch + 1) % channels
            }
        }
        scratch.flip()
        outputBuffer = scratch
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = EMPTY
        return out
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === EMPTY

    override fun flush() {
        outputBuffer = EMPTY
        inputEnded = false
        state.forEach { it.fill(0f) }
    }

    override fun reset() {
        flush()
        inputFormat = AudioProcessor.AudioFormat.NOT_SET
        supported = false
        scratch = EMPTY
    }

    private companion object {
        const val MAX_CHANNELS = 8
        val EMPTY: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }
}
