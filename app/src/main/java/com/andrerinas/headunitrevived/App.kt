package com.andrerinas.headunitrevived

import androidx.appcompat.app.AppCompatDelegate
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import com.andrerinas.headunitrevived.aap.AapProjectionActivity
import com.andrerinas.headunitrevived.license.LicenseGuard
import com.andrerinas.headunitrevived.location.GpsFixMonitor
import com.andrerinas.headunitrevived.sensors.GForceEstimator
import com.andrerinas.headunitrevived.sensors.LeanEstimator
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.multidex.MultiDex
import com.andrerinas.headunitrevived.main.BackgroundNotification
import com.andrerinas.headunitrevived.main.ScreenFocus
import com.andrerinas.headunitrevived.aap.AapNavigation
import com.andrerinas.headunitrevived.ssl.ConscryptInitializer
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.AppThemeManager
import com.andrerinas.headunitrevived.utils.Settings
import android.os.SystemClock
import java.io.File

class App : Application() {

    private val component: AppComponent by lazy {
        AppComponent(this)
    }

    /** How many of our activities are resumed right now — drives the GNSS keep-alive below. */
    private var resumedActivities = 0
    private val gnssHandler = Handler(Looper.getMainLooper())
    private val releaseGnssKeepAlive = Runnable { GpsFixMonitor.release(GNSS_KEEP_ALIVE_OWNER) }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this


        
        // Enable vector drawable support on older Android versions
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)

        if (ConscryptInitializer.isNeededForTls12()) {
            ConscryptInitializer.initialize()
        }

        if (isUserUnlocked()) {
            val settings = Settings(this) // Create a Settings instance
            AppLog.init(settings, this) // Initialize AppLog with settings for conditional logging

            // Sync auto-start settings to device-protected storage so that
            // BootCompleteReceiver, UsbAttachedActivity, and AutoStartReceiver
            // can read them during locked boot (before user unlock)
            Settings.syncAutoStartOnBootToDeviceStorage(this, settings.autoStartOnBoot)
            Settings.syncAutoStartOnUsbToDeviceStorage(this, settings.autoStartOnUsb)
            Settings.syncAutoStartOnWifiToDeviceStorage(this, settings.autoStartOnWifi)
            Settings.syncAutoStartWifiSsidToDeviceStorage(this, settings.autoStartWifiSsid)
            Settings.syncAutoStartBtMacsToDeviceStorage(this, settings.autoStartBluetoothDeviceMacs)

            // Apply app theme
            if (AppThemeManager.isStaticMode(settings.appTheme)) {
                AppThemeManager.applyStaticTheme(settings)
            } else {
                appThemeManager = AppThemeManager(this, settings)
                appThemeManager?.start()
            }
        } else {
            AppLog.init(null, this) // Initialize with default logging if locked
            AppLog.w("App started in Direct Boot mode (locked). Settings access deferred.")
        }

        if (ConscryptInitializer.isAvailable()) {
            AppLog.i("Conscrypt security provider is active")
        } else if (ConscryptInitializer.isNeededForTls12()) {
            AppLog.w("Conscrypt not available - TLS 1.2 may not work on this device")
        }

        AppLog.d( "native library dir ${applicationInfo.nativeLibraryDir}")

        File(applicationInfo.nativeLibraryDir).listFiles()?.forEach { file ->
            AppLog.d( "   ${file.name}")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(defaultChannel, "Headunit Service", NotificationManager.IMPORTANCE_LOW)
            serviceChannel.description = "Persistent service notification"
            serviceChannel.setShowBadge(false)
            component.notificationManager.createNotificationChannel(serviceChannel)

            val mediaChannel = NotificationChannel(BackgroundNotification.mediaChannel, "Media Playback", NotificationManager.IMPORTANCE_LOW)
            mediaChannel.setSound(null, null)
            mediaChannel.setShowBadge(false)
            component.notificationManager.createNotificationChannel(mediaChannel)

            AapNavigation.createNotificationChannel(this)

            val bootChannel = NotificationChannel(bootStartChannel, "Boot Auto-Start", NotificationManager.IMPORTANCE_HIGH)
            bootChannel.description = "Shown once after boot to open the app"
            bootChannel.setShowBadge(false)
            component.notificationManager.createNotificationChannel(bootChannel)
        }

        // Register the main broadcast receiver safely for Android 14+ using ContextCompat
        ContextCompat.registerReceiver(this, AapBroadcastReceiver(), AapBroadcastReceiver.filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // Play build: evaluate the stored licence and, if there is internet, ask the Play Store.
        // The GitHub build has nothing to check.
        LicenseGuard.init(this)

        // Keep the Ride lean estimator running while Android Auto is projecting, so the recorded
        // lean and g-force maxima accumulate across a ride even though the Ride screen is not visible.
        // Also track which of our screens is in front, so a connection coming up in the background
        // doesn't yank the rider off the screen they chose (see ScreenFocus).
        //
        // And hold the GNSS session open for as long as any of our screens is up. Every screen used
        // to bring its own listener and take it away again, so moving between Home, Ride and the
        // Android Auto projection tore the session down and rebuilt it — an 8 September ride logged
        // an 11-second gap that way, and the first fix of the day took 13 minutes to arrive while
        // the second, on a warm ephemeris, took 63 seconds. One owner that outlives those handovers
        // costs nothing extra (the screens' own listeners still win on interval) and lets the
        // receiver keep searching across them.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                ScreenFocus.onResumed(activity)
                resumedActivities++
                gnssHandler.removeCallbacks(releaseGnssKeepAlive)
                GpsFixMonitor.acquire(GNSS_KEEP_ALIVE_OWNER, this@App)
                if (activity is AapProjectionActivity && LicenseGuard.isUsable(this@App)) {
                    LeanEstimator.acquire("aa", this@App)
                    GForceEstimator.acquire("aa", this@App)
                }
            }
            override fun onActivityPaused(activity: Activity) {
                ScreenFocus.onPaused(activity)
                if (resumedActivities > 0) resumedActivities--
                if (resumedActivities == 0) {
                    // Deliberately delayed: Android runs A.onPause fully before B.onResume, so the
                    // count dips to zero on every screen change. Releasing right here would tear the
                    // GNSS session down at exactly the moments this is meant to bridge.
                    gnssHandler.removeCallbacks(releaseGnssKeepAlive)
                    gnssHandler.postDelayed(releaseGnssKeepAlive, GNSS_KEEP_ALIVE_LINGER_MS)
                }
                if (activity is AapProjectionActivity) {
                    LeanEstimator.release("aa")
                    GForceEstimator.release("aa")
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun isUserUnlocked(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val userManager = getSystemService(Context.USER_SERVICE) as UserManager
            userManager.isUserUnlocked
        } else {
            true
        }
    }

    companion object {
        const val defaultChannel = "headunit_service_v2"
        const val bootStartChannel = "headunit_boot_start"
        val appStartTime = SystemClock.elapsedRealtime()
        var appThemeManager: AppThemeManager? = null
        var isPiPActive = false

        /** [GpsFixMonitor] owner held for as long as any of our screens is up. */
        private const val GNSS_KEEP_ALIVE_OWNER = "app"

        /**
         * How long the GNSS session outlives the last resumed screen. Long enough to bridge a
         * screen change or a quick trip out of the app, short enough that a head unit left in the
         * background is not searching for satellites all night.
         */
        private const val GNSS_KEEP_ALIVE_LINGER_MS = 60_000L

        @Volatile
        var instance: App? = null
            private set


        fun get(context: Context): App {
            return context.applicationContext as App
        }
        fun provide(context: Context): AppComponent {
            return get(context).component
        }
    }
}