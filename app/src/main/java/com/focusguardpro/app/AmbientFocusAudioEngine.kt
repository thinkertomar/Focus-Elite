package com.focusguardpro.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.random.Random

/**
 * Procedural ambient audio synthesizer using native Android AudioTrack.
 * Generates continuous focus audio (Binaural 40Hz Gamma, Deep Brown Noise, Pink Rain)
 * on-the-fly with ZERO audio file assets, keeping the APK lean and battery friendly.
 */
object AmbientFocusAudioEngine {

    enum class SoundMode(val displayName: String, val description: String) {
        OFF("Mute", "Silent focus session"),
        GAMMA_40HZ("40Hz Gamma Waves", "Binaural pulse for peak cognitive concentration"),
        BROWN_NOISE("Deep Brown Noise", "Warm low-frequency cascade for blocking office noise"),
        SOFT_RAIN("Pink Rain", "Soft rainfall texture for soothing steady focus")
    }

    private const val SAMPLE_RATE = 44100
    private var audioTrack: AudioTrack? = null
    private var audioJob: Job? = null
    private val audioScope = CoroutineScope(Dispatchers.Default)

    var currentMode: SoundMode = SoundMode.OFF
        private set

    @Synchronized
    fun setSoundMode(mode: SoundMode, volume: Float = 0.5f) {
        if (currentMode == mode && audioTrack != null) return
        stop()
        currentMode = mode
        if (mode == SoundMode.OFF) return

        try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = (minBufferSize * 2).coerceAtLeast(SAMPLE_RATE / 2)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.setVolume(volume.coerceIn(0f, 1f))
            audioTrack?.play()

            audioJob = audioScope.launch {
                synthesizeLoop(mode, bufferSize / 2)
            }
        } catch (_: Throwable) {
            stop()
        }
    }

    @Synchronized
    fun stop() {
        currentMode = SoundMode.OFF
        try {
            audioJob?.cancel()
            audioJob = null
            audioTrack?.apply {
                stop()
                release()
            }
            audioTrack = null
        } catch (_: Throwable) {}
    }

    fun isPlaying(): Boolean = currentMode != SoundMode.OFF && audioTrack != null

    private fun synthesizeLoop(mode: SoundMode, chunkSize: Int) {
        val buffer = ShortArray(chunkSize)
        var phase1 = 0.0
        var phase2 = 0.0
        val baseFreq = 220.0
        val beatFreq = 40.0 // 40Hz binaural cognitive frequency
        var lastBrown = 0.0
        var b0 = 0.0
        var b1 = 0.0
        var b2 = 0.0

        while (audioJob?.isActive == true) {
            when (mode) {
                SoundMode.GAMMA_40HZ -> {
                    val inc1 = 2.0 * Math.PI * baseFreq / SAMPLE_RATE
                    val inc2 = 2.0 * Math.PI * (baseFreq + beatFreq) / SAMPLE_RATE
                    for (i in 0 until chunkSize) {
                        val sample = (sin(phase1) + sin(phase2)) * 0.45
                        buffer[i] = (sample * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        phase1 += inc1
                        phase2 += inc2
                        if (phase1 > 2 * Math.PI) phase1 -= 2 * Math.PI
                        if (phase2 > 2 * Math.PI) phase2 -= 2 * Math.PI
                    }
                }
                SoundMode.BROWN_NOISE -> {
                    for (i in 0 until chunkSize) {
                        val white = Random.nextDouble(-1.0, 1.0)
                        lastBrown = (lastBrown + (0.02 * white)) / 1.02
                        val sample = lastBrown * 3.5
                        buffer[i] = (sample * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }
                }
                SoundMode.SOFT_RAIN -> {
                    for (i in 0 until chunkSize) {
                        val white = Random.nextDouble(-1.0, 1.0)
                        b0 = 0.99886 * b0 + white * 0.0555179
                        b1 = 0.99332 * b1 + white * 0.0750759
                        b2 = 0.96900 * b2 + white * 0.1538520
                        val pink = b0 + b1 + b2 + white * 0.5362
                        val sample = (pink * 0.12).coerceIn(-1.0, 1.0)
                        buffer[i] = (sample * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }
                }
                SoundMode.OFF -> break
            }

            try {
                audioTrack?.write(buffer, 0, chunkSize)
            } catch (_: Throwable) {
                break
            }
        }
    }
}
