package com.andrerinas.headunitrevived.connection

import android.os.SystemClock

/**
 * Remembers what the head unit last did to invite the phone back, so a reconnect can be
 * attributed rather than guessed at.
 *
 * After a range loss the head unit pokes the phone exactly once — right after the P2P group is
 * rebuilt, which is usually while the rider is still out of range — and then only listens. From
 * that point a reconnect can come from the phone deciding on its own, or from a poke fired by
 * unlocking the screen or tapping the Android Auto card. In the log those look identical: a
 * connection is simply accepted.
 *
 * The distinction is what decides whether anything needs fixing. If the phone reliably comes back
 * by itself, the head unit never needed to poke again. If reconnects only ever follow an unlock
 * or a tap, then on a bike — where the screen stays on and no unlock happens — the rider is doing
 * by hand what the app should be doing on its own.
 *
 * Process-wide and deliberately not plumbed: the writers are the service and a broadcast
 * receiver, the reader is the Bluetooth handshake server, and none of them holds a reference to
 * the others. Purely observational — nothing here changes what the app does.
 */
internal object ReconnectTrigger {
    /** Label of the last invitation the head unit sent, or null if none this process. */
    @Volatile
    private var lastTrigger: String? = null
    @Volatile
    private var lastTriggerMs: Long = 0L

    /** Poke fired once after the P2P group was rebuilt following a disconnect. */
    const val GROUP_REBUILD_POKE = "group-rebuild-poke"
    /** Poke fired because the rider unlocked the head unit screen. */
    const val USER_PRESENT_POKE = "user-present-poke"
    /** Poke fired because the rider tapped the Android Auto card. */
    const val MANUAL_TAP_POKE = "manual-tap-poke"
    /** The phone's Bluetooth came back into range and reconnected. Not a poke — we do not send one. */
    const val BT_ACL_CONNECTED = "bt-acl-connected"

    fun record(trigger: String) {
        lastTrigger = trigger
        lastTriggerMs = SystemClock.elapsedRealtime()
    }

    /** Human-readable age of the last trigger, for a log line at the moment a phone connects. */
    fun describe(): String {
        val trigger = lastTrigger ?: return "none recorded this session"
        val ageMs = SystemClock.elapsedRealtime() - lastTriggerMs
        return "$trigger ${ageMs / 1000}s ago"
    }
}
