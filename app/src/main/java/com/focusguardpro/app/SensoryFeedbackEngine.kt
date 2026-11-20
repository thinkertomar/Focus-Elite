package com.focusguardpro.app

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.OvershootInterpolator

/**
 * 100% Offline tactile haptics, subtle spring animations, and procedural ToneGenerator cues.
 * Respects device Silent/Vibrate mode and user toggles in Focus & Rules settings.
 */
object SensoryFeedbackEngine {

    private const val PREFS_NAME = "FocusElite_SensoryPrefs"
    private const val KEY_HAPTICS_ENABLED = "haptics_enabled"
    private const val KEY_AUDIO_CUES_ENABLED = "audio_cues_enabled"

    private val mainHandler = Handler(Looper.getMainLooper())

    fun isHapticsEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HAPTICS_ENABLED, true)
    }

    fun setHapticsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HAPTICS_ENABLED, enabled)
            .apply()
    }

    fun isAudioCuesEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUDIO_CUES_ENABLED, true)
    }

    fun setAudioCuesEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUDIO_CUES_ENABLED, enabled)
            .apply()
    }

    fun tap(context: Context, view: View? = null) {
        if (!isHapticsEnabled(context)) return
        if (view != null) {
            try {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                return
            } catch (_: Throwable) {}
        }
        vibrateOneShot(context, 14L, 70)
    }

    fun toggle(context: Context, view: View? = null) {
        if (!isHapticsEnabled(context)) return
        if (view != null) {
            try {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                animateBounce(view, 0.94f)
                return
            } catch (_: Throwable) {}
        }
        vibrateOneShot(context, 22L, 110)
    }

    fun setCompleted(context: Context, view: View? = null) {
        if (view != null) {
            animateBounce(view, 1.12f)
        }
        if (isHapticsEnabled(context)) {
            vibratePattern(context, longArrayOf(0, 22, 35, 34), intArrayOf(0, 120, 0, 180))
        }
        if (isAudioCuesEnabled(context)) {
            playToneSafe(context, ToneGenerator.TONE_PROP_BEEP, 90, 58)
        }
    }

    fun restCountdownTick(context: Context, secondsLeft: Int) {
        if (isHapticsEnabled(context)) {
            val amp = if (secondsLeft <= 1) 140 else 85
            vibrateOneShot(context, 18L, amp)
        }
        if (isAudioCuesEnabled(context)) {
            playToneSafe(context, ToneGenerator.TONE_PROP_BEEP, 70, 50)
        }
    }

    fun restComplete(context: Context, view: View? = null) {
        if (view != null) {
            animateBounce(view, 1.06f)
        }
        if (isHapticsEnabled(context)) {
            vibratePattern(context, longArrayOf(0, 45, 70, 85), intArrayOf(0, 175, 0, 235))
        }
        if (isAudioCuesEnabled(context)) {
            playToneSafe(context, ToneGenerator.TONE_PROP_ACK, 200, 75)
        }
    }

    fun workoutFinished(context: Context) {
        if (isHapticsEnabled(context)) {
            vibratePattern(
                context,
                longArrayOf(0, 35, 55, 55, 65, 110),
                intArrayOf(0, 140, 0, 185, 0, 255)
            )
        }
        if (isAudioCuesEnabled(context)) {
            playToneSafe(context, ToneGenerator.TONE_PROP_BEEP2, 240, 80)
        }
    }

    fun animateBounce(view: View, peakScale: Float = 1.07f) {
        try {
            view.animate().cancel()
            view.animate()
                .scaleX(peakScale)
                .scaleY(peakScale)
                .setDuration(110L)
                .withEndAction {
                    view.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(190L)
                        .setInterpolator(OvershootInterpolator(1.6f))
                        .start()
                }
                .start()
        } catch (_: Throwable) {}
    }

    fun animateFadeSlideIn(view: View, offsetYDp: Float = 10f) {
        try {
            val density = view.resources.displayMetrics.density
            view.alpha = 0f
            view.translationY = offsetYDp * density
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220L)
                .start()
        } catch (_: Throwable) {}
    }

    private fun vibrateOneShot(context: Context, durationMs: Long, amplitude: Int) {
        try {
            val vibrator = resolveVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val safeAmp = amplitude.coerceIn(1, 255)
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, safeAmp))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (_: Throwable) {}
    }

    private fun vibratePattern(context: Context, timings: LongArray, amplitudes: IntArray) {
        try {
            val vibrator = resolveVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(timings, -1)
            }
        } catch (_: Throwable) {}
    }

    private fun resolveVibrator(context: Context): Vibrator? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun playToneSafe(context: Context, toneType: Int, durationMs: Int, volume: Int) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am != null && am.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
                // Respect Silent & Vibrate-Only device modes
                return
            }
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, volume.coerceIn(15, 90))
            tg.startTone(toneType, durationMs)
            mainHandler.postDelayed({
                try {
                    tg.release()
                } catch (_: Throwable) {}
            }, durationMs + 120L)
        } catch (_: Throwable) {}
    }
}
