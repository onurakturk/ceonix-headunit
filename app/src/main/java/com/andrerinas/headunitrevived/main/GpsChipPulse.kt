package com.andrerinas.headunitrevived.main

import android.animation.ValueAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView

/**
 * Slow "breathing" of the GPS chip's glyph while the head unit is still looking for satellites, so
 * a cold start reads as "working on it" rather than as a dead icon.
 *
 * Only the glyph's alpha moves - the chip background stays put - and it eases in and out instead of
 * blinking, per the gauge smoothness rule. Shared by the Home status bar and StatusBarController
 * (Ride) so the two can never disagree on how it looks.
 *
 * If the system animator scale is 0 the glyph simply stays solid, which is fine.
 */
class GpsChipPulse(private val icon: ImageView) {

    private var animator: ValueAnimator? = null

    fun setSearching(searching: Boolean) {
        if (searching) start() else cancel()
    }

    /** Stops the pulse and leaves the glyph fully opaque. Call when the screen goes away. */
    fun cancel() {
        animator?.cancel()
        animator = null
        icon.imageAlpha = OPAQUE
    }

    private fun start() {
        if (animator != null) return
        animator = ValueAnimator.ofInt(OPAQUE, DIM).apply {
            duration = HALF_BREATH_MS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { icon.imageAlpha = it.animatedValue as Int }
            start()
        }
    }

    private companion object {
        const val OPAQUE = 255
        const val DIM = 90            // ~35%: clearly dimmer, never gone
        const val HALF_BREATH_MS = 1200L
    }
}
