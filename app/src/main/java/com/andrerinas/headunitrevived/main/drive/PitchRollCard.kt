package com.andrerinas.headunitrevived.main.drive

import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.main.ride.RideCard
import com.andrerinas.headunitrevived.main.ride.RideTelemetry
import com.andrerinas.headunitrevived.main.ride.ease
import com.andrerinas.headunitrevived.sensors.DriveEstimator
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * PITCH & ROLL page of the Drive screen's middle slot: the road's grade and camber as the car sits
 * on it, drawn by [PitchRollGaugeView] after the design handoff.
 *
 * Until the mount has been learned (a level reference and a forward axis, see [DriveEstimator])
 * the gauge is muted with no numbers and a hint says so. A long-press anywhere on the page offers
 * two actions: reset the pitch & roll maxima, or declare "level right now".
 */
class PitchRollCard : RideCard(ID, R.layout.item_drive_card_attitude) {

    private var gauge: PitchRollGaugeView? = null
    private var tvHint: View? = null

    private var dispPitch = 0f
    private var dispRoll = 0f
    private var lastReady: Boolean? = null

    override fun onBind(v: View) {
        gauge = v.findViewById(R.id.pitch_roll_gauge)
        tvHint = v.findViewById(R.id.tv_attitude_hint)
        lastReady = null

        // Long-press: two independent actions (user decision) - clear the maxima, or declare the
        // car level right now (also the cure after moving the phone).
        v.isLongClickable = true
        v.setOnLongClickListener { page ->
            page.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val ctx = page.context
            MaterialAlertDialogBuilder(ctx, R.style.DarkAlertDialog)
                .setItems(arrayOf(ctx.getString(R.string.drive_reset_max), ctx.getString(R.string.drive_set_level))) { _, which ->
                    if (which == 0) {
                        DriveEstimator.resetAttitudeMax()
                        Toast.makeText(ctx, R.string.drive_max_reset_done, Toast.LENGTH_SHORT).show()
                    } else {
                        DriveEstimator.setLevelHere()
                        dispPitch = 0f; dispRoll = 0f
                        Toast.makeText(ctx, R.string.drive_level_set, Toast.LENGTH_SHORT).show()
                    }
                }
                .show()
            true
        }
    }

    override fun onUnbind() {
        view?.setOnLongClickListener(null)
        gauge = null
        tvHint = null
    }

    override fun render(t: RideTelemetry) {
        val s = DriveEstimator.state.value
        // The hint is about the mount, which only roll needs: pitch comes from the road grade.
        val ready = s.rollReady
        dispPitch = ease(dispPitch, if (s.pitchReady) s.pitchDeg else 0f, t.frameDtSec, TAU_ANGLE)
        dispRoll = ease(dispRoll, if (s.rollReady) s.rollDeg else 0f, t.frameDtSec, TAU_ANGLE)

        gauge?.let {
            it.pitchReady = s.pitchReady
            it.rollReady = s.rollReady
            it.pitch = dispPitch
            it.roll = dispRoll
            it.maxPitchUp = s.maxPitchUpDeg
            it.maxPitchDown = s.maxPitchDownDeg
            it.maxRollLeft = s.maxRollLeftDeg
            it.maxRollRight = s.maxRollRightDeg
        }
        if (ready != lastReady) {
            lastReady = ready
            tvHint?.visibility = if (ready) View.GONE else View.VISIBLE
        }
    }

    companion object {
        const val ID = "attitude"
        private const val TAU_ANGLE = 0.15f
    }
}
