package com.andrerinas.headunitrevived.main

import android.app.Activity
import com.andrerinas.headunitrevived.R

/**
 * Remembers what the rider is currently looking at, so Android Auto can tell the difference
 * between an empty screen and a screen someone chose.
 *
 * Android Auto used to take the foreground the instant a session came up, whatever was on
 * screen at the time. On a bike that is exactly wrong: a reconnect after a range loss would
 * throw the rider off the Ride dashboard mid-corner, and coming back from a settings sub-screen
 * would do the same. Projection is now allowed to take the screen only when nothing of ours is
 * deliberately in front of it — see [com.andrerinas.headunitrevived.aap.ProjectionFocusPolicy].
 *
 * "Deliberate" means any screen the rider had to navigate to. Home is not one of them: it is the
 * start destination, where the app lands on its own, and it is where the Android Auto card lives.
 * Keeping Home neutral is what preserves the turn-the-key feel — boot lands on Home and projection
 * takes over from there, as it always has.
 *
 * Process-wide and deliberately not plumbed, in the shape of
 * [com.andrerinas.headunitrevived.connection.ReconnectTrigger]: the writers are [App] and
 * [MainActivity], the readers are the service and a broadcast receiver, and none of them holds a
 * reference to the others. Reads are single-field and advisory — a stale value at worst allows or
 * skips one foreground transition — so plain volatiles are enough and no flow is needed.
 */
internal object ScreenFocus {

    /** Simple class name of whichever of our activities is resumed, or null if none is. */
    @Volatile
    private var foregroundActivity: String? = null

    /** Current destination in [MainActivity]'s nav host. 0 until the first destination change. */
    @Volatile
    private var destinationId: Int = 0

    @Volatile
    private var destinationName: String? = null

    fun onResumed(activity: Activity) {
        foregroundActivity = activity.javaClass.simpleName
    }

    /**
     * Clears only if the paused activity is still the one we recorded. Android resumes the
     * incoming activity before pausing the outgoing one, so an unguarded clear would wipe the
     * name that was just written.
     */
    fun onPaused(activity: Activity) {
        if (foregroundActivity == activity.javaClass.simpleName) {
            foregroundActivity = null
        }
    }

    fun onDestination(id: Int, name: String?) {
        destinationId = id
        destinationName = name
    }

    /**
     * The screen the rider deliberately chose, or null if projection is free to take over.
     *
     * Null when the screen is off, when another app is in front, or when we are sitting on Home —
     * all cases where nothing is being taken away from anyone.
     */
    fun protectedScreenOrNull(): String? = when {
        foregroundActivity == SETTINGS_ACTIVITY -> "Settings"
        foregroundActivity == MAIN_ACTIVITY && isProtectedDestination() ->
            destinationName ?: "screen $destinationId"
        else -> null
    }

    /**
     * Destination-only view, ignoring which activity is resumed.
     *
     * [MainActivity.onResume] has to use this one: `ActivityLifecycleCallbacks.onActivityResumed`
     * is dispatched *after* `onResume()` returns, so during `onResume` the tracker still holds
     * whatever was in front before — usually null, which would read as "free to take over".
     */
    fun isProtectedDestination(): Boolean =
        destinationId != 0 && destinationId != R.id.homeFragment

    /**
     * Whether any screen of ours is resumed.
     *
     * Distinct from [protectedScreenOrNull]: this is true on Home too. Used by the boot/wake
     * MainActivity launch, which has nothing to add when the app is already up and would only
     * tear down whatever is above it.
     */
    fun isAnyScreenForeground(): Boolean = foregroundActivity != null

    /** For log lines that only care about the nav destination, e.g. `rideFragment`. */
    fun describeDestination(): String = destinationName ?: destinationId.toString()

    /** For log lines: `MainActivity/rideFragment`, or `none` when nothing of ours is in front. */
    fun describe(): String {
        val activity = foregroundActivity ?: return "none"
        if (activity != MAIN_ACTIVITY) return activity
        return "$activity/${destinationName ?: destinationId}"
    }

    private const val MAIN_ACTIVITY = "MainActivity"
    private const val SETTINGS_ACTIVITY = "SettingsActivity"
}
