package com.andrerinas.headunitrevived.main.ride

import kotlin.math.exp

/**
 * Per-frame snapshot handed to every visible [RideCard].
 *
 * Deliberately one mutable instance owned by the fragment rather than a data class rebuilt each
 * frame: the render loop runs at ~60 Hz on a device that is also carrying an Android Auto session,
 * so an allocation per frame is not worth the immutability.
 *
 * It carries primitives only — no `Location`. A card that needs the position receives it through
 * [RideCard.onLocation], where it is call-scoped and cannot be retained by accident.
 */
class RideTelemetry {

    /** Eased display speed in km/h (already smoothed by the fragment). */
    var speedKmh = 0f

    /** Eased lean in degrees, + right / - left. */
    var leanDeg = 0f

    var maxLeftDeg = 0
    var maxRightDeg = 0

    /** True once a real GPS fix has been seen. */
    var hasFix = false

    var nowMillis = 0L

    /** Seconds since the previous frame, for cards that ease their own values. */
    var frameDtSec = 0f
}

/** Frame-rate independent critical damping toward [target]. Shared by the fragment and the cards. */
internal fun ease(cur: Float, target: Float, dt: Float, tau: Float): Float =
    cur + (target - cur) * (1f - exp(-dt / tau))
