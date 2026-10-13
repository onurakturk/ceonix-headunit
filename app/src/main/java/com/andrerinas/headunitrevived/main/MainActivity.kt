package com.andrerinas.headunitrevived.main

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.AapProjectionActivity
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.app.BaseActivity
import com.andrerinas.headunitrevived.connection.CommManager
import com.andrerinas.headunitrevived.main.permissions.PermissionItem
import com.andrerinas.headunitrevived.utils.AppLog
import android.content.res.Configuration
import com.andrerinas.headunitrevived.utils.Settings
import android.os.SystemClock
import com.andrerinas.headunitrevived.utils.SystemUI
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : BaseActivity() {

    private var lastBackPressTime: Long = 0
    var keyListener: KeyListener? = null

    private var isOrientationReceiverRegistered = false
    private var isFinishReceiverRegistered = false

    private val viewModel: MainViewModel by viewModels()

    /**
     * The nav host's controller, kept so [checkSetupFlow] can look at the current destination
     * without re-finding the fragment on every resume.
     */
    private var navController: androidx.navigation.NavController? = null


    private var autoConnectWatchdog: Job? = null
    private var autoConnectKenBurnsAnim: ObjectAnimator? = null

    /**
     * Visual mode for an in-progress auto-connect attempt. PILL is a small,
     * non-blocking status indicator at the top of the home screen used for
     * fully automatic background attempts so the home buttons stay tappable.
     * OVERLAY is the full-screen custom loading screen used for connections
     * the user explicitly triggered with a button.
     */
    enum class ConnectionUiMode { PILL, OVERLAY }

    private val finishReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.action == "com.andrerinas.headunitrevived.ACTION_FINISH_ACTIVITIES") {
                AppLog.i("MainActivity: Received finish request. Closing.")
                finishAffinity()
            }
        }
    }

    interface KeyListener {
        fun onKeyEvent(event: KeyEvent?): Boolean
    }

    private val orientationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AapService.ACTION_ORIENTATION_CHANGED) {
                AppLog.i("MainActivity: Orientation change broadcast received. Updating.")
                requestedOrientation = Settings(this@MainActivity).screenOrientation.androidOrientation
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = Settings(this).screenOrientation.androidOrientation
        super.onCreate(savedInstanceState)

        logLaunchSource()

        // If an Android Auto session is active, bring the projection activity to front — unless
        // the user paused it from the exit menu (see projectionPausedByUser), in which case Home
        // stays on the home screen until they explicitly go back to projection.
        //
        // Only on a real launch. This is the turn-the-key path: the app started, so go find
        // Android Auto. A recreate is not a launch — the theme toggle on the Ride screen rebuilds
        // this activity through the same onCreate, and without this check a rider changing to
        // night mode was thrown straight into projection. onResume's own guard cannot cover it,
        // because onCreate has already fired the intent by the time it runs.
        val isFreshLaunch = savedInstanceState == null
        AppLog.i("DIAG: MainActivity.onCreate forward-check connected=${App.provide(this).commManager.isConnected} pip=${App.isPiPActive} paused=$projectionPausedByUser freshLaunch=$isFreshLaunch")
        if (!isFreshLaunch && App.provide(this).commManager.isConnected) {
            AppLog.i("FOCUS: onCreate forward suppressed (recreate, not a launch — dest=${ScreenFocus.describeDestination()})")
        }
        if (isFreshLaunch && App.provide(this).commManager.isConnected && !App.isPiPActive && !projectionPausedByUser) {
            AppLog.i("MainActivity: Active session detected in onCreate, bringing projection to front")
            val aapIntent = AapProjectionActivity.intent(this).apply {
                putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(aapIntent)

            // If we are auto-forwarding, hide the splash immediately to avoid flashing it twice.
            // Always a fresh launch here, so the splash is up by definition.
            findViewById<View>(R.id.splash_overlay)?.visibility = View.GONE
        }

        setTheme(R.style.AppTheme)
        val mainSettings = Settings(this)
        val isNightActive = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (mainSettings.appTheme == Settings.AppTheme.EXTREME_DARK ||
            (mainSettings.useExtremeDarkMode && isNightActive)) {
            theme.applyStyle(R.style.ThemeOverlay_ExtremeDark, true)
        }
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val appSettings = Settings(this)
        requestedOrientation = appSettings.screenOrientation.androidOrientation

        // Sync UsbAttachedActivity component state with the listen for USB devices setting.
        // This covers first install, app updates (manifest may reset component state),
        // and ensures the USB system modal only appears when the user has opted in to listen for ALL USB devices.
        lifecycleScope.launch(Dispatchers.IO) {
            Settings.setUsbAttachedActivityEnabled(applicationContext, appSettings.listenForUsbDevices)
        }

        // Start main service immediately to handle connections and wireless server
        val serviceIntent = Intent(this, AapService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)

        setFullscreen()

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.main_content) as androidx.navigation.fragment.NavHostFragment
        val navController = navHostFragment.navController
        this.navController = navController

        // Record where the rider is, so a session coming up in the background knows whether it is
        // allowed to take the screen (see ScreenFocus / ProjectionFocusPolicy). Fires immediately
        // with the current destination, so the tracker is never left at 0 after onCreate.
        navController.addOnDestinationChangedListener { _, destination, _ ->
            val name = try {
                resources.getResourceEntryName(destination.id)
            } catch (e: android.content.res.Resources.NotFoundException) {
                null
            }
            ScreenFocus.onDestination(destination.id, name)
            AppLog.i("FOCUS: screen=${ScreenFocus.describe()}")
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // While the full-screen overlay is up, treat Back as cancel so the
                // user isn't trapped if a manual connection attempt hangs. Pill
                // mode is non-blocking, so Back falls through to its normal
                // navigation behavior there.
                if (autoConnectInProgress && autoConnectMode == ConnectionUiMode.OVERLAY) {
                    cancelAutoConnect()
                    return
                }
                if (navController.navigateUp()) {
                    return
                } else if (Settings(this@MainActivity).useAsLauncher) {
                    // As the home launcher, Back must never finish the app — stay on home.
                    return
                } else if (System.currentTimeMillis() - lastBackPressTime < 2000) {
                    finish()
                } else {
                    lastBackPressTime = System.currentTimeMillis()
                    Toast.makeText(this@MainActivity, R.string.press_back_again_to_exit, Toast.LENGTH_SHORT).show()
                }
            }
        })

        if (savedInstanceState == null && !appSettings.useAsLauncher) {
            val elapsedSinceStart = SystemClock.elapsedRealtime() - App.appStartTime
            val targetTotalDuration = 1200L
            val actualDelay = (targetTotalDuration - elapsedSinceStart).coerceAtLeast(0L)

            showSplashWithDelay(actualDelay)
        } else {
            // Launcher mode: skip the splash so pressing Home feels instant.
            findViewById<View>(R.id.splash_overlay)?.visibility = View.GONE
        }

        // Permissions are no longer blasted from here. They are asked for one card at a time on the
        // permission dashboard, which onResume's setup chain opens when something is missing.
        // A genuinely new activity means a new run, so the screen is offered again — but a theme
        // change also lands here via recreate(), and that must not count (savedInstanceState is
        // non-null there, the same test onCreate's ignition forward already relies on).
        if (savedInstanceState == null) {
            permissionSetupNavigatedThisRun = false
        }
        viewModel.register()
        handleLaunchIntent(intent)
        setupWifiDirectInfo()

        ContextCompat.registerReceiver(
            this, finishReceiver,
            android.content.IntentFilter("com.andrerinas.headunitrevived.ACTION_FINISH_ACTIVITIES"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isFinishReceiverRegistered = true

        // USB auto-attach is the one auto-connect path that pre-launches MainActivity
        // before any state transition occurs. HomeFragment-driven auto-connects will
        // call beginAutoConnect() directly from their entry points.
        if (savedInstanceState == null &&
            intent?.getStringExtra(EXTRA_LAUNCH_SOURCE) == "USB auto-start") {
            beginAutoConnect("USB auto-start", ConnectionUiMode.PILL)
        }

        // Wire cancel affordances. Pill click and overlay cancel button both
        // route through the same cancellation path.
        findViewById<View>(R.id.auto_connect_pill)?.setOnClickListener {
            cancelAutoConnect()
        }
        findViewById<View>(R.id.auto_connect_loading_cancel)?.setOnClickListener {
            cancelAutoConnect()
        }

        observeConnectionStateForOverlay()
    }

    /**
     * Mark that an automatic connection attempt has started and surface a status
     * indicator over the home screen. Called from HomeFragment auto-connect paths
     * and from MainActivity itself when launched via USB auto-attach.
     *
     * @param reason Diagnostic label written to the log.
     * @param mode PILL for non-blocking background attempts (home buttons stay
     *        usable), OVERLAY for user-initiated attempts (full-screen custom
     *        loading screen with cancel button).
     * @param customStatusText Optional override for the status text. If provided
     *        and the user has the show-text option enabled, this string is shown
     *        instead of the generic "Android Auto is starting…". Used by the
     *        Nearby selector to surface the picked device name.
     */
    @JvmOverloads
    fun beginAutoConnect(reason: String, mode: ConnectionUiMode, customStatusText: String? = null) {
        if (autoConnectInProgress) return
        val commManager = App.provide(this).commManager
        // If we are already past the connection phase, no indicator is needed.
        if (commManager.isConnected) return
        AppLog.i("Auto-connect: begin ($reason, mode=$mode)")
        autoConnectInProgress = true
        autoConnectMode = mode
        // Seed hasAdvancedToActiveState from the current connection state. If
        // something else (e.g. AapService responding to a USB attach) already
        // moved the state into Connecting before we got here, the StateFlow
        // will not re-emit it, so the observer would never flip the flag to
        // true on its own. Without this seed, a subsequent failure transition
        // to Disconnected would be misread as the initial Disconnected on
        // launch and ignored until the 30 s watchdog kicks in.
        val currentState = commManager.connectionState.value
        hasAdvancedToActiveState = currentState is CommManager.ConnectionState.Connecting ||
                currentState is CommManager.ConnectionState.Connected ||
                currentState is CommManager.ConnectionState.StartingTransport
        autoConnectStatusText = customStatusText
        // Hand the status text off to AapProjectionActivity so its own loading
        // screen continues to show the same context-specific label after the
        // handshake completes and AAP takes over the UI. AAP reads and clears
        // this on its first launch; we always overwrite it here (even with
        // null) so a stale value from a prior attempt can't leak across.
        AapProjectionActivity.pendingStatusText = customStatusText
        showAutoConnectUi()
    }

    /**
     * Dispatches to the pill or overlay show-method based on [autoConnectMode].
     */
    private fun showAutoConnectUi() {
        when (autoConnectMode) {
            ConnectionUiMode.PILL -> showAutoConnectPill()
            ConnectionUiMode.OVERLAY -> showAutoConnectOverlay()
        }
    }

    /**
     * Cancels an in-progress auto-connect attempt, regardless of whether it
     * has reached the Connecting state yet. Safe to call even if no attempt is
     * pending. Invoked by pill tap, overlay cancel button, and back-press when
     * the overlay is up.
     */
    private fun cancelAutoConnect() {
        if (!autoConnectInProgress) return
        AppLog.i("Auto-connect: cancelled by user")
        // disconnect() handles all states including the Connecting state where
        // ACTION_DISCONNECT in AapService used to be a no-op. Setting state to
        // Disconnected here also feeds the observer, but we end the UI
        // immediately rather than waiting for the round-trip.
        App.provide(this).commManager.disconnect()
        endAutoConnect(success = false)
    }

    /**
     * Subscribes to [CommManager.connectionState] to drive the auto-connect overlay.
     *
     * Show: when the overlay is requested via [beginAutoConnect] and the connection
     * advances out of the initial Disconnected state.
     *
     * Hide: on terminal states (HandshakeComplete/TransportStarted = success — AAP
     * projection activity will take over with its own overlay; Error/Disconnected =
     * failure — drop back to HomeFragment so the user can intervene).
     */
    private fun observeConnectionStateForOverlay() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                App.provide(this@MainActivity).commManager.connectionState.collect { state ->
                    when (state) {
                        is CommManager.ConnectionState.Connecting,
                        is CommManager.ConnectionState.Connected,
                        is CommManager.ConnectionState.StartingTransport -> {
                            hasAdvancedToActiveState = true
                            // The pill/overlay should already be visible (set when auto-connect
                            // was requested); ensure it is in case the request raced with
                            // setContentView or the activity was recreated mid-attempt.
                            if (autoConnectInProgress) {
                                showAutoConnectUi()
                            }
                        }
                        is CommManager.ConnectionState.HandshakeComplete,
                        is CommManager.ConnectionState.TransportStarted -> {
                            // AapProjectionActivity is launching (HandshakeComplete) or
                            // has launched (TransportStarted). Hide our overlay so we
                            // don't keep video/animation resources alive while AAP
                            // covers us.
                            if (autoConnectInProgress) {
                                AppLog.i("Auto-connect overlay: handshake complete, handing off to projection")
                                endAutoConnect(success = true)
                            }
                        }
                        is CommManager.ConnectionState.Error -> {
                            if (autoConnectInProgress) {
                                AppLog.w("Auto-connect overlay: connection error: ${state.message}")
                                endAutoConnect(success = false)
                            }
                        }
                        is CommManager.ConnectionState.Disconnected -> {
                            // Initial Disconnected on app launch is normal; only treat
                            // as failure if we previously advanced to an active state.
                            if (autoConnectInProgress && hasAdvancedToActiveState) {
                                AppLog.w("Auto-connect overlay: disconnected mid-attempt")
                                endAutoConnect(success = false)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun endAutoConnect(success: Boolean) {
        autoConnectWatchdog?.cancel()
        autoConnectWatchdog = null
        autoConnectInProgress = false
        hasAdvancedToActiveState = false
        autoConnectStatusText = null
        if (!success) {
            // Failure path: AAP is not going to launch, so clear the handover
            // value so it can't appear on a later, unrelated connection. On
            // success we leave it alone, AAP either already consumed it in
            // onCreate or is about to.
            AapProjectionActivity.pendingStatusText = null
        }
        // Pill cleanup is cheap and safe to run regardless of mode.
        hideAutoConnectPill()
        if (success) {
            // On success the projection activity will cover our overlay almost
            // immediately. Hiding without an animation avoids the fade competing
            // with the activity transition.
            findViewById<View>(R.id.auto_connect_loading_overlay)?.visibility = View.GONE
            stopAutoConnectVideo()
            autoConnectKenBurnsAnim?.cancel()
            autoConnectKenBurnsAnim = null
        } else {
            hideAutoConnectOverlay()
        }
    }

    private fun showAutoConnectPill() {
        // Watchdog backstop applies to pill mode too so the indicator can't
        // get stuck if the connection attempt never produces an event.
        if (autoConnectWatchdog?.isActive != true) {
            startAutoConnectWatchdog()
        }

        val pill = findViewById<View>(R.id.auto_connect_pill) ?: return
        val pillText = findViewById<android.widget.TextView>(R.id.auto_connect_pill_text)
        pillText?.text = autoConnectStatusText ?: getString(R.string.android_auto_starting)
        if (pill.visibility == View.VISIBLE) return

        pill.visibility = View.VISIBLE
        pill.bringToFront()
        // Suppress the launch splash if it is still up so the pill is visible
        // immediately on auto-connect from a cold start.
        findViewById<View>(R.id.splash_overlay)?.visibility = View.GONE
    }

    private fun hideAutoConnectPill() {
        val pill = findViewById<View>(R.id.auto_connect_pill) ?: return
        if (pill.visibility != View.VISIBLE) return
        pill.visibility = View.GONE
    }

    private fun startAutoConnectWatchdog() {
        autoConnectWatchdog?.cancel()
        autoConnectWatchdog = lifecycleScope.launch {
            delay(AUTO_CONNECT_WATCHDOG_MS)
            if (autoConnectInProgress) {
                AppLog.w("Auto-connect overlay: watchdog timeout, hiding")
                endAutoConnect(success = false)
            }
        }
    }

    private fun showAutoConnectOverlay() {
        // Watchdog is owned by this activity's lifecycleScope, so it must be
        // (re)started here to survive configuration changes that recreate the
        // activity while autoConnectInProgress remains true.
        if (autoConnectWatchdog?.isActive != true) {
            startAutoConnectWatchdog()
        }

        val overlay = findViewById<View>(R.id.auto_connect_loading_overlay) ?: return
        if (overlay.visibility == View.VISIBLE) return

        overlay.alpha = 1f
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
        // Once the auto-connect overlay is up there is no point keeping the
        // launch splash around — they would just stack the same dark background.
        findViewById<View>(R.id.splash_overlay)?.visibility = View.GONE

        setupAutoConnectMedia()
    }

    private fun setupAutoConnectMedia() {
        val settings = App.provide(this).settings
        val mediaPath = settings.loadingScreenMediaPath
        val mediaType = settings.loadingScreenMediaType

        val defaultContent = findViewById<View>(R.id.auto_connect_loading_default_content)
        val customTextOverlay = findViewById<View>(R.id.auto_connect_loading_custom_text_overlay)
        val customImage = findViewById<ImageView>(R.id.auto_connect_loading_custom_image)
        val customVideo = findViewById<VideoView>(R.id.auto_connect_loading_custom_video)
        val overlay = findViewById<View>(R.id.auto_connect_loading_overlay)

        // Apply the optional custom status text (e.g. "Connecting to Pixel 8…").
        // Falls back to the default "Android Auto is starting…" when no override
        // is set. Both the default and custom-media text overlays share the same
        // string so the wording stays consistent regardless of media presence.
        val statusText = autoConnectStatusText ?: getString(R.string.android_auto_starting)
        findViewById<android.widget.TextView>(R.id.auto_connect_loading_default_text)?.text = statusText
        findViewById<android.widget.TextView>(R.id.auto_connect_loading_custom_text)?.text = statusText

        if (mediaPath.isEmpty() || mediaType.isEmpty()) {
            // No custom media — show default text + spinner over the dark backdrop.
            defaultContent?.visibility = View.VISIBLE
            customTextOverlay?.visibility = View.GONE
            customImage?.visibility = View.GONE
            customVideo?.visibility = View.GONE
            overlay?.setBackgroundColor(Color.parseColor("#CC000000"))
            return
        }

        val file = File(mediaPath)
        if (!file.exists()) {
            // Stored path is stale — reset and fall back to default.
            settings.loadingScreenMediaPath = ""
            settings.loadingScreenMediaType = ""
            defaultContent?.visibility = View.VISIBLE
            customTextOverlay?.visibility = View.GONE
            customImage?.visibility = View.GONE
            customVideo?.visibility = View.GONE
            overlay?.setBackgroundColor(Color.parseColor("#CC000000"))
            return
        }

        defaultContent?.visibility = View.GONE
        overlay?.setBackgroundColor(Color.BLACK)

        if (settings.loadingScreenShowText) {
            customTextOverlay?.visibility = View.VISIBLE
        } else {
            customTextOverlay?.visibility = View.GONE
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
                    customVideo?.visibility = View.GONE
                    customImage?.visibility = View.VISIBLE
                    if (customImage != null) {
                        Glide.with(this).load(file).into(customImage)
                        if (keepRatio) {
                            autoConnectKenBurnsAnim?.cancel()
                            val scaleAnim = ObjectAnimator.ofPropertyValuesHolder(
                                customImage,
                                PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.05f),
                                PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.05f)
                            )
                            scaleAnim.duration = 8000
                            scaleAnim.repeatMode = ObjectAnimator.REVERSE
                            scaleAnim.repeatCount = ObjectAnimator.INFINITE
                            scaleAnim.start()
                            autoConnectKenBurnsAnim = scaleAnim
                        }
                    }
                }
                "gif" -> {
                    customVideo?.visibility = View.GONE
                    customImage?.visibility = View.VISIBLE
                    if (customImage != null) {
                        Glide.with(this).asGif().load(file).into(customImage)
                    }
                }
                "video" -> {
                    customImage?.visibility = View.GONE
                    customVideo?.visibility = View.VISIBLE
                    customVideo?.setVideoPath(file.absolutePath)
                    customVideo?.setOnPreparedListener { mp ->
                        mp.isLooping = settings.loadingScreenLoopVideo
                        mp.setVolume(0f, 0f)

                        try {
                            val vw = mp.videoWidth
                            val vh = mp.videoHeight
                            if (overlay != null) {
                                val cw = overlay.width
                                val ch = overlay.height
                                if (cw > 0 && ch > 0) {
                                    val lp = customVideo.layoutParams as FrameLayout.LayoutParams
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
                                    customVideo.layoutParams = lp
                                }
                            }
                        } catch (e: Exception) {
                            AppLog.w("Auto-connect overlay: could not resize video: ${e.message}")
                        }
                    }
                    customVideo?.setOnErrorListener { _, _, _ ->
                        AppLog.e("Auto-connect overlay: error playing custom video")
                        // Fall back to default text on video error.
                        findViewById<View>(R.id.auto_connect_loading_custom_video)?.visibility = View.GONE
                        customTextOverlay?.visibility = View.GONE
                        defaultContent?.visibility = View.VISIBLE
                        overlay?.setBackgroundColor(Color.parseColor("#CC000000"))
                        true
                    }
                    customVideo?.start()
                }
            }
        } catch (e: Exception) {
            AppLog.e("Auto-connect overlay: failed to load media: ${e.message}")
            customImage?.visibility = View.GONE
            customVideo?.visibility = View.GONE
            customTextOverlay?.visibility = View.GONE
            defaultContent?.visibility = View.VISIBLE
            overlay?.setBackgroundColor(Color.parseColor("#CC000000"))
        }
    }

    private fun hideAutoConnectOverlay() {
        val overlay = findViewById<View>(R.id.auto_connect_loading_overlay) ?: return
        if (overlay.visibility != View.VISIBLE) return

        // Stop video FIRST — VideoView's SurfaceView ignores parent alpha animations
        // and would otherwise stay visible during the fade-out.
        stopAutoConnectVideo()
        autoConnectKenBurnsAnim?.cancel()
        autoConnectKenBurnsAnim = null
        findViewById<View>(R.id.auto_connect_loading_custom_video)?.visibility = View.GONE
        findViewById<View>(R.id.auto_connect_loading_custom_image)?.visibility = View.GONE
        findViewById<View>(R.id.auto_connect_loading_custom_text_overlay)?.visibility = View.GONE

        val hasCustomVideo = App.provide(this).settings.loadingScreenMediaType == "video"
        if (hasCustomVideo) {
            overlay.visibility = View.GONE
        } else {
            overlay.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction {
                    overlay.visibility = View.GONE
                    overlay.alpha = 1f
                }
                .start()
        }
    }

    private fun stopAutoConnectVideo() {
        findViewById<VideoView>(R.id.auto_connect_loading_custom_video)?.let {
            try {
                if (it.isPlaying) it.stopPlayback()
                it.suspend()
            } catch (_: Exception) {}
        }

    }

    private fun showSplashWithDelay(delayMs: Long) {
        val overlay = findViewById<View>(R.id.splash_overlay) ?: return
        lifecycleScope.launch(Dispatchers.Main) {
            if (delayMs > 0) {
                delay(delayMs)
            }
            overlay.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction {
                    overlay.visibility = View.GONE
                }
                .start()
        }
    }

    private fun setupWifiDirectInfo() {
        val tvInfo = findViewById<android.widget.TextView>(R.id.wifi_direct_info)
        val settings = Settings(this)

        lifecycleScope.launch {
            AapService.wifiDirectName.collectLatest { name ->
                val isHelperMode = settings.wifiConnectionMode == 2
                if (isHelperMode && name != null) {
                    tvInfo.text = "WiFi Direct: $name"
                    tvInfo.visibility = View.VISIBLE
                } else {
                    tvInfo.visibility = View.GONE
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onStop() {
        super.onStop()
        // Free media resources whenever the activity is backgrounded. Covers user
        // pressing Home, AapProjectionActivity coming to front (success), and
        // navigating to SettingsActivity. The connection itself keeps running in
        // AapService; this only tears down the visual indicator. The watchdog is
        // intentionally NOT cancelled here so the flag is still cleared if every
        // remaining state transition happens while the activity is stopped.
        if (findViewById<View>(R.id.auto_connect_loading_overlay)?.visibility == View.VISIBLE) {
            stopAutoConnectVideo()
            autoConnectKenBurnsAnim?.cancel()
            autoConnectKenBurnsAnim = null
            findViewById<View>(R.id.auto_connect_loading_overlay)?.visibility = View.GONE
        }
        // Pill has no media resources but should also be hidden so it doesn't
        // briefly flash on resume before the observer re-applies the UI.
        findViewById<View>(R.id.auto_connect_pill)?.visibility = View.GONE
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Opening the app from its icon is a fresh start, so the permission screen gets to offer
        // itself again. Back only finishes this activity when it is pressed twice, so without this
        // the "once per run" guard would outlive several launches and the screen would never
        // reappear until the process died.
        //
        // CATEGORY_HOME is excluded deliberately: in launcher mode every press of the Home button
        // arrives here too, and that is navigation, not a restart.
        if (intent.action == Intent.ACTION_MAIN &&
            intent.hasCategory(Intent.CATEGORY_LAUNCHER) &&
            !intent.hasCategory(Intent.CATEGORY_HOME)
        ) {
            permissionSetupNavigatedThisRun = false
        }
        handleLaunchIntent(intent)
    }

    private fun logLaunchSource() {
        val source = intent?.getStringExtra(EXTRA_LAUNCH_SOURCE)
        if (source != null) {
            AppLog.i("App launched via: $source")
            return
        }

        val referrer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            referrer?.toString()
        } else null

        val isLauncherTap = intent?.action == Intent.ACTION_MAIN &&
                intent.hasCategory(Intent.CATEGORY_LAUNCHER)

        if (isLauncherTap) {
            AppLog.i("App launched by user tap (referrer: ${referrer ?: "none"})")
        } else if (referrer != null) {
            AppLog.i("App launched by third party: $referrer (action: ${intent?.action})")
        } else {
            AppLog.i("App launched, source unknown (action: ${intent?.action})")
        }
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent == null) return

        val intentData = intent.data
        val intentAction = intent.action

        if (intentAction == "com.andrerinas.headunitrevived.ACTION_EXIT") {
            AppLog.i("MainActivity: Received exit action")
            val exitIntent = Intent(this, AapService::class.java).apply {
                this.action = AapService.ACTION_STOP_SERVICE
            }
            ContextCompat.startForegroundService(this, exitIntent)
            finishAffinity()
            return
        }

        if (intentAction == AapService.ACTION_START_SELF_MODE || 
           (intentData?.scheme == "headunit" && intentData.host == "selfmode")) {
            AppLog.i("MainActivity: Forced self-mode start requested")
            HomeFragment.forceSelfModeLaunch = true
            val selfModeIntent = Intent(this, AapService::class.java).apply {
                this.action = AapService.ACTION_START_SELF_MODE
            }
            ContextCompat.startForegroundService(this, selfModeIntent)
        }

        if (intent.action == Intent.ACTION_VIEW) {
            if (intentData?.scheme == "headunit" && intentData.host == "connect") {
                val ip = intentData.getQueryParameter("ip")
                if (!ip.isNullOrEmpty()) {
                    AppLog.i("Received connect intent for IP: $ip")
                    ContextCompat.startForegroundService(this, Intent(this, AapService::class.java).apply {
                        action = AapService.ACTION_CONNECT_SOCKET
                    })
                    lifecycleScope.launch(Dispatchers.IO) { App.provide(this@MainActivity).commManager.connect(ip, 5277) }
                } else {
                    AppLog.i("Received connect intent without IP -> triggering last session auto-connect")
                    val autoIntent = Intent(this, AapService::class.java).apply {
                        action = AapService.ACTION_CHECK_USB
                    }
                    ContextCompat.startForegroundService(this, autoIntent)
                }
            } else if (intentData?.scheme == "headunit" && intentData.host == "disconnect") {
                AppLog.i("Received disconnect intent")
                val stopIntent = Intent(this, AapService::class.java).apply {
                    action = AapService.ACTION_DISCONNECT
                }
                ContextCompat.startForegroundService(this, stopIntent)
            } else if (intentData?.scheme == "headunit" && intentData.host == "exit") {
                AppLog.i("Received full exit intent via deep link")
                val exitIntent = Intent(this, AapService::class.java).apply {
                    action = AapService.ACTION_STOP_SERVICE
                }
                ContextCompat.startForegroundService(this, exitIntent)
                finishAffinity()
            }
        }
    }

    private fun setFullscreen() {
        val root = findViewById<View>(R.id.root)
        val appSettings = Settings(this)
        SystemUI.apply(window, root, appSettings.fullscreenMode)
    }

    override fun onResume() {
        super.onResume()
        setFullscreen()

        checkSetupFlow()

        requestedOrientation = Settings(this).screenOrientation.androidOrientation
        ContextCompat.registerReceiver(this, orientationReceiver, android.content.IntentFilter(AapService.ACTION_ORIENTATION_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        isOrientationReceiverRegistered = true

        // If an Android Auto session is active, bring the projection activity to front. While the
        // user has paused it, this is skipped every time (the flag is not consumed here — only a
        // real return to projection clears it), so Home keeps showing the home screen.
        AppLog.i("DIAG: MainActivity.onResume forward-check connected=${App.provide(this).commManager.isConnected} pip=${App.isPiPActive} projFg=${AapProjectionActivity.isForeground} paused=$projectionPausedByUser dest=${ScreenFocus.describeDestination()}")
        if (App.provide(this).commManager.isConnected && !App.isPiPActive
            && !AapProjectionActivity.isForeground && !projectionPausedByUser) {
            // Deliberately asks ScreenFocus about the destination rather than the foreground
            // activity: onActivityResumed is dispatched after onResume() returns, so the
            // foreground view is still stale here and would read as "free to take over".
            if (ScreenFocus.isProtectedDestination()) {
                AppLog.i("FOCUS: onResume forward suppressed (dest=${ScreenFocus.describeDestination()})")
                return
            }
            AppLog.i("MainActivity: Active session detected, bringing projection to front")
            val aapIntent = AapProjectionActivity.intent(this).apply {
                putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(aapIntent)
        }
    }

    override fun onPause() {
        super.onPause()
        if (isOrientationReceiverRegistered) {
            unregisterReceiver(orientationReceiver)
            isOrientationReceiverRegistered = false
        }
    }

    /**
     * First-run chain, driven by [onResume]: permission dashboard -> disclaimer.
     * Each step returns early, so the next one only appears once the previous is done.
     *
     * The permissions step used to be an overlay dialog here and a blast of system dialogs in
     * [onCreate]; both moved onto the dashboard, where each one carries its reason and its state.
     * There used to be a setup wizard after the disclaimer; everything it configured is worked out
     * automatically now, so nothing is asked about the display any more.
     */
    fun checkSetupFlow() {
        val appSettings = Settings(this)
        if (navigateToPermissionSetupIfNeeded(appSettings)) return

        if (!appSettings.hasAcceptedDisclaimer) {
            SafetyDisclaimerDialog.show(supportFragmentManager)
        }
    }

    /**
     * Opens the permission dashboard when something is missing, on every launch.
     *
     * Not a first-run-only prompt, unlike the overlay and battery dialogs it replaced. Those asked
     * for a one-off action; this screen reports a state that can stay broken, and a missing
     * Bluetooth permission means Android Auto silently never connects. So:
     *
     *  - a missing **required** permission reopens the screen every run and nothing silences it;
     *  - a missing **recommended** one is silenced for good once the rider closes the screen with
     *    Continue (which is only available when nothing required is missing anyway).
     *
     * Backing out is "later", not "done": it leaves [Settings.hasDismissedPermissionSetup] alone,
     * so the screen returns on the next run while staying out of the way for the rest of this one.
     *
     * The guards matter more than the navigation. This runs on every resume, and the dashboard
     * itself sends the rider out to system settings screens — so the return trip from a card tap
     * comes straight back through here. Without the "already on it" check the screen would be
     * pushed on top of itself once per tap, and Back would need pressing as many times.
     *
     * @return true if the setup chain should stop here for now.
     */
    private fun navigateToPermissionSetupIfNeeded(appSettings: Settings): Boolean {
        if (permissionSetupNavigatedThisRun) return false

        val requiredMissing = PermissionItem.anyRequiredMissing(this)
        val recommendedMissing = PermissionItem.anyRecommendedMissing(this)
        if (!requiredMissing &&
            (appSettings.hasDismissedPermissionSetup || !recommendedMissing)
        ) {
            return false
        }

        val nav = navController ?: return false
        if (nav.currentDestination?.id == R.id.permissionDashboardFragment) return true
        // Never pull the screen out from under Ride, Apps or anything else the rider chose.
        if (nav.currentDestination?.id != R.id.homeFragment) return false

        permissionSetupNavigatedThisRun = true
        // The splash sits above the nav host and is clickable, so on a cold first run it would eat
        // the dashboard's taps for over a second.
        findViewById<View>(R.id.splash_overlay)?.visibility = View.GONE
        val reason = if (requiredMissing) "required" else "recommended"
        AppLog.i("FOCUS: opening the permission dashboard ($reason permissions missing)")
        nav.navigate(
            R.id.action_homeFragment_to_permissionDashboardFragment,
            android.os.Bundle().apply {
                putBoolean(PermissionDashboardFragment.ARG_SETUP_MODE, true)
            }
        )
        return true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setFullscreen()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        AppLog.i("dispatchKeyEvent: keyCode=%d, action=%d", event.keyCode, event.action)
        
        // Always give the KeymapFragment (if active) a chance to see the key
        val handled = keyListener?.onKeyEvent(event) ?: false
        
        // If the key was handled by our listener (e.g. in KeymapFragment), stop here
        if (handled) return true
        
        // Otherwise continue with standard handling
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        navController = null
        if (isFinishReceiverRegistered) {
            unregisterReceiver(finishReceiver)
            isFinishReceiverRegistered = false
        }
        if (isFinishing) {
            AppLog.i("MainActivity finishing, resetting auto-start flag.")
            HomeFragment.resetAutoStart()
        }
    }

    companion object {
        const val EXTRA_LAUNCH_SOURCE = "launch_source"

        /**
         * True once the permission dashboard has been offered in this app run.
         *
         * [checkSetupFlow] runs on every resume, so without this the screen would be pushed again
         * the moment the rider backed out of it — and again on every return from a system settings
         * screen the dashboard itself sent them to.
         *
         * Process-scoped rather than per-instance, like [projectionPausedByUser]: a theme change
         * calls `recreate()`, which would reset an instance field and let the screen re-open on top
         * of whatever the rider was doing.
         *
         * Being process-scoped, it has to be cleared explicitly or it outlives every launch until
         * the process dies — which is exactly what went wrong once. A "run" starts at a fresh
         * [onCreate] or at a launcher [onNewIntent]; see both for which cases deliberately do not
         * count.
         */
        @Volatile
        var permissionSetupNavigatedThisRun = false

        /**
         * True while the user has paused the projection from the exit menu
         * ([AapProjectionActivity.moveToBackground]). The session stays live, but nothing brings
         * the projection back on its own: this activity skips its auto-forward, and so do the
         * service's screen-on / user-present "session is live, show it again" paths. Only an
         * explicit return (the Android Auto tile, the notification, or a brand new handshake)
         * ends the pause — [AapProjectionActivity.onResume] clears it.
         *
         * Deliberately sticky, not one-shot: a one-shot guard only survived the first return to
         * Home, so the next onResume forwarded straight back into projection.
         */
        @Volatile
        var projectionPausedByUser = false

        /**
         * Hard upper bound for how long the auto-connect overlay may stay visible
         * without the connection state advancing through the success path. Covers
         * USB open hangs and silent AOA-mode-switch failures, which today produce
         * no event for the observer to react to.
         */
        private const val AUTO_CONNECT_WATCHDOG_MS = 30_000L

        /**
         * `true` while the loading indicator should be (or is) covering the home
         * screen during an automatic connection attempt. Set by entry points
         * that initiate an auto-connect; cleared by [endAutoConnect].
         */
        @Volatile var autoConnectInProgress: Boolean = false

        /**
         * Visual mode for the in-progress attempt. Kept on the companion so a
         * recreated activity (e.g. after rotation) can re-apply the same UI
         * mode the original [beginAutoConnect] caller asked for.
         */
        @Volatile var autoConnectMode: ConnectionUiMode = ConnectionUiMode.OVERLAY

        /**
         * Optional override for the status text (e.g. "Connecting to Pixel 8…"
         * from the Nearby selector). When `null`, the default
         * `R.string.android_auto_starting` is used. Kept on the companion so
         * the customized text isn't lost if the activity is recreated mid
         * attempt.
         */
        @Volatile var autoConnectStatusText: String? = null

        /**
         * Tracks whether the connection attempt has reached an active state
         * (Connecting/Connected/StartingTransport). Used by the connection
         * observer to distinguish a genuine mid-attempt Disconnect (failure)
         * from the initial Disconnected state on app launch (expected). Kept
         * on the companion so a recreated activity does not lose this signal
         * and mistakenly treat a real failure as the initial state.
         */
        @Volatile var hasAdvancedToActiveState: Boolean = false

    }
}
