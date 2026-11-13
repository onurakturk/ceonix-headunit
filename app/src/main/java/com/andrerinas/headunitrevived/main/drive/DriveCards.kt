package com.andrerinas.headunitrevived.main.drive

import com.andrerinas.headunitrevived.main.ride.RideCard

/**
 * The cards in the Drive screen's middle slot, in swipe order. Persisted by id under
 * Settings.driveCardId - the same wire-format rule as RideCards: never rename an id, never reuse
 * a retired one; the order may change freely.
 */
object DriveCards {
    fun create(): List<RideCard> = listOf(PitchRollCard(), GForce4Card())
}
