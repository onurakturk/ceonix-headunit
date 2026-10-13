package com.andrerinas.headunitrevived.aap

import com.andrerinas.headunitrevived.main.ScreenFocus

/**
 * Decides whether Android Auto is allowed to take the screen right now.
 *
 * [ActivityLaunchPolicy] answers *how* to start the projection activity from the background;
 * this answers *whether* it should be started at all. The rule is one sentence: projection may
 * take the foreground unless the rider is deliberately looking at one of our other screens.
 *
 * Nothing here overrides the guards that were already in place — `App.isPiPActive`,
 * `MainActivity.projectionPausedByUser` and `AapProjectionActivity.isForeground` stay exactly
 * where they are and keep their own meanings. This adds a single extra term at each call site,
 * which is what keeps it revertible in one commit.
 *
 * User-initiated launches never consult this. The Android Auto card on Home, the Ride quick
 * action, the notification taps and the exit dialog call straight through, so "the rider can
 * always get into Android Auto" is structurally true rather than a rule that could be
 * misconfigured.
 */
object ProjectionFocusPolicy {

    /** Why something wants the screen. */
    enum class Trigger {
        /** The rider asked for it. Always granted — listed for symmetry in logs. */
        USER,

        /**
         * A wake-up: cold start, screen on, unlock. The turn-the-key case — granted whenever the
         * rider isn't already somewhere, which after a wake is almost always true.
         */
        IGNITION,

        /**
         * Nobody asked: a handshake completed, an activity resumed, the phone requested the
         * screen over the video channel. This is the class that used to interrupt rides.
         */
        SPONTANEOUS
    }

    data class Decision(val allow: Boolean, val reason: String)

    /**
     * Pure decision, for testing and for callers that already know the screen.
     *
     * @param protectedScreen the screen the rider deliberately chose, or null if there is none.
     */
    fun evaluate(trigger: Trigger, protectedScreen: String?): Decision = when {
        trigger == Trigger.USER -> Decision(true, "the rider asked for it")
        protectedScreen == null -> Decision(true, "nothing of ours is in front")
        else -> Decision(false, "the rider is on $protectedScreen")
    }

    fun evaluate(trigger: Trigger): Decision = evaluate(trigger, ScreenFocus.protectedScreenOrNull())
}
