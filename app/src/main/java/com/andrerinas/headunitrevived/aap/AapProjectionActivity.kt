package com.andrerinas.headunitrevived.aap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.protocol.messages.TouchEvent
import com.andrerinas.headunitrevived.aap.protocol.messages.VideoFocusEvent
import com.andrerinas.headunitrevived.app.SurfaceActivity
import com.andrerinas.headunitrevived.connection.CommManager
import com.andrerinas.headunitrevived.contract.KeyIntent
import kotlinx.coroutines.launch
import com.andrerinas.headunitrevived.decoder.SoftwareYuvFrameSink
import com.andrerinas.headunitrevived.decoder.VideoDecoder
import com.andrerinas.headunitrevived.decoder.VideoDimensionsListener
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.IntentFilters
import com.andrerinas.headunitrevived.view.IProjectionView
import com.andrerinas.headunitrevived.view.GlProjectionView
import com.andrerinas.headunitrevived.view.ProjectionView
import com.andrerinas.headunitrevived.view.TextureProjectionView
import com.andrerinas.headunitrevived.utils.Settings
import com.andrerinas.headunitrevived.view.OverlayTouchView
import com.andrerinas.headunitrevived.utils.HeadUnitScreenConfig
import com.andrerinas.headunitrevived.utils.SystemUI
import com.andrerinas.headunitrevived.aap.AapService
import android.content.IntentFilter
import com.andrerinas.headunitrevived.view.ProjectionViewScaler
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.widget.ImageView
import android.widget.VideoView
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class AapProjectionActivity : SurfaceActivity(), IProjectionView.Callbacks, VideoDimensionsListener {

    private enum class OverlayState { STARTING, RECONNECTING, HIDDEN }

    private lateinit var projectionView: IProjectionView
    private val videoDecoder: VideoDecoder by lazy { App.provide(this).videoDecoder }
    private val settings: Settings by lazy { Settings(this) }
    private val cachedKeyCodes: Map<Int, Int> by lazy { settings.keyCodes }
    private var isSurfaceSet = false
    private var overlayState = OverlayState.STARTING
    private val watchdogHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Ken Burns scale animation applied to a static image loading screen.
     * Stored in a field so it can be cancelled when the loading overlay is
     * torn down or the activity is destroyed — otherwise the infinite-repeat
     * animator keeps consuming frame callbacks even when the view is gone.
     */
    private var kenBurnsAnimator: ObjectAnimator? = null

    private var initialX = 0f
    private var initialY = 0f
    private var isPotentialGesture = false

    // Activity-local override for fullscreen mode. If non-null, setFullscreen() will use this
    // instead of persisting to Settings. This keeps toggles local to the Activity lifecycle.
    private var activityFullscreenOverride: Settings.FullscreenMode? = null
    private var fpsTextView: TextView? = null
    private var touchOverlayView: OverlayTouchView? = null
    private var currentFps: Int? = null
    private val performanceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val performanceSampler = PerformanceSampler()
    private val performanceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PerformanceSampler").apply {
            priority = Thread.MIN_PRIORITY
        }
    }
    private val performanceSampleInFlight = AtomicBoolean(false)
    private val performanceOverlayRunnable = object : Runnable {
        override fun run() {
            requestPerformanceOverlayUpdate()
            performanceHandler.postDelayed(this, 1000L)
        }
    }

    private var isOrientationReceiverRegistered = false
    private var isNightModeReceiverRegistered = false
    private var isFinishReceiverRegistered = false
    private var isKeyEventReceiverRegistered = false

    private val videoWatchdogRunnable = object : Runnable {
        override fun run() {
            if (!commManager.isConnected) return

            val loadingOverlay = findViewById<View>(R.id.loading_overlay)

            // Frames are flowing — nothing to recover, just clear a stale overlay.
            if (videoDecoder.lastFrameRenderedMs > 0) {
                if (loadingOverlay?.visibility == View.VISIBLE) {
                    AppLog.i("Watchdog: Decoder is already rendering frames. Hiding overlay.")
                    hideLoadingOverlay(loadingOverlay)
                }
                return
            }

            // Connected, but not a single frame has been decoded.
            //
            // This deliberately does NOT depend on the loading overlay being visible.
            // When the session is torn down and re-established while this activity is
            // stopped (head unit locked), the lifecycle-scoped connection observer never
            // runs, so the overlay is never re-shown. Keying recovery off its visibility
            // meant no keyframe was ever requested and the screen stayed frozen on the
            // last pre-disconnect frame indefinitely.
            AppLog.w("Watchdog: No video received yet. Requesting Keyframe (Unsolicited Focus)...")
            commManager.send(VideoFocusEvent(gain = true, unsolicited = true))
            watchdogHandler.postDelayed(this, 1500)
        }
    }
    /**
     * Newest frame timestamp this watchdog has ever seen, for the current session.
     *
     * [VideoDecoder.lastFrameRenderedMs] cannot be used on its own: every decoder restart
     * resets it to 0, and a freeze is exactly what makes the decoder restart. Reading it
     * directly meant a frozen picture kept looking like "the first frame hasn't arrived yet",
     * so the gap below restarted from zero on every restart and never grew. Keeping our own
     * high-water mark makes it keep growing across restarts. Cleared by a new session.
     */
    private var lastFrameSeenMs = 0L
    /** Rate-limits the recovery keyframe requests made by [reconnectingWatchdog]. */
    private var lastFrozenKeyframeRequestMs = 0L
    /** Set once the watchdog has torn a frozen session down, so it only does so once per freeze. */
    private var frozenTeardownFired = false
    /** Previous [VideoStreamStats] sample, so each tick can report what arrived since. */
    private var lastStatsTotal = 0L
    private var lastStatsVideo = 0L
    private var lastStatsVideoBytes = 0L

    /**
     * Watches frame flow for the whole session, reports what is arriving while it is stopped,
     * and escalates until the picture comes back.
     *
     * The counters ([VideoStreamStats]) stay because they are what identifies the failure: they
     * separate "the phone stopped sending video" from "the read loop went silent" from "packets
     * arrive but nothing decodes", which look identical otherwise.
     *
     * The escalation is back after an observation round, and on evidence rather than on a guess.
     * Two captured freezes showed the phone stays reachable and willing the whole time — it just
     * has to be asked. In one, a keyframe request brought the stream straight back; in the other,
     * the phone answered every focus message within 600ms by restarting its video session, while
     * nothing asked it to for 39 seconds and the rider ended up intervening by hand. The decoder
     * cannot cover this on its own: its restart only runs when the next packet arrives, and a
     * silent phone is precisely the case where none does.
     *
     * Checked every 2s against the age of the last rendered frame:
     * - 5s — request a keyframe, and log what arrived since the last tick.
     * - 10s — show the reconnecting overlay, so the freeze is visible.
     * - 25s — drop the session and let auto-reconnect rebuild it. Nothing has been drawn for 25
     *   seconds, so there is no working session left to protect.
     */
    private val reconnectingWatchdog = object : Runnable {
        override fun run() {
            // Runs for the whole session, not just the handshake: by the time this is first
            // posted the state has usually already advanced to TransportStarted.
            if (!commManager.isConnected) return

            // High-water mark, not the raw value: a decoder restart zeroes it (see field doc).
            val lastFrame = videoDecoder.lastFrameRenderedMs
            if (lastFrame > lastFrameSeenMs) lastFrameSeenMs = lastFrame

            val total = VideoStreamStats.totalMessages
            val video = VideoStreamStats.videoMessages
            val videoBytes = VideoStreamStats.videoBytes
            val dTotal = total - lastStatsTotal
            val dVideo = video - lastStatsVideo
            val dBytes = videoBytes - lastStatsVideoBytes
            lastStatsTotal = total
            lastStatsVideo = video
            lastStatsVideoBytes = videoBytes

            if (lastFrameSeenMs == 0L) {
                // First frame hasn't arrived yet — handled by the starting overlay
                lastFrozenKeyframeRequestMs = 0L
                frozenTeardownFired = false
                watchdogHandler.postDelayed(this, 2000)
                return
            }
            val gap = SystemClock.elapsedRealtime() - lastFrameSeenMs

            if (gap > FROZEN_KEYFRAME_MS) {
                AppLog.w(
                    "FREEZE: no frame for ${gap}ms — since last tick: msgs=$dTotal, videoMsgs=$dVideo, " +
                        "videoBytes=$dBytes (totals: $total/$video/$videoBytes)"
                )
            }

            if (gap > FROZEN_TEARDOWN_MS && !frozenTeardownFired) {
                frozenTeardownFired = true
                AppLog.e("FREEZE: no frame for ${gap}ms — dropping the session so it can be re-established.")
                // isUserExit = false is essential: a user exit arms the auto-connect cooldown,
                // which is exactly the recovery being asked for here.
                commManager.disconnect(sendByeBye = false, isUserExit = false)
                watchdogHandler.postDelayed(this, 2000)
                return
            }

            if (gap > FROZEN_KEYFRAME_MS) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastFrozenKeyframeRequestMs > 3000) {
                    lastFrozenKeyframeRequestMs = now
                    // A plain focus gain, the same nudge the startup watchdog uses. Deliberately
                    // not AapTransport's down-then-up focus cycle: dropping focus first makes the
                    // phone tear its video session down and build a new one, which showed up in
                    // the capture as stop/start churn rather than recovery.
                    AppLog.w("FREEZE: no frame for ${gap}ms — requesting a keyframe (unsolicited focus).")
                    commManager.send(VideoFocusEvent(gain = true, unsolicited = true))
                }
            }

            if (overlayState == OverlayState.HIDDEN && gap > 10000) {
                AppLog.w("FREEZE: no frame for ${gap}ms — showing the reconnecting overlay.")
                showReconnectingOverlay()
            } else if (overlayState == OverlayState.RECONNECTING && gap < 2000) {
                lastFrozenKeyframeRequestMs = 0L
                frozenTeardownFired = false
                hideReconnectingOverlay()
            } else if (gap < 2000) {
                lastFrozenKeyframeRequestMs = 0L
                frozenTeardownFired = false
            }
            watchdogHandler.postDelayed(this, 2000)
        }
    }
    private val exitRunnable = Runnable {
        if (commManager.connectionState.value is CommManager.ConnectionState.Disconnected) {
            AppLog.i("AapProjectionActivity: Reconnect timed out (20s). Finishing activity.")
            hideReconnectingOverlay()
            finish()
        }
    }
    private val watchdogRunnable = Runnable {
        if (!isSurfaceSet) {
            AppLog.w("Watchdog: Surface not set after 2s. Checking view state...")
            checkAndForceSurface()
        }
    }
    private fun checkAndForceSurface() {
        AppLog.i("Watchdog: checkAndForceSurface executing...")
        if (projectionView is TextureView) {
            val tv = projectionView as TextureView
            if (tv.isAvailable) {
                AppLog.w("Watchdog: TextureView IS available. Forcing onSurfaceChanged.")
                onSurfaceChanged(android.view.Surface(tv.surfaceTexture), tv.width, tv.height)
            } else {
                AppLog.e("Watchdog: TextureView NOT available. Vis=${tv.visibility}, W=${tv.width}, H=${tv.height}")
            }
        } else if (projectionView is GlProjectionView) {
             val gles = projectionView as GlProjectionView
             if (gles.isSurfaceValid()) {
                 AppLog.w("Watchdog: GlProjectionView IS valid. Forcing onSurfaceChanged.")
                 onSurfaceChanged(gles.getSurface()!!, gles.width, gles.height)
             } else {
                 AppLog.e("Watchdog: GlProjectionView NOT valid.")
             }
        } else if (projectionView is ProjectionView) {
             val sv = projectionView as ProjectionView
             if (sv.holder.surface.isValid) {
                 AppLog.w("Watchdog: SurfaceView IS valid. Forcing onSurfaceChanged.")
                 onSurfaceChanged(sv.holder.surface, sv.width, sv.height)
             } else {
                 AppLog.e("Watchdog: SurfaceView NOT valid.")
             }
        }
    }

    private fun recreateProjectionView() {
        runOnUiThread {
            AppLog.i("Recreating projection view due to settings change...")
            val container = findViewById<FrameLayout>(R.id.container)
            if (::projectionView.isInitialized) {
                videoDecoder.softwareYuvFrameSink = null
                videoDecoder.stop("projectionViewRecreate")
                container.removeView(projectionView as View)
            }
            isSurfaceSet = false
            setupProjectionView()
        }
    }

    private val nightModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val isNight = intent.getBooleanExtra("isNight", false)
            updateDesaturation(isNight)
        }
    }

    private fun updateDesaturation(isNight: Boolean) {
        if (settings.aaMonochromeEnabled && projectionView is GlProjectionView) {
            val level = if (isNight) settings.aaDesaturationLevel / 100f else 0f
            (projectionView as GlProjectionView).setDesaturation(level)
        } else if (projectionView is GlProjectionView) {
            (projectionView as GlProjectionView).setDesaturation(0f)
        }
    }

    private val orientationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AapService.ACTION_ORIENTATION_CHANGED) {
                AppLog.i("AapProjectionActivity: Orientation change broadcast received. Updating.")
                applyOrientationSettings()
            }
        }
    }

    private val keyEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val event: KeyEvent? = IntentCompat.getParcelableExtra(intent, KeyIntent.extraEvent, KeyEvent::class.java)
            event?.let {
                onKeyEvent(it.keyCode, it.action == KeyEvent.ACTION_DOWN)
            }
        }
    }

    private val finishReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.andrerinas.headunitrevived.ACTION_FINISH_ACTIVITIES") {
                AppLog.i("AapProjectionActivity: Received finish request. Closing.")
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyOrientationSettings()
        super.onCreate(savedInstanceState)


        setContentView(R.layout.activity_headunit)

        if (settings.showFpsCounter) {
            setupFpsCounter()
        }

        videoDecoder.dimensionsListener = this

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showExitDialog()
            }
        })

        var isFirstEmission = true
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                commManager.connectionState.collect { state ->
                    val first = isFirstEmission
                    isFirstEmission = false

                    if (first && state is CommManager.ConnectionState.Disconnected) {
                        AppLog.i("AapProjectionActivity: Ignoring initial Disconnected state from StateFlow replay.")
                        return@collect
                    }

                    when (state) {
                        is CommManager.ConnectionState.Disconnected -> {
                            watchdogHandler.removeCallbacksAndMessages(null)
                            if (!state.isClean && !state.isUserExit) {
                                AppLog.w("AapProjectionActivity: Disconnected unexpectedly.")
                                Toast.makeText(this@AapProjectionActivity, getString(R.string.wifi_disconnect_toast), Toast.LENGTH_LONG).show()
                            }
                            // Only finish immediately if the user explicitly exited, it was a clean close, or killOnDisconnect is enabled.
                            if (state.isUserExit || state.isClean || settings.killOnDisconnect) {
                                AppLog.i("AapProjectionActivity: Finishing because state isUserExit=${state.isUserExit}, isClean=${state.isClean}, killOnDisconnect=${settings.killOnDisconnect}")
                                hideReconnectingOverlay()
                                finish()
                            } else {
                                // For unexpected disconnects (especially Wireless), show the reconnecting overlay immediately
                                // and wait for the connection to recover before dropping back to the home screen.
                                // The wireless grace window is deliberately long (60s): on a motorcycle the phone and
                                // headunit briefly leave BT/Wi-Fi range (fuel stops, obstructions, approaching the bike),
                                // and returning to range triggers a Wi-Fi Direct group re-formation that can take ~30-45s
                                // of connect/drop/reconnect flapping before it stabilises. Keeping the projection alive
                                // through that window lets it recover on its own instead of finishing and forcing a
                                // manual reconnect. This does NOT slow the reconnect itself — recovery still hides the
                                // overlay the instant the first video frame arrives.
                                val timeoutMs = if (settings.lastConnectionType == Settings.CONNECTION_TYPE_USB) 8000L else 60000L
                                AppLog.i("AapProjectionActivity: Unexpected disconnect. Showing reconnecting overlay and waiting up to ${timeoutMs / 1000}s for recovery.")
                                showReconnectingOverlay()

                                // Re-initialize the first frame listener to hide the reconnecting overlay when video starts flowing
                                videoDecoder.onFirstFrameListener = {
                                    runOnUiThread {
                                        hideReconnectingOverlay()
                                    }
                                }

                                watchdogHandler.removeCallbacks(exitRunnable)
                                watchdogHandler.postDelayed(exitRunnable, timeoutMs)
                            }
                        }
                        is CommManager.ConnectionState.HandshakeComplete -> {
                            watchdogHandler.removeCallbacks(exitRunnable)

                            // Restart the video watchdog so it can request keyframes for the new session
                            watchdogHandler.removeCallbacks(videoWatchdogRunnable)
                            watchdogHandler.postDelayed(videoWatchdogRunnable, 1000)

                            // Re-arm the frame-flow watchdog too. It stops rescheduling once the
                            // connection drops, and the activity survives a reconnect without ever
                            // seeing onResume — so without this it would stay dead for the rest of
                            // the session after the first recovery.
                            lastFrameSeenMs = 0L
                            lastFrozenKeyframeRequestMs = 0L
                            frozenTeardownFired = false
                            lastStatsTotal = VideoStreamStats.totalMessages
                            lastStatsVideo = VideoStreamStats.videoMessages
                            lastStatsVideoBytes = VideoStreamStats.videoBytes
                            watchdogHandler.removeCallbacks(reconnectingWatchdog)
                            watchdogHandler.postDelayed(reconnectingWatchdog, 5000)

                            // Lock the resolution so that orientation changes don't cause re-negotiation
                            HeadUnitScreenConfig.lockResolution()

                            // Handshake done. If the surface is already ready (e.g. reconnect
                            // while the activity is in the foreground), start reading immediately.
                            // If not, onSurfaceChanged() will call startReading() when the surface
                            // becomes available.
                            if (isSurfaceSet) {
                                AppLog.i("DIAG: Activity observer HandshakeComplete, surface ready -> startReading()")
                                commManager.startReading()
                            } else {
                                AppLog.i("DIAG: Activity observer HandshakeComplete, surface NOT ready (await onSurfaceChanged)")
                            }
                        }
                        is CommManager.ConnectionState.TransportStarted -> {
                            watchdogHandler.removeCallbacks(exitRunnable)
                        }
                        else -> {}
                    }
                }
            }
        }

        ContextCompat.registerReceiver(this, finishReceiver, android.content.IntentFilter("com.andrerinas.headunitrevived.ACTION_FINISH_ACTIVITIES"), ContextCompat.RECEIVER_NOT_EXPORTED)
        isFinishReceiverRegistered = true

        AppLog.i("AapProjectionActivity: onCreate (CEONIX Headunit)")

        val container = findViewById<FrameLayout>(R.id.container)
        setupProjectionView()

        val overlayView = OverlayTouchView(this)
        this.touchOverlayView = overlayView
        overlayView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        overlayView.isFocusable = true
        overlayView.isFocusableInTouchMode = true

        overlayView.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    overlayView.requestFocus()
                }
                sendTouchEvent(event)
                true
            }

        container.addView(overlayView)
        overlayView.requestFocus()
        setFullscreen() // Call setFullscreen here as well

        val loadingOverlay = findViewById<View>(R.id.loading_overlay)

        // [FIX] If we are already connected and frames are flowing (e.g. activity recreation),
        // hide the overlay immediately to prevent the "Android Auto is starting" flicker.
        if (commManager.isConnected && videoDecoder.lastFrameRenderedMs > 0) {
            loadingOverlay?.visibility = View.GONE
            overlayState = OverlayState.HIDDEN
        }

        // Ensure loading overlay is on top of everything
        loadingOverlay?.bringToFront()

        // Set up custom loading screen if configured
        setupCustomLoadingScreen()

        findViewById<Button>(R.id.disconnect_button)?.setOnClickListener {
            commManager.disconnect()
        }

        videoDecoder.onFirstFrameListener = {
            runOnUiThread {
                AppLog.i("DIAG: first video frame rendered (projection visible)")
                hideLoadingOverlay(loadingOverlay)

                // Show one-time gesture hint
                if (!settings.gestureHintShown) {
                    Toast.makeText(this@AapProjectionActivity, R.string.gesture_hint, Toast.LENGTH_LONG).show()
                    settings.gestureHintShown = true
                }
            }
        }

        commManager.onUpdateUiConfigReplyReceived = {
            AppLog.i("[UI_DEBUG_FIX] UpdateUiConfig reply received. AA acknowledged new margins.")
        }
    }

    override fun onPause() {
        isForeground = false
        AppLog.i("AapProjectionActivity: onPause")
        super.onPause()
        // Clear any activity-local fullscreen override when leaving the Activity so
        // the stored settings remain authoritative on next resume.
        activityFullscreenOverride = null
        watchdogHandler.removeCallbacks(watchdogRunnable)
        watchdogHandler.removeCallbacks(videoWatchdogRunnable)
        watchdogHandler.removeCallbacks(reconnectingWatchdog)
        watchdogHandler.removeCallbacks(exitRunnable)
        if (isOrientationReceiverRegistered) {
            unregisterReceiver(orientationReceiver)
            isOrientationReceiverRegistered = false
        }
        if (isNightModeReceiverRegistered) {
            unregisterReceiver(nightModeReceiver)
            isNightModeReceiverRegistered = false
        }
        if (isKeyEventReceiverRegistered) {
            unregisterReceiver(keyEventReceiver)
            isKeyEventReceiverRegistered = false
        }
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        // Projection is in front again (AA tile, notification, or a fresh handshake) — whatever
        // route got us here ends the pause started from the exit menu.
        com.andrerinas.headunitrevived.main.MainActivity.projectionPausedByUser = false
        AppLog.i("AapProjectionActivity: onResume")
        applyStickyOrientation()
        // Remove first: a handshake that completed while this activity was starting has already
        // armed these, and posting the same Runnable twice queues it twice — which showed up as
        // every watchdog line appearing in pairs.
        watchdogHandler.removeCallbacks(watchdogRunnable)
        watchdogHandler.removeCallbacks(videoWatchdogRunnable)
        watchdogHandler.removeCallbacks(reconnectingWatchdog)
        watchdogHandler.postDelayed(watchdogRunnable, 2000)
        watchdogHandler.postDelayed(videoWatchdogRunnable, 3000)
        watchdogHandler.postDelayed(reconnectingWatchdog, 5000)


        if (!isKeyEventReceiverRegistered) {
            ContextCompat.registerReceiver(this, keyEventReceiver, IntentFilters.keyEvent, ContextCompat.RECEIVER_EXPORTED)
            isKeyEventReceiverRegistered = true
        }

        // Register orientation receiver
        ContextCompat.registerReceiver(this, orientationReceiver, IntentFilter(AapService.ACTION_ORIENTATION_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        isOrientationReceiverRegistered = true

        // Register night mode receiver for AA monochrome filter
        ContextCompat.registerReceiver(this, nightModeReceiver, IntentFilter(AapService.ACTION_NIGHT_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        isNightModeReceiverRegistered = true

        // Request current night mode state for initial desaturation
        sendBroadcast(Intent(AapService.ACTION_REQUEST_NIGHT_MODE_UPDATE).apply {
            setPackage(packageName)
        })

        setFullscreen()
    }


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        AppLog.i("AapProjectionActivity: onNewIntent received")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setFullscreen() // Reapply fullscreen mode if window gains focus
            touchOverlayView?.requestFocus()
        }
    }

    private fun showReconnectingOverlay() {
        AppLog.i("Showing reconnecting overlay")
        overlayState = OverlayState.RECONNECTING
        val overlay = findViewById<View>(R.id.loading_overlay) ?: return

        // Ensure default content is shown, custom media is hidden
        findViewById<View>(R.id.loading_default_content)?.visibility = View.VISIBLE
        findViewById<View>(R.id.loading_custom_image)?.visibility = View.GONE
        findViewById<View>(R.id.loading_custom_text_overlay)?.visibility = View.GONE
        stopCustomLoadingMedia()
        findViewById<View>(R.id.loading_custom_video)?.visibility = View.GONE
        overlay.setBackgroundColor(Color.parseColor("#CC000000"))

        val title = findViewById<TextView>(R.id.overlay_text)
        val detail = findViewById<TextView>(R.id.overlay_detail)
        val button = findViewById<Button>(R.id.disconnect_button)
        overlay.visibility = View.VISIBLE
        title?.text = getString(R.string.connection_interrupted)
        detail?.text = getString(R.string.connection_interrupted_detail)
        detail?.visibility = View.VISIBLE
        button?.visibility = View.VISIBLE
    }

    private fun hideReconnectingOverlay() {
        AppLog.i("Hiding reconnecting overlay — frames resumed")
        overlayState = OverlayState.HIDDEN
        val overlay = findViewById<View>(R.id.loading_overlay) ?: return
        val detail = findViewById<TextView>(R.id.overlay_detail)
        val button = findViewById<Button>(R.id.disconnect_button)
        overlay.visibility = View.GONE
        detail?.visibility = View.GONE
        button?.visibility = View.GONE
        stopCustomLoadingMedia()
        touchOverlayView?.requestFocus()
    }

    private fun setupCustomLoadingScreen() {
        // Apply any context-specific status text handed over by MainActivity
        // (e.g. "Connecting to Pixel 8…") to BOTH the default-content text and
        // the custom-media text overlay. Done before the early-return paths so
        // the override applies whether or not custom media is configured. Read
        // once and cleared so the value can't leak into a later connection.
        val handover = pendingStatusText
        pendingStatusText = null
        if (handover != null) {
            findViewById<TextView>(R.id.overlay_text)?.text = handover
            findViewById<TextView>(R.id.loading_custom_text)?.text = handover
        }

        val mediaPath = settings.loadingScreenMediaPath
        val mediaType = settings.loadingScreenMediaType
        if (mediaPath.isEmpty() || mediaType.isEmpty()) return

        val file = File(mediaPath)
        if (!file.exists()) {
            settings.loadingScreenMediaPath = ""
            settings.loadingScreenMediaType = ""
            return
        }

        val defaultContent = findViewById<View>(R.id.loading_default_content)
        val customTextOverlay = findViewById<View>(R.id.loading_custom_text_overlay)
        val customImage = findViewById<ImageView>(R.id.loading_custom_image)
        val customVideo = findViewById<VideoView>(R.id.loading_custom_video)
        val overlay = findViewById<View>(R.id.loading_overlay)

        // Always hide the default content when custom media is active
        defaultContent?.visibility = View.GONE
        overlay?.setBackgroundColor(Color.BLACK)

        // Show the dedicated custom text overlay if the user wants status text
        if (settings.loadingScreenShowText) {
            customTextOverlay?.visibility = View.VISIBLE
        }

        val keepRatio = settings.loadingScreenKeepAspectRatio
        val scalePercent = settings.loadingScreenScalePercent
        val scale = scalePercent / 100f

        val ov = overlay
        val img = customImage
        if (ov != null && img != null) {
            ov.post {
                val cw = ov.width
                val ch = ov.height
                if (cw > 0 && ch > 0) {
                    val lp = img.layoutParams as? FrameLayout.LayoutParams
                    if (lp != null) {
                        lp.width = (cw * scale).toInt()
                        lp.height = (ch * scale).toInt()
                        lp.gravity = android.view.Gravity.CENTER
                        img.layoutParams = lp
                    }
                }
            }
        }
        customImage?.scaleType = if (keepRatio) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.FIT_XY

        try {
            when (mediaType) {
                "image" -> {
                    customImage?.visibility = View.VISIBLE
                    customImage?.let { Glide.with(this).load(file).into(it) }
                    if (keepRatio) {
                        customImage?.let { imageView ->
                            kenBurnsAnimator?.cancel()
                            val scaleAnim = ObjectAnimator.ofPropertyValuesHolder(
                                imageView,
                                PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.05f),
                                PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.05f)
                            )
                            scaleAnim.duration = 8000
                            scaleAnim.repeatMode = ObjectAnimator.REVERSE
                            scaleAnim.repeatCount = ObjectAnimator.INFINITE
                            scaleAnim.start()
                            kenBurnsAnimator = scaleAnim
                        }
                    }
                }
                "gif" -> {
                    customImage?.visibility = View.VISIBLE
                    customImage?.let { Glide.with(this).asGif().load(file).into(it) }
                }
                "video" -> {
                    customVideo?.visibility = View.VISIBLE
                    customVideo?.setVideoPath(file.absolutePath)
                    customVideo?.setOnPreparedListener { mp ->
                        mp.isLooping = settings.loadingScreenLoopVideo
                        mp.setVolume(0f, 0f)

                        try {
                            val vw = mp.videoWidth
                            val vh = mp.videoHeight
                            val ov = overlay
                            val cv = customVideo
                            if (ov != null && cv != null) {
                                val cw = ov.width
                                val ch = ov.height
                                if (cw > 0 && ch > 0) {
                                    val lp = cv.layoutParams as FrameLayout.LayoutParams
                                    if (keepRatio && vw > 0 && vh > 0) {
                                        val videoRatio = vw.toFloat() / vh
                                        val containerRatio = cw.toFloat() / ch
                                        val baseWidth: Int
                                        val baseHeight: Int
                                        if (videoRatio > containerRatio) {
                                            baseWidth = cw
                                            baseHeight = (cw / videoRatio).toInt()
                                        } else {
                                            baseHeight = ch
                                            baseWidth = (ch * videoRatio).toInt()
                                        }
                                        lp.width = (baseWidth * scale).toInt()
                                        lp.height = (baseHeight * scale).toInt()
                                    } else {
                                        lp.width = (cw * scale).toInt()
                                        lp.height = (ch * scale).toInt()
                                    }
                                    lp.gravity = android.view.Gravity.CENTER
                                    cv.layoutParams = lp
                                }
                            }
                        } catch (e: Exception) {
                            AppLog.w("Could not resize video: ${e.message}")
                        }
                    }
                    customVideo?.setOnErrorListener { _, _, _ ->
                        AppLog.e("Error playing custom loading video")
                        fallbackToDefaultOverlay()
                        true
                    }
                    customVideo?.start()
                }
                else -> return
            }
        } catch (e: Exception) {
            AppLog.e("Failed to load custom loading screen: ${e.message}")
            fallbackToDefaultOverlay()
        }
    }

    private fun hideLoadingOverlay(loadingOverlay: View?) {
        overlayState = OverlayState.HIDDEN
        AppLog.i("Hiding loading overlay after first video frame")

        // CRITICAL: Stop custom video FIRST — VideoView/SurfaceView has its own
        // rendering layer that ignores parent alpha animations and can stay visible
        // even when the parent is animated to alpha=0
        stopCustomLoadingMedia()
        findViewById<View>(R.id.loading_custom_video)?.visibility = View.GONE
        findViewById<View>(R.id.loading_custom_image)?.visibility = View.GONE
        findViewById<View>(R.id.loading_custom_text_overlay)?.visibility = View.GONE

        // Now hide the overlay — if no custom video, do a smooth fade
        val hasCustomVideo = settings.loadingScreenMediaType == "video"
        if (hasCustomVideo) {
            // Direct hide — animation won't work with SurfaceView
            loadingOverlay?.visibility = View.GONE
            touchOverlayView?.requestFocus()
        } else {
            // Smooth fade for images/GIFs
            loadingOverlay?.animate()
                ?.alpha(0f)
                ?.setDuration(300)
                ?.withEndAction {
                    loadingOverlay?.visibility = View.GONE
                    loadingOverlay?.alpha = 1f
                    touchOverlayView?.requestFocus()
                }?.start()
                ?: run { 
                    loadingOverlay?.visibility = View.GONE 
                    touchOverlayView?.requestFocus()
                }
        }
        touchOverlayView?.requestFocus()
    }

    private fun fallbackToDefaultOverlay() {
        findViewById<View>(R.id.loading_custom_image)?.visibility = View.GONE
        stopCustomLoadingMedia()
        findViewById<View>(R.id.loading_custom_video)?.visibility = View.GONE
        findViewById<View>(R.id.loading_custom_text_overlay)?.visibility = View.GONE
        findViewById<View>(R.id.loading_default_content)?.visibility = View.VISIBLE
        findViewById<View>(R.id.loading_overlay)?.setBackgroundColor(Color.parseColor("#CC000000"))
    }

    private fun stopCustomLoadingMedia() {
        kenBurnsAnimator?.cancel()
        kenBurnsAnimator = null
        findViewById<VideoView>(R.id.loading_custom_video)?.let {
            try {
                if (it.isPlaying) it.stopPlayback()
                it.suspend()
            } catch (_: Exception) {}
        }
    }

    private fun setFullscreen() {
        val container = findViewById<View>(R.id.container)

        // Use activity-local override if present, otherwise use stored settings
        val mode = activityFullscreenOverride ?: settings.fullscreenMode

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT && mode != Settings.FullscreenMode.NONE) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }

        SystemUI.apply(window, container, mode) {
            if (::projectionView.isInitialized) {
                ProjectionViewScaler.updateScale(projectionView as View, videoDecoder.videoWidth, videoDecoder.videoHeight)
            }
        }

        // Workaround for API < 19 (Jelly Bean) where Sticky Immersive Mode doesn't exist.
        // If bars appear (e.g. on touch), hide them again after a delay.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT && mode != Settings.FullscreenMode.NONE) {
            window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
                if ((visibility and View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                    // Bars are visible. Hide them again.
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        val effectiveMode = activityFullscreenOverride ?: settings.fullscreenMode
                        SystemUI.apply(window, container, effectiveMode) {
                            if (::projectionView.isInitialized) {
                                ProjectionViewScaler.updateScale(projectionView as View, videoDecoder.videoWidth, videoDecoder.videoHeight)
                            }
                        }
                    }, 2000)
                }
            }
        }
    }

    /**
     * Back-press menu shown over the AA projection. Two large, glove-friendly actions laid out
     * horizontally: Exit (stop the connection) and Pause (move to background).
     */
    private fun showExitDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_exit_actions, null)
        val dialog = MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
            .setTitle(R.string.exit_dialog_title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .create()

        view.findViewById<View>(R.id.action_exit).setOnClickListener {
            dialog.dismiss()
            commManager.disconnect(sendByeBye = true)
            finish()
        }
        view.findViewById<View>(R.id.action_pause).setOnClickListener {
            dialog.dismiss()
            moveToBackground()
        }
        dialog.show()
        // Shrink the dialog window to hug its content (removes the wide empty side margins).
        // This also drops the framework's fit-to-screen clamping, so the content itself has to be
        // able to fit: dialog_exit_actions.xml sizes its icons from @dimen (compact by default,
        // full size only from values-h350dp) and wraps everything in a ScrollView as a backstop.
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    /**
     * Pause: leave the projection screen but keep the Android Auto session running (no
     * disconnect — that is what Exit is for), and land on the CEONIX Headunit home screen.
     *
     * Targets [MainActivity] explicitly rather than firing ACTION_MAIN/CATEGORY_HOME. That
     * intent means "go to whatever the current HOME app is": with launcher mode on it resolves
     * back to MainActivity, but with launcher mode off it hands the screen to the phone's own
     * launcher — the app disappeared instead of returning to its home screen. The destination is
     * the same activity in both modes, so one explicit route covers both.
     *
     * REORDER_TO_FRONT|SINGLE_TOP, not NEW_TASK|CLEAR_TOP: MainActivity is singleTask and sits
     * directly below this activity in the same task, so these flags surface it without tearing
     * anything down (CLEAR_TOP is the pattern that closed the Settings screen in v1.0.95/96).
     *
     * The paused flag is set BEFORE starting the activity: both MainActivity.onCreate and
     * onResume forward to projection while a session is live, and only `projectionPausedByUser`
     * holds them back — set it after and Pause bounces straight back into projection. Cleared
     * only when the user really returns to projection (onResume) or the session drops.
     */
    private fun moveToBackground() {
        com.andrerinas.headunitrevived.main.MainActivity.projectionPausedByUser = true
        AppLog.i("AapProjectionActivity: Pause - returning to the CEONIX Headunit home screen")
        val backToHomeScreen = Intent(this, com.andrerinas.headunitrevived.main.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(com.andrerinas.headunitrevived.main.MainActivity.EXTRA_LAUNCH_SOURCE, "Pause")
        }
        startActivity(backToHomeScreen)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        App.isPiPActive = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            // Hide UI elements during PiP (like FPS counter, loading overlay)
            findViewById<View>(R.id.loading_overlay)?.visibility = View.GONE
            stopCustomLoadingMedia()
            fpsTextView?.visibility = View.GONE
        } else {
            // Restore UI if needed
            fpsTextView?.visibility = if (settings.showFpsCounter) View.VISIBLE else View.GONE
            setFullscreen()
        }
    }

    override fun onUserLeaveHint() {
        // Optional: Auto-enter PiP if user presses home

        // For now, we only enter via dialog as requested.
        super.onUserLeaveHint()
    }

    private val commManager get() = App.provide(this).commManager

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 1. 2-finger swipe detection from the left or right edge (to open exit menu or toggle fullscreen)
        if (ev.pointerCount == 2) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    initialX = ev.getX(0)
                    initialY = ev.getY(0)
                    isPotentialGesture = initialX < 100 || initialX > HeadUnitScreenConfig.getUsableWidth() - 100
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isPotentialGesture) {
                        val deltaX = ev.getX(0) - initialX
                        val deltaY = abs(ev.getY(0) - initialY)
                        if (deltaX > 200 && deltaY < 100) {
                            isPotentialGesture = false
                            showExitDialog()
                            return true // Consume
                        }

                        if (deltaX < -200 && deltaY < 100) {
                            isPotentialGesture = false
                            val currentEffective = activityFullscreenOverride ?: settings.fullscreenMode
                            if (currentEffective != Settings.FullscreenMode.NONE) {
                                activityFullscreenOverride = Settings.FullscreenMode.NONE
                                setFullscreen()
                            } else {
                                activityFullscreenOverride = null
                                setFullscreen()
                            }
                            return true // Consume
                        }
                    }
                }
            }
        }

        // 2. Legacy Touch handling for older devices (API < 19)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            sendTouchEvent(ev)
        }

        return super.dispatchTouchEvent(ev)
    }

    override fun onSurfaceCreated(surface: android.view.Surface) {
        AppLog.i("[UI_DEBUG] [AapProjectionActivity] onSurfaceCreated")
        // Decoder configuration is now in onSurfaceChanged
    }

    override fun onSurfaceChanged(surface: android.view.Surface, width: Int, height: Int) {
        AppLog.i("[UI_DEBUG] [AapProjectionActivity] onSurfaceChanged. Actual surface dimensions: width=$width, height=$height")
        isSurfaceSet = true

        videoDecoder.setSurface(surface)

        // --- Surface Mismatch Detection ---
        // Compare actual surface dimensions with what HeadUnitScreenConfig negotiated.
        // If they differ (e.g. system bars appeared/disappeared), update margins.
        val prevUsableW = HeadUnitScreenConfig.getUsableWidth()
        val prevUsableH = HeadUnitScreenConfig.getUsableHeight()

        if (HeadUnitScreenConfig.updateSurfaceDimensions(width, height)) {
            AppLog.i("[UI_DEBUG_FIX] Surface mismatch! Expected: ${prevUsableW}x${prevUsableH}, Actual: ${width}x${height}")

            // Cache the real surface size for next session
            settings.cachedSurfaceWidth = width
            settings.cachedSurfaceHeight = height
            settings.cachedSurfaceSettingsHash = HeadUnitScreenConfig.computeSettingsHash(settings)

            if (commManager.connectionState.value is CommManager.ConnectionState.TransportStarted) {
                // AA is already running → send corrected per-side margins dynamically
                commManager.sendUpdateUiConfigRequest(
                    HeadUnitScreenConfig.getLeftMargin(),
                    HeadUnitScreenConfig.getTopMargin(),
                    HeadUnitScreenConfig.getRightMargin(),
                    HeadUnitScreenConfig.getBottomMargin()
                )
                AppLog.i("[UI_DEBUG_FIX] AA is already running, send corrected via sendUpdateUiConfigRequest")
            }
            // If transport not started yet, ServiceDiscoveryResponse will use the corrected values automatically.
        }

        when (commManager.connectionState.value) {
            is CommManager.ConnectionState.Connected -> {
                // AapService should have started the handshake already, but as a fallback
                // (e.g. service restarted) kick it off here. The HandshakeComplete observer
                // will call startReading() once the handshake finishes.
                lifecycleScope.launch { commManager.startHandshake() }
            }
            is CommManager.ConnectionState.StartingTransport -> {
                // Handshake is in progress. The HandshakeComplete observer will call
                // startReading() when it finishes.
            }
            is CommManager.ConnectionState.HandshakeComplete -> {
                // Handshake already done before surface was ready — start reading now.
                AppLog.i("DIAG: onSurfaceChanged w/ HandshakeComplete -> startReading()")
                lifecycleScope.launch { commManager.startReading() }
            }
            is CommManager.ConnectionState.TransportStarted -> {
                // Surface recreated while transport was already running; request a keyframe.
                commManager.send(VideoFocusEvent(gain = true, unsolicited = true))
            }
            else -> {
                commManager.send(VideoFocusEvent(gain = true, unsolicited = false))
            }
        }

        // Explicitly check and set video dimensions if already known by the decoder
        // This handles cases where the activity is recreated but the decoder already has dimensions
        val currentVideoWidth = videoDecoder.videoWidth
        val currentVideoHeight = videoDecoder.videoHeight

        if (currentVideoWidth > 0 && currentVideoHeight > 0) {
            AppLog.i("[AapProjectionActivity] Decoder already has dimensions: ${currentVideoWidth}x$currentVideoHeight. Applying to view.")
            runOnUiThread {
                projectionView.setVideoSize(currentVideoWidth, currentVideoHeight)
                ProjectionViewScaler.updateScale(projectionView as View, currentVideoWidth, currentVideoHeight)
            }
        }
    }

    override fun onSurfaceDestroyed(surface: android.view.Surface) {
        AppLog.i("SurfaceCallback: onSurfaceDestroyed. Surface: $surface")
        isSurfaceSet = false
        commManager.send(VideoFocusEvent(gain = false, unsolicited = false))
        videoDecoder.stop("surfaceDestroyed")
    }


    override fun onVideoDimensionsChanged(width: Int, height: Int) {
        AppLog.i("[AapProjectionActivity] Received video dimensions: ${width}x$height")
        runOnUiThread {
            projectionView.setVideoSize(width, height)
            ProjectionViewScaler.updateScale(projectionView as View, width, height)
        }
    }

    private fun sendTouchEvent(event: MotionEvent) {
        val action = TouchEvent.motionEventToAction(event) ?: return
        val ts = SystemClock.elapsedRealtime()

        val videoW = HeadUnitScreenConfig.getNegotiatedWidth()
        val videoH = HeadUnitScreenConfig.getNegotiatedHeight()

        if (videoW <= 0 || videoH <= 0 || projectionView !is View) {
            AppLog.w("sendTouchEvent: Ignoring touch, screen config or view not ready.")
            return
        }

        val view = projectionView as View
        // Use the container's "Anchor" dimensions (full touch surface) as the reference,
        // not the potentially resized projectionView's dimensions.
        val viewW = HeadUnitScreenConfig.getUsableWidth().toFloat()
        val viewH = HeadUnitScreenConfig.getUsableHeight().toFloat()

        if (viewW <= 0 || viewH <= 0) return

        val marginW = HeadUnitScreenConfig.getWidthMargin().toFloat()
        val marginH = HeadUnitScreenConfig.getHeightMargin().toFloat()

        val uiW = videoW - marginW
        val uiH = videoH - marginH

        // Logic check: When forcedScale is active, the visual behavior of 'stretchToFill'
        // is inverted (True = Aspect Ratio Centered, False = Stretched to Screen).
        // We adjust the touch mapping to match this visual reality.
        val isStretch = if (HeadUnitScreenConfig.forcedScale) {
            !settings.stretchToFill
        } else {
            settings.stretchToFill
        }

        val pointerData = mutableListOf<Triple<Int, Int, Int>>()
        repeat(event.pointerCount) { pointerIndex ->
            val pointerId = event.getPointerId(pointerIndex)
            val px = event.getX(pointerIndex)
            val py = event.getY(pointerIndex)

            var videoX = 0f
            var videoY = 0f

            if (isStretch) {
                videoX = (px / viewW) * uiW
                videoY = (py / viewH) * uiH
            } else {
                val uiRatio = uiW / uiH
                val viewRatio = viewW / viewH

                var displayedUiW = viewW
                var displayedUiH = viewH

                if (viewRatio > uiRatio) {
                    displayedUiW = viewH * uiRatio
                } else {
                    displayedUiH = viewW / uiRatio
                }

                val uiLeft = (viewW - displayedUiW) / 2f
                val uiTop = (viewH - displayedUiH) / 2f

                val localX = px - uiLeft
                val localY = py - uiTop

                videoX = (localX / displayedUiW) * uiW
                videoY = (localY / displayedUiH) * uiH
            }

            // Clamp to negotiated bounds to prevent out-of-bounds touches
            val correctedX = videoX.toInt().coerceIn(0, videoW)
            val correctedY = videoY.toInt().coerceIn(0, videoH)

            pointerData.add(Triple(pointerId, correctedX, correctedY))
        }

        commManager.send(TouchEvent(ts, action, event.actionIndex, pointerData))
    }


    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action = event.action
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
            return super.dispatchKeyEvent(event)
        }

        // 1. Let the system handle volume keys and unmapped back keys.
        // If Back was explicitly learned in Keymap and transport is running,
        // route it through CommManager so it can be remapped and sent to Android Auto.
        if (commManager.connectionState.value is CommManager.ConnectionState.TransportStarted &&
            ProjectionKeyPolicy.shouldRouteBackKeyToProjection(cachedKeyCodes, event.keyCode)) {
            commManager.sendKey(event.keyCode, action == KeyEvent.ACTION_DOWN)
            return true
        }

        if (event.keyCode == KeyEvent.KEYCODE_BACK ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(event)
        }

        // 2. Funnel all other keys to CommManager
        commManager.sendKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN)
        return true
    }

    private fun onKeyEvent(keyCode: Int, isPress: Boolean) {
        // Broadcasts (e.g. from CarKeyReceiver) still use this path.
        commManager.sendKey(keyCode, isPress)
    }

    private fun applyStickyOrientation() {
        if (settings.screenOrientation == Settings.ScreenOrientation.AUTO && HeadUnitScreenConfig.isResolutionLocked) {
            val target = if (HeadUnitScreenConfig.getNegotiatedWidth() > HeadUnitScreenConfig.getNegotiatedHeight()) {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            if (requestedOrientation != target) {
                AppLog.i("[UI_DEBUG] Sticky Orientation: Session active, forcing orientation to $target")
                requestedOrientation = target
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishReceiverRegistered) {
            unregisterReceiver(finishReceiver)
            isFinishReceiverRegistered = false
        }
        if (isKeyEventReceiverRegistered) {
            unregisterReceiver(keyEventReceiver)
            isKeyEventReceiverRegistered = false
        }
        // Defensive cleanup: if the activity is destroyed while the loading
        // overlay is still up (early connection failure, system kill,
        // configuration change before first frame), the VideoView's surface
        // and the Ken Burns animator outlive the view hierarchy briefly.
        // stopCustomLoadingMedia releases both.
        stopCustomLoadingMedia()
        stopPerformanceOverlayUpdates()
        performanceExecutor.shutdownNow()
        AppLog.i("AapProjectionActivity.onDestroy called. isFinishing=$isFinishing")
        App.isPiPActive = false
        videoDecoder.onFpsChanged = null
        videoDecoder.softwareYuvFrameSink = null
        videoDecoder.dimensionsListener = null
    }

    companion object {
        const val EXTRA_FOCUS = "focus"
        @Volatile var isForeground = false

        /**
         * Frame age at which [reconnectingWatchdog] starts reporting what is still arriving and
         * asking the phone for a keyframe.
         */
        private const val FROZEN_KEYFRAME_MS = 5000L
        /** Frame age at which [reconnectingWatchdog] gives up and drops the session. */
        private const val FROZEN_TEARDOWN_MS = 25000L

        /**
         * Optional one-shot override for the loading-screen status text. Set by
         * MainActivity when it begins an auto-connect with a context-specific
         * label (e.g. "Connecting to Pixel 8…" from the Nearby selector). Read
         * and cleared by [setupCustomLoadingScreen] on the next launch so the
         * value can't leak into a subsequent connection attempt.
         */
        @Volatile var pendingStatusText: String? = null

        fun intent(context: Context): Intent {
            val aapIntent = Intent(context, AapProjectionActivity::class.java)
            aapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return aapIntent
        }
    }
    private fun applyOrientationSettings() {
        val screenOrientation = settings.screenOrientation
        if (screenOrientation == Settings.ScreenOrientation.AUTO) {
            applyStickyOrientation()
            if (!HeadUnitScreenConfig.isResolutionLocked) {
                // Initial start: lock to current orientation at launch
                if (Build.VERSION.SDK_INT >= 18) {
                    requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
                } else {
                    requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_NOSENSOR
                }
            }
        } else {
            requestedOrientation = screenOrientation.androidOrientation
        }
    }

    private fun setupProjectionView() {
        val container = findViewById<FrameLayout>(R.id.container)
        val displayMetrics = resources.displayMetrics

        if (settings.viewMode == Settings.ViewMode.TEXTURE) {
            AppLog.i("Using TextureView")
            val textureView = TextureProjectionView(this)
            textureView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            projectionView = textureView
            container.setBackgroundColor(Color.BLACK)
        } else if (settings.viewMode == Settings.ViewMode.GLES) {
            AppLog.i("Using GlProjectionView")
            val glView = GlProjectionView(this)
            glView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            projectionView = glView
            container.setBackgroundColor(Color.BLACK)
        } else {
            AppLog.i("Using SurfaceView")
            projectionView = ProjectionView(this)
            (projectionView as View).layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        // Use the same screen conf for both views for negotiation
        HeadUnitScreenConfig.init(this, displayMetrics, settings)

        val view = projectionView as View
        container.addView(view)
        videoDecoder.softwareYuvFrameSink = projectionView as? SoftwareYuvFrameSink
        if (videoDecoder.softwareYuvFrameSink != null) {
            AppLog.i("Using GLES YUV sink for bundled software HEVC")
        }

        projectionView.addCallback(this)
    }

    private fun setupFpsCounter() {
        val container = findViewById<FrameLayout>(R.id.container)
        fpsTextView = TextView(this).apply {
            setTextColor(Color.YELLOW)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#80000000"))
            setPadding(10, 5, 10, 5)
            text = "FPS: --\nCPU: -- / --\nTemp: --\nFrame: --"
            // Lift it above everything
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = 100f
                translationZ = 100f
            }
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(20, 20, 0, 0)
        }
        container.addView(fpsTextView, params)

        videoDecoder.onFpsChanged = { fps ->
            currentFps = fps
        }
        startPerformanceOverlayUpdates()
    }

    private fun startPerformanceOverlayUpdates() {
        performanceHandler.removeCallbacks(performanceOverlayRunnable)
        performanceOverlayRunnable.run()
    }

    private fun stopPerformanceOverlayUpdates() {
        performanceHandler.removeCallbacks(performanceOverlayRunnable)
    }

    private fun requestPerformanceOverlayUpdate() {
        if (!performanceSampleInFlight.compareAndSet(false, true)) return

        val fpsSnapshot = currentFps
        val lastFrameSnapshot = videoDecoder.lastFrameRenderedMs
        try {
            performanceExecutor.execute {
                try {
                    val text = buildPerformanceOverlayText(fpsSnapshot, lastFrameSnapshot)
                    runOnUiThread {
                        if (!isFinishing && fpsTextView?.visibility == View.VISIBLE) {
                            fpsTextView?.text = text
                        }
                    }
                } catch (e: Exception) {
                    AppLog.w("Performance overlay update failed: ${e.message}")
                } finally {
                    performanceSampleInFlight.set(false)
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            performanceSampleInFlight.set(false)
        }
    }

    private fun buildPerformanceOverlayText(fpsSnapshot: Int?, lastFrameSnapshot: Long): String {
        val metrics = performanceSampler.sample()
        val frameAgeText = if (lastFrameSnapshot > 0L) {
            "${SystemClock.elapsedRealtime() - lastFrameSnapshot}ms"
        } else {
            "--"
        }
        val fpsText = fpsSnapshot?.toString() ?: "--"
        val appCpuText = metrics.appCpuPercent?.let { "${it}%" } ?: "--"
        val totalCpuText = metrics.totalCpuPercent?.let { "${it}%" }
            ?: metrics.loadAverage?.let { String.format(java.util.Locale.US, "%.2f load", it) }
            ?: "--"
        val tempText = metrics.temperatureC?.let { "${it}C" } ?: "--"
        return "FPS: $fpsText\nCPU: app $appCpuText / sys $totalCpuText\nTemp: $tempText\nFrame: $frameAgeText"
    }

    private class PerformanceSampler {
        private data class TotalCpuSnapshot(
            val totalJiffies: Long,
            val idleJiffies: Long
        )

        data class Metrics(
            val appCpuPercent: Int?,
            val totalCpuPercent: Int?,
            val loadAverage: Double?,
            val temperatureC: Int?
        )

        private var previousTotalCpu: TotalCpuSnapshot? = null
        private var previousProcessCpuMs: Long? = null
        private var previousElapsedMs: Long? = null

        fun sample(): Metrics {
            val nowElapsedMs = SystemClock.elapsedRealtime()
            val nowProcessCpuMs = android.os.Process.getElapsedCpuTime()
            val previousProcess = previousProcessCpuMs
            val previousElapsed = previousElapsedMs
            previousProcessCpuMs = nowProcessCpuMs
            previousElapsedMs = nowElapsedMs

            val appCpu = if (previousProcess != null && previousElapsed != null) {
                val cpuDelta = (nowProcessCpuMs - previousProcess).coerceAtLeast(0L)
                val elapsedDelta = (nowElapsedMs - previousElapsed).coerceAtLeast(1L)
                ((cpuDelta.toDouble() / elapsedDelta) * 100.0).toInt().coerceAtLeast(0)
            } else {
                null
            }

            val currentTotalCpu = readTotalCpuSnapshot()
            val previousTotal = previousTotalCpu
            previousTotalCpu = currentTotalCpu
            val totalCpu = if (currentTotalCpu != null && previousTotal != null) {
                val totalDelta = (currentTotalCpu.totalJiffies - previousTotal.totalJiffies).coerceAtLeast(1L)
                val idleDelta = (currentTotalCpu.idleJiffies - previousTotal.idleJiffies).coerceAtLeast(0L)
                (((totalDelta - idleDelta).toDouble() / totalDelta) * 100.0).toInt().coerceIn(0, 100)
            } else {
                null
            }

            return Metrics(appCpu, totalCpu, readLoadAverage(), readTemperatureC())
        }

        private fun readTotalCpuSnapshot(): TotalCpuSnapshot? {
            return try {
                val cpuLine = File("/proc/stat").useLines { lines ->
                    lines.firstOrNull { it.startsWith("cpu ") }
                } ?: return null
                val cpuValues = cpuLine.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
                if (cpuValues.size < 5) return null
                val idle = cpuValues.getOrElse(3) { 0L } + cpuValues.getOrElse(4) { 0L }
                val total = cpuValues.take(8).sum()
                TotalCpuSnapshot(total, idle)
            } catch (e: Exception) {
                null
            }
        }

        private fun readLoadAverage(): Double? {
            return try {
                File("/proc/loadavg")
                    .readText()
                    .trim()
                    .split(Regex("\\s+"))
                    .firstOrNull()
                    ?.toDoubleOrNull()
            } catch (e: Exception) {
                null
            }
        }

        private fun readTemperatureC(): Int? {
            return try {
                val thermalRoot = File("/sys/class/thermal")
                val values = thermalRoot.listFiles()
                    ?.filter { it.name.startsWith("thermal_zone") }
                    ?.mapNotNull { zone ->
                        val raw = zone.resolve("temp").readText().trim().toIntOrNull() ?: return@mapNotNull null
                        when {
                            raw in 10000..125000 -> raw / 1000
                            raw in 10..125 -> raw
                            else -> null
                        }
                    }
                    .orEmpty()
                values.maxOrNull()
            } catch (e: Exception) {
                null
            }
        }
    }
}
