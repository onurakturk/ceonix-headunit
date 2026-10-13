package com.andrerinas.headunitrevived.aap

import android.os.SystemClock

/**
 * Counters for what is actually arriving from the phone, so a frozen picture can be explained.
 *
 * When the video stops there are three very different causes and, without these, they all look
 * identical from the outside — the connection stays up and nothing is logged either way:
 *
 * | video messages | any messages | meaning                                             |
 * |----------------|--------------|-----------------------------------------------------|
 * | none           | some         | the phone stopped sending video (audio/control still flowing) |
 * | none           | none         | the read loop went silent — link or socket          |
 * | some           | some         | packets arrive but no frame is rendered — decoder    |
 *
 * Read by the frame-flow watchdog in `AapProjectionActivity`, which reports the delta on each
 * tick while frames are missing. Process-wide rather than plumbed through, deliberately: the
 * write sites sit on the hot read path and must stay a single increment, and the reader is an
 * Activity that has no handle on the transport.
 *
 * Monotonic for the life of the process. Consumers compare against their own previous sample
 * instead of resetting, so no writer ever has to coordinate with a reader.
 */
internal object VideoStreamStats {
    /** Every message the handler accepted, on any channel. */
    @Volatile var totalMessages = 0L
    /** Messages on the video channel, whether or not they produced a frame. */
    @Volatile var videoMessages = 0L
    /** Payload bytes seen on the video channel. */
    @Volatile var videoBytes = 0L
    /** `SystemClock.elapsedRealtime()` of the newest video-channel message. */
    @Volatile var lastVideoMessageMs = 0L

    fun onMessage() {
        totalMessages++
    }

    fun onVideoMessage(size: Int) {
        videoMessages++
        videoBytes += size
        lastVideoMessageMs = SystemClock.elapsedRealtime()
    }
}
