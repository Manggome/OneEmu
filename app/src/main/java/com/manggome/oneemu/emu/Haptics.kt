package com.manggome.oneemu.emu

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Thin wrapper over the platform vibrator used by the pad (press ticks) and the core (rumble). */
class Haptics(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private var lastRumbleAt = 0L

    /** Short press feedback; [ms] <= 0 uses the system tick effect. */
    fun tick(ms: Int) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            if (ms <= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else {
                v.vibrate(VibrationEffect.createOneShot(ms.coerceIn(1, 100).toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    /** Core rumble, [strength] 0..65535; throttled to one pulse per 50 ms. */
    fun rumble(strength: Int) {
        val v = vibrator ?: return
        if (strength <= 0 || !v.hasVibrator()) return
        val now = System.currentTimeMillis()
        if (now - lastRumbleAt < 50) return
        lastRumbleAt = now
        val amplitude = (strength * 255 / 65535).coerceIn(1, 255)
        runCatching { v.vibrate(VibrationEffect.createOneShot(50, amplitude)) }
    }

    fun cancel() = runCatching { vibrator?.cancel() }
}
