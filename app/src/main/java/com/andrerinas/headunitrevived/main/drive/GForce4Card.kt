package com.andrerinas.headunitrevived.main.drive

import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.main.ride.RideCard
import com.andrerinas.headunitrevived.main.ride.RideTelemetry
import com.andrerinas.headunitrevived.main.ride.ease
import com.andrerinas.headunitrevived.sensors.DriveEstimator
import kotlin.math.abs

/**
 * Four-way G-FORCE page of the Drive screen's middle slot, drawn by [GForce4GaugeView] after the
 * design handoff: braking, acceleration and both directions of cornering on one segmented cross,
 * with the combined figure and the four recorded maxima.
 *
 * Lateral is shown as **felt** force (the user's choice): a left turn throws the occupants right,
 * so it lights the right arm. The maxima follow the arms, so M. RIGHT is the hardest left turn -
 * the box and the marker beside it always describe the same side.
 *
 * DriveFragment holds [DriveEstimator] for the whole screen, so this page acquires nothing itself.
 * Same display rules as the Ride G-FORCE card: eased 20 Hz -> 60 Hz, a small deadband on the way
 * to the screen only.
 */
class GForce4Card : RideCard(ID, R.layout.item_drive_card_gforce4) {

    private var gauge: GForce4GaugeView? = null

    private var dispLong = 0f
    private var dispLat = 0f

    override fun onBind(v: View) {
        gauge = v.findViewById(R.id.gforce4_gauge)
        gauge?.setOnLongClickListener { view ->
            DriveEstimator.resetMax()
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            Toast.makeText(view.context, R.string.ride_gforce_reset, Toast.LENGTH_SHORT).show()
            true
        }
    }

    override fun onUnbind() {
        gauge?.setOnLongClickListener(null)
        gauge = null
    }

    override fun render(t: RideTelemetry) {
        val s = DriveEstimator.state.value
        dispLong = ease(dispLong, s.longG, t.frameDtSec, TAU_G)
        dispLat = ease(dispLat, s.latG, t.frameDtSec, TAU_G)
        val shownLong = if (abs(dispLong) < DISPLAY_DEADBAND) 0f else dispLong
        val shownLat = if (abs(dispLat) < DISPLAY_DEADBAND) 0f else dispLat

        gauge?.let {
            it.longG = shownLong
            // latG is + for a left turn; felt, that lights the right arm.
            it.latArm = if (LATERAL_FELT) shownLat else -shownLat
            it.maxBrake = s.maxBrakeG
            it.maxAcc = s.maxAccG
            it.maxRightArm = if (LATERAL_FELT) s.maxLeftG else s.maxRightG
            it.maxLeftArm = if (LATERAL_FELT) s.maxRightG else s.maxLeftG
        }
    }

    companion object {
        const val ID = "gforce4"
        private const val TAU_G = 0.05f
        private const val DISPLAY_DEADBAND = 0.025f

        /** A left turn lights the right arm, as the body is thrown. */
        private const val LATERAL_FELT = true
    }
}
