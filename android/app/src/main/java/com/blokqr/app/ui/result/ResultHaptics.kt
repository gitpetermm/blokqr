package com.blokqr.app.ui.result

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.blokqr.app.model.Verdict

/**
 * Retour haptique discret, calibré selon la sévérité du verdict.
 *
 *   SAFE / NEUTRAL    -> un tick léger
 *   UNKNOWN / DANGER. -> double pulsation modérée
 *   MALICIOUS         -> triple pulsation plus ferme (alerte)
 *
 * Nécessite la permission <uses-permission android:name="android.permission.VIBRATE"/>
 * (permission « normale » : aucun consentement runtime, non sensible). Sans
 * vibreur ou permission, l'appel est un no-op silencieux (runCatching).
 *
 * minSdk 29 -> VibrationEffect (API 26) toujours disponible. VibratorManager
 * (API 31) est utilisé quand présent, sinon repli sur le service historique.
 */
fun Context.vibrateForVerdict(verdict: Verdict) {
    val vib = vibratorOrNull() ?: return
    if (!vib.hasVibrator()) return

    val timings: LongArray
    val amplitudes: IntArray
    when (verdict) {
        Verdict.SAFE, Verdict.NEUTRAL -> {
            timings = longArrayOf(0, 18)
            amplitudes = intArrayOf(0, 120)
        }
        Verdict.UNKNOWN, Verdict.DANGEROUS -> {
            timings = longArrayOf(0, 22, 60, 22)
            amplitudes = intArrayOf(0, 160, 0, 160)
        }
        Verdict.MALICIOUS -> {
            timings = longArrayOf(0, 32, 50, 32, 50, 42)
            amplitudes = intArrayOf(0, 200, 0, 220, 0, 255)
        }
    }

    runCatching {
        val effect = if (vib.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(timings, amplitudes, -1)
        } else {
            VibrationEffect.createWaveform(timings, -1)
        }
        vib.vibrate(effect)
    }
}

private fun Context.vibratorOrNull(): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
