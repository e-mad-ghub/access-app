package com.example.access.util

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class FeedbackManager(private val context: Context) {
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
    
    private val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    fun emitSuccess() {
        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        val effect = VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
        vibrator.vibrate(effect)
    }

    fun emitError() {
        toneGenerator.startTone(ToneGenerator.TONE_PROP_NACK, 300)
        val timings = longArrayOf(0, 100, 50, 100)
        val amplitudes = intArrayOf(0, 255, 0, 255)
        val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
        vibrator.vibrate(effect)
    }

    fun release() {
        toneGenerator.release()
    }
}
