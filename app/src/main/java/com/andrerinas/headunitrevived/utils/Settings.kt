package com.andrerinas.headunitrevived.utils

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.protocol.proto.Control
import com.andrerinas.headunitrevived.app.UsbAttachedActivity
import com.andrerinas.headunitrevived.connection.UsbDeviceCompat

class Settings(private val context: Context) {

    private val _prefs: SharedPreferences? by lazy {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (e: Exception) {
            null
        }
    }

    private val prefs: SharedPreferences
        get() = _prefs ?: throw IllegalStateException("SharedPreferences in credential encrypted storage are not available until after user is unlocked")

    fun isConnectingDevice(deviceCompat: UsbDeviceCompat): Boolean {
        val allowDevices = prefs.getStringSet("allow-devices", null) ?: return false
        return allowDevices.contains(deviceCompat.uniqueName)
    }

    var allowedDevices: Set<String>
        get() = prefs.getStringSet("allow-devices", HashSet<String>())!!
        set(devices) {
            prefs.edit().putStringSet("allow-devices", devices).apply()
        }

    var networkAddresses: Set<String>
        get() = prefs.getStringSet("network-addresses", HashSet<String>())!!
        set(addrs) {
            prefs.edit().putStringSet("network-addresses", addrs).apply()
        }

    var bluetoothAddress: String
        get() = prefs.getString("bt-address", "")!!
        set(value) = prefs.edit().putString("bt-address", value).apply()

    /** False until [lastKnownLocation] has really been written; its getter otherwise invents Tel Aviv. */
    val hasLastKnownLocation: Boolean
        get() = prefs.contains("last-loc-latitude")

    var lastKnownLocation: Location
        get() {
            val latitudeBits = prefs.getLong("last-loc-latitude", (32.0864169).toRawBits())
            val longitudeBits = prefs.getLong("last-loc-longitude", (34.7557871).toRawBits())

            val location = Location("")
            location.latitude = java.lang.Double.longBitsToDouble(latitudeBits)
            location.longitude = java.lang.Double.longBitsToDouble(longitudeBits)
            return location
        }
        set(location) {
            prefs.edit()
                .putLong("last-loc-latitude", location.latitude.toRawBits())
                .putLong("last-loc-longitude", location.longitude.toRawBits())
                .apply()
        }

    /**
     * The last real GPS fix, kept so the Ride screen has a position the moment it opens - the bike
     * did not move while it was parked, and with no internet for A-GPS a cold fix can take minutes.
     *
     * Kept apart from [lastKnownLocation], which defaults to Tel Aviv and cannot say "never had a
     * fix". NightMode prefers this one since 1.2.23. This one is null
     * until a fix has been stored. Altitude is stored already sea-level-corrected where the platform
     * offered it (see AltitudeTracker.altitudeOf), so it is handed back as plain altitude.
     *
     * Device state, not a preference: not part of the settings backup.
     */
    var lastGpsFix: Location?
        get() {
            val time = prefs.getLong("last-fix-time", 0L)
            if (time <= 0L) return null
            val location = Location("persisted")
            location.latitude = java.lang.Double.longBitsToDouble(prefs.getLong("last-fix-lat", 0L))
            location.longitude = java.lang.Double.longBitsToDouble(prefs.getLong("last-fix-lon", 0L))
            val alt = prefs.getFloat("last-fix-alt", Float.NaN)
            if (!alt.isNaN()) location.altitude = alt.toDouble()
            location.time = time
            return location
        }
        set(location) {
            val edit = prefs.edit()
            if (location == null) {
                edit.remove("last-fix-lat").remove("last-fix-lon").remove("last-fix-alt").remove("last-fix-time")
            } else {
                val alt = com.andrerinas.headunitrevived.sensors.AltitudeTracker.altitudeOf(location)
                edit.putLong("last-fix-lat", location.latitude.toRawBits())
                    .putLong("last-fix-lon", location.longitude.toRawBits())
                    .putLong("last-fix-time", if (location.time > 0L) location.time else System.currentTimeMillis())
                if (alt != null) edit.putFloat("last-fix-alt", alt) else edit.remove("last-fix-alt")
            }
            edit.apply()
        }


    /** Size of Android Auto's own controls. Defaults to LARGE — this is worn with gloves. */
    var uiSize: UiSize
        get() = UiSize.fromId(prefs.getInt("aa-ui-size", UiSize.LARGE.id))
        set(value) { prefs.edit().putInt("aa-ui-size", value.id).apply() }

    // Flag to determine if the projection should stretch and ignore aspect ratio to fill the screen
    var stretchToFill: Boolean
        get() = prefs.getBoolean("stretch_to_fill", true)
        set(value) { prefs.edit().putBoolean("stretch_to_fill", value).apply() }

    // Forced scale for older devices (SurfaceView fix)
    var forcedScale: Boolean
        get() = prefs.getBoolean("forced_scale", false)
        set(value) { prefs.edit().putBoolean("forced_scale", value).apply() }

    // UI Scale percentage for Home

    // UI Scale percentage for Settings

    var micSampleRate: Int
        get() = prefs.getInt("mic-sample-rate", 16000)
        set(sampleRate) {
            prefs.edit().putInt("mic-sample-rate", sampleRate).apply()
        }

    var useGpsForNavigation: Boolean
        get() = prefs.getBoolean("gps-navigation", true)
        set(value) {
            prefs.edit().putBoolean("gps-navigation", value).apply()
        }

    var showNavigationNotifications: Boolean
        get() = prefs.getBoolean("show-navigation-notifications", false)
        set(value) {
            prefs.edit().putBoolean("show-navigation-notifications", value).apply()
        }

    /** Mirror phone now-playing (title, artist, duration, art) in the system media session. */
    var syncMediaSessionWithAaMetadata: Boolean
        get() = prefs.getBoolean(KEY_SYNC_MEDIA_SESSION_AA_METADATA, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SYNC_MEDIA_SESSION_AA_METADATA, value).apply()
        }

    var nightMode: NightMode
        get() {
            val value = prefs.getInt("night-mode", 0)
            val mode = NightMode.fromInt(value)
            return mode!!
        }
        set(nightMode) {
            prefs.edit().putInt("night-mode", nightMode.value).apply()
        }

    var nightModeThresholdLux: Int
        get() = prefs.getInt("night-mode-threshold-lux", 100)
        set(value) {
            prefs.edit().putInt("night-mode-threshold-lux", value).apply()
        }

    var nightModeThresholdBrightness: Int
        get() = prefs.getInt("night-mode-threshold-brightness", 100)
        set(value) {
            prefs.edit().putInt("night-mode-threshold-brightness", value).apply()
        }

    var keyCodes: MutableMap<Int, Int>
        get() {
            val set = prefs.getStringSet("key-codes", mutableSetOf())!!
            val map = mutableMapOf<Int, Int>()
            set.forEach {
                val codes = it.split("-")
                map[codes[0].toInt()] = codes[1].toInt()
            }
            return map
        }
        set(codesMap) {
            val list: List<String> = codesMap.map { "${it.key}-${it.value}" }
            prefs.edit().putStringSet("key-codes", list.toSet()).apply()
        }

    var exporterLogLevel: LogExporter.LogLevel
        get() = LogExporter.LogLevel.entries.getOrElse(prefs.getInt(KEY_LOG_LEVEL, LogExporter.LogLevel.INFO.ordinal)) { LogExporter.LogLevel.INFO }
        set(value) { prefs.edit().putInt(KEY_LOG_LEVEL, value.ordinal).apply() }

    enum class LogSource {
        LOGCAT,
        APPLOG_FILE
    }

    var logSource: LogSource
        get() = LogSource.entries.getOrElse(prefs.getInt(KEY_LOG_SOURCE, LogSource.LOGCAT.ordinal)) { LogSource.LOGCAT }
        set(value) { prefs.edit().putInt(KEY_LOG_SOURCE, value.ordinal).apply() }

    /** Whether log capture should be active across restarts. Default: false (disabled). */
    var exporterCaptureEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOG_CAPTURE_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_LOG_CAPTURE_ENABLED, value).apply() }
    val logLevel: Int get() = exporterLogLevel.logLevel

    /**
     * Renderer for the projection surface.
     *
     * The default is worked out from the API level, because there is no runtime fallback: whatever
     * this names is what gets built, and a wrong choice stays wrong. Android 5.0-8.1 devices
     * generally do better with GLES than with a TextureView; newer ones are fine either way, and
     * pre-Lollipop had distortion problems with SurfaceView. (This used to be applied by the setup
     * wizard, which meant it was only right if the user completed it — and was lost again on reset.)
     */
    var viewMode: ViewMode
        get() {
            val default = if (Build.VERSION.SDK_INT in
                Build.VERSION_CODES.LOLLIPOP..Build.VERSION_CODES.O_MR1
            ) ViewMode.GLES.value else ViewMode.TEXTURE.value
            return ViewMode.fromInt(prefs.getInt("view-mode", default))!!
        }
        set(viewMode) {
            prefs.edit().putInt("view-mode", viewMode.value).apply()
        }

    /** Landscape by default — this is a head unit, and the projection is locked to it anyway. */
    var screenOrientation: ScreenOrientation
        get() {
            val value = prefs.getInt(KEY_SCREEN_ORIENTATION, ScreenOrientation.LANDSCAPE.value)
            return ScreenOrientation.fromInt(value) ?: ScreenOrientation.LANDSCAPE
        }
        set(orientation) {
            prefs.edit().putInt(KEY_SCREEN_ORIENTATION, orientation.value).apply()
        }

    var staticBSSID: String?
        get() = try { prefs.getString("static-bssid", "0") } catch (e: Exception) { "0" } // Default 0 for Auto
        set(value) {
            prefs.edit().putString("static-bssid", value).apply()
        }

    var fakeSpeed: Boolean
        get() = prefs.getBoolean("fake_speed", true)
        set(value) {
            prefs.edit().putBoolean("fake_speed", value).apply()
        }

    var gestureHintShown: Boolean
        get() = prefs.getBoolean("gesture_hint_shown", false)
        set(value) {
            prefs.edit().putBoolean("gesture_hint_shown", value).apply()
        }

    // Custom Insets (Screen Margins)
    var insetLeft: Int
        get() = prefs.getInt("inset-left", 0)
        set(value) { prefs.edit().putInt("inset-left", value).apply() }

    var insetTop: Int
        get() = prefs.getInt("inset-top", 0)
        set(value) { prefs.edit().putInt("inset-top", value).apply() }

    var insetRight: Int
        get() = prefs.getInt("inset-right", 0)
        set(value) { prefs.edit().putInt("inset-right", value).apply() }

    var insetBottom: Int
        get() = prefs.getInt("inset-bottom", 0)
        set(value) { prefs.edit().putInt("inset-bottom", value).apply() }

    // Cached Surface Dimensions (used to avoid mismatch flicker on next start)
    var cachedSurfaceWidth: Int
        get() = prefs.getInt("cached-surface-width", 0)
        set(value) { prefs.edit().putInt("cached-surface-width", value).apply() }

    var cachedSurfaceHeight: Int
        get() = prefs.getInt("cached-surface-height", 0)
        set(value) { prefs.edit().putInt("cached-surface-height", value).apply() }

    var cachedSurfaceSettingsHash: Int
        get() = prefs.getInt("cached-surface-settings-hash", 0)
        set(value) { prefs.edit().putInt("cached-surface-settings-hash", value).apply() }

    var fullscreenMode: FullscreenMode
        get() {
            // Migration logic
            if (!prefs.contains("fullscreen-mode") && prefs.contains("start-in-fullscreen-mode")) {
                val old = prefs.getBoolean("start-in-fullscreen-mode", true)
                val migrated = if (old) FullscreenMode.IMMERSIVE else FullscreenMode.NONE
                prefs.edit().putInt("fullscreen-mode", migrated.value).apply()
                return migrated
            }
            val value = prefs.getInt("fullscreen-mode", FullscreenMode.IMMERSIVE.value)
            return FullscreenMode.fromInt(value) ?: FullscreenMode.IMMERSIVE
        }
        set(value) { prefs.edit().putInt("fullscreen-mode", value.value).apply() }

    var forceSoftwareDecoding: Boolean
        get() = prefs.getBoolean("force-software-decoding", false)
        set(value) { prefs.edit().putBoolean("force-software-decoding", value).apply() }

    var softwareVideoDecoder: SoftwareVideoDecoder
        get() {
            val value = prefs.getInt("software-video-decoder", SoftwareVideoDecoder.BUNDLED_FFMPEG.value)
            return SoftwareVideoDecoder.fromInt(value) ?: SoftwareVideoDecoder.BUNDLED_FFMPEG
        }
        set(value) { prefs.edit().putInt("software-video-decoder", value.value).apply() }

    var rightHandDrive: Boolean
        get() = prefs.getBoolean("right-hand-drive", false)
        set(value) { prefs.edit().putBoolean("right-hand-drive", value).apply() }

    // Vehicle info settings (sent to phone during Android Auto handshake)
    var vehicleDisplayName: String
        get() = prefs.getString("vehicle-display-name", "Bike's Name")!!
        set(value) { prefs.edit().putString("vehicle-display-name", value).apply() }

    var vehicleMake: String
        get() = prefs.getString("vehicle-make", "Bike's Brand")!!
        set(value) { prefs.edit().putString("vehicle-make", value).apply() }

    var vehicleModel: String
        get() = prefs.getString("vehicle-model", "Bike's Model")!!
        set(value) { prefs.edit().putString("vehicle-model", value).apply() }

    var vehicleYear: String
        get() = prefs.getString("vehicle-year", "2026")!!
        set(value) { prefs.edit().putString("vehicle-year", value).apply() }

    var vehicleId: String
        get() = prefs.getString("vehicle-id", "ceon-headunit-001")!!
        set(value) { prefs.edit().putString("vehicle-id", value).apply() }


    // 0 = Manual, 1 = Auto (Headunit Server), 2 = Helper (Wifi Launcher), 3 = Native AA
    var wifiConnectionMode: Int
        get() {
            // Migration: Check if old helper boolean exists
            if (prefs.contains("wifi-launcher-mode")) {
                val old = prefs.getBoolean("wifi-launcher-mode", false)
                val newMode = if (old) 2 else 1
                prefs.edit().putInt("wifi-connection-mode", newMode).remove("wifi-launcher-mode").apply()
                return newMode
            }
            // Migration: Check if native-aa-wireless was true
            if (prefs.getBoolean("native-aa-wireless", false)) {
                prefs.edit().putInt("wifi-connection-mode", 3).remove("native-aa-wireless").apply()
                return 3
            }
            // Provisioning default for this motorcycle build: Native AA (3) so a
            // fresh install / settings-reset comes up ready to connect to the phone
            // WITHOUT a helper app. Upstream default was 2 (Wireless Helper).
            return prefs.getInt("wifi-connection-mode", 3)
        }
        set(value) { prefs.edit().putInt("wifi-connection-mode", value).apply() }

    var videoCodec: String
        get() = prefs.getString("video-codec", "Auto")!!
        set(value) { prefs.edit().putString("video-codec", value).apply() }

    var fpsLimit: Int
        get() = prefs.getInt("fps-limit", 60)
        set(value) { prefs.edit().putInt("fps-limit", value).apply() }

    var hasAcceptedDisclaimer: Boolean
        get() = prefs.getBoolean("has-accepted-disclaimer", false)
        set(value) { prefs.edit().putBoolean("has-accepted-disclaimer", value).apply() }


    /**
     * Unread since the permission dashboard replaced the one-shot battery prompt in onCreate.
     * Kept so an existing install is not re-asked if the flag ever gets a reader again.
     */
    var hasRequestedBatteryExemption: Boolean
        get() = prefs.getBoolean("has-requested-battery-exemption", false)
        set(value) { prefs.edit().putBoolean("has-requested-battery-exemption", value).apply() }

    /**
     * Unread since the permission dashboard replaced MainActivity's overlay dialog. The Auto-Start
     * screen still asks for the overlay permission independently, so nothing was orphaned.
     */
    var hasRequestedOverlayPermission: Boolean
        get() = prefs.getBoolean("has-requested-overlay-permission", false)
        set(value) { prefs.edit().putBoolean("has-requested-overlay-permission", value).apply() }

    /**
     * True once the rider has closed the permission dashboard with Continue.
     *
     * Note what this is *not*: it is not "the screen has been shown". Backing out leaves it false
     * on purpose, so the screen comes back on the next launch. And it only ever silences the
     * recommended permissions — a missing required one reopens the dashboard every launch no matter
     * what this says, because Android Auto cannot connect at all in that state.
     */
    var hasDismissedPermissionSetup: Boolean
        get() = prefs.getBoolean("has-dismissed-permission-setup", false)
        set(value) { prefs.edit().putBoolean("has-dismissed-permission-setup", value).apply() }

    /**
     * Every runtime permission we have ever put a system dialog in front of.
     *
     * This exists because `shouldShowRequestPermissionRationale` cannot tell "never asked" from
     * "denied for good" — it returns false in both cases. Without this set the dashboard would send
     * a first-time user straight to the app settings page instead of showing the system dialog.
     *
     * Deliberately absent from [SettingsBackupManager]: permission state is device truth and must
     * not ride a settings backup from one head unit to another. That also means a settings reset
     * leaves it alone, which is what we want.
     */
    var askedPermissions: Set<String>
        get() {
            val asked = prefs.getStringSet("asked-permissions", null)
            if (asked != null) return asked
            // Upgrade, not a fresh install: MainActivity used to request the whole list on every
            // launch, so anything the user permanently denied back then would look "never asked"
            // here and the first tap on its card would open a dialog Android silently suppresses.
            val seed = if (prefs.contains("has-accepted-disclaimer")) LEGACY_REQUESTED_PERMISSIONS else emptySet()
            prefs.edit().putStringSet("asked-permissions", seed).apply()
            return seed
        }
        set(value) { prefs.edit().putStringSet("asked-permissions", value).apply() }

    /**
     * Records that a system dialog was put in front of [permission].
     *
     * Copy-on-write: `getStringSet` hands back the instance SharedPreferences is holding, so
     * mutating it in place corrupts the store without any error.
     */
    fun markPermissionAsked(permission: String) {
        val current = askedPermissions
        if (permission in current) return
        askedPermissions = current.toMutableSet().apply { add(permission) }
    }

    var autoConnectLastSession: Boolean
        // Provisioning default true for this build (upstream: false).
        get() = prefs.getBoolean("auto-connect-last-session", true)
        set(value) { prefs.edit().putBoolean("auto-connect-last-session", value).apply() }

    var autoConnectSingleUsbDevice: Boolean
        get() = prefs.getBoolean("auto-connect-single-usb", false)
        set(value) { prefs.edit().putBoolean("auto-connect-single-usb", value).apply() }

    var lastConnectionType: String
        get() = prefs.getString("last-connection-type", "")!!
        set(value) { prefs.edit().putString("last-connection-type", value).apply() }

    var lastConnectionIp: String
        get() = prefs.getString("last-connection-ip", "")!!
        set(value) { prefs.edit().putString("last-connection-ip", value).apply() }

    var lastConnectionUsbDevice: String
        get() = prefs.getString("last-connection-usb-device", "")!!
        set(value) { prefs.edit().putString("last-connection-usb-device", value).apply() }

    fun saveLastConnection(type: String, ip: String = "", usbDevice: String = "") {
        lastConnectionType = type
        lastConnectionIp = ip
        lastConnectionUsbDevice = usbDevice
    }

    fun clearLastConnection() {
        lastConnectionType = ""
        lastConnectionIp = ""
        lastConnectionUsbDevice = ""
    }

    var enableAudioSink: Boolean
        get() = prefs.getBoolean("enable-audio-sink", true)
        set(value) { prefs.edit().putBoolean("enable-audio-sink", value).apply() }

    var staticAudioFocus: Boolean
        get() = prefs.getBoolean("static-audio-focus", false)
        set(value) { prefs.edit().putBoolean("static-audio-focus", value).apply() }

    var separateAudioStreams: Boolean
        get() = prefs.getBoolean("separate-audio-streams", false)
        set(value) { prefs.edit().putBoolean("separate-audio-streams", value).apply() }

    var micInputSource: Int
        get() = prefs.getInt("mic-input-source", 0) // Default: DEFAULT
        set(value) { prefs.edit().putInt("mic-input-source", value).apply() }

    var audioLatencyMultiplier: Int
        get() = prefs.getInt("audio-latency-multiplier", 8)
        set(value) { prefs.edit().putInt("audio-latency-multiplier", value).apply() }

    var audioQueueCapacity: Int
        get() = prefs.getInt("audio-queue-capacity", 0)
        set(value) { prefs.edit().putInt("audio-queue-capacity", value).apply() }

    var useAacAudio: Boolean
        get() = prefs.getBoolean("use-aac-audio", false)
        set(value) { prefs.edit().putBoolean("use-aac-audio", value).apply() }

    var micEchoCanceler: Boolean
        get() = prefs.getBoolean("mic-echo-canceler", false)
        set(value) { prefs.edit().putBoolean("mic-echo-canceler", value).apply() }

    var micNoiseSuppressor: Boolean
        get() = prefs.getBoolean("mic-noise-suppressor", false)
        set(value) { prefs.edit().putBoolean("mic-noise-suppressor", value).apply() }

    var micAutoGainControl: Boolean
        get() = prefs.getBoolean("mic-auto-gain-control", false)
        set(value) { prefs.edit().putBoolean("mic-auto-gain-control", value).apply() }

    var autoStartSelfMode: Boolean
        get() = prefs.getBoolean("auto-start-self-mode", false)
        set(value) { prefs.edit().putBoolean("auto-start-self-mode", value).apply() }

    var autoStartOnUsb: Boolean
        get() = prefs.getBoolean("auto-start-on-usb", false)
        set(value) { prefs.edit().putBoolean("auto-start-on-usb", value).apply() }

    var autoStartOnBoot: Boolean
        // Provisioning default true for this build (upstream: false). Kept in sync
        // with the device-protected copy in [isAutoStartOnBootEnabled] and [reset]
        // so the locked-boot receiver and the UI never disagree.
        get() = prefs.getBoolean("auto-start-on-boot", true)
        set(value) { prefs.edit().putBoolean("auto-start-on-boot", value).apply() }

    // When true (default, the behaviour before this setting existed), a session the rider ends
    // from the phone (USER_SELECTION byebye) is treated like a dropped link and reconnected right
    // away. When false it is treated like our own Exit: no auto-reconnect until the screen is
    // turned off and on or the Android Auto card is tapped. Also when false, any session the rider
    // ends (phone or Exit) releases the Wi-Fi Direct group so the phone lets go; when true our
    // Exit keeps the group up as before. Range loss is never affected.
    var stayConnected: Boolean
        get() = prefs.getBoolean("stay-connected", true)
        set(value) { prefs.edit().putBoolean("stay-connected", value).apply() }

    // When true, the app acts as the device HOME launcher (via the enabled-toggled
    // LauncherAlias). See [com.andrerinas.headunitrevived.utils.LauncherMode].
    var useAsLauncher: Boolean
        get() = prefs.getBoolean("use-as-launcher", false)
        set(value) { prefs.edit().putBoolean("use-as-launcher", value).apply() }

    /**
     * Ride lean-gauge recorded maxima (degrees), persisted so a session's deepest lean survives a
     * reboot. Reset only by a long-press on the gauge ([com.andrerinas.headunitrevived.sensors.LeanEstimator.resetMax]).
     */
    var leanMaxLeft: Int
        get() = prefs.getInt("lean-max-left", 0)
        set(value) { prefs.edit().putInt("lean-max-left", value).apply() }

    var leanMaxRight: Int
        get() = prefs.getInt("lean-max-right", 0)
        set(value) { prefs.edit().putInt("lean-max-right", value).apply() }

    /**
     * Which card the Ride screen's third slot is showing, stored as the card's STABLE STRING ID
     * (see RideCards) rather than an index - so a later release can insert a card anywhere in the
     * list without moving anyone's choice.
     *
     * Empty means "never chosen", which resolves to the default card. An id this build does not
     * recognise also falls back to the default but is deliberately left in place, so a card that
     * comes back later restores the rider's pick.
     */
    var rideCardId: String
        get() = prefs.getString(KEY_RIDE_CARD_ID, "")!!
        set(value) { prefs.edit().putString(KEY_RIDE_CARD_ID, value).apply() }

    /**
     * Ride G-force recorded maxima, in **milli-g** (902 = 0.902 g) so they fit the Int-only
     * preference shape the lean maxima already use. Persisted so a session's hardest launch and
     * hardest stop survive a reboot; reset only by a long-press on the gauge
     * ([com.andrerinas.headunitrevived.sensors.GForceEstimator.resetMax]).
     *
     * Deliberately absent from SettingsBackupManager, exactly like lean-max-*: this is ride state,
     * not a preference, so it neither travels between devices nor is cleared by Reset.
     */
    var gforceMaxAcc: Int
        get() = prefs.getInt("gforce-max-acc", 0)
        set(value) { prefs.edit().putInt("gforce-max-acc", value).apply() }

    var gforceMaxBrake: Int
        get() = prefs.getInt("gforce-max-brake", 0)
        set(value) { prefs.edit().putInt("gforce-max-brake", value).apply() }

    var autoStartOnScreenOn: Boolean
        get() = prefs.getBoolean("auto-start-on-screen-on", false)
        set(value) { prefs.edit().putBoolean("auto-start-on-screen-on", value).apply() }

    var autoStartOnWifi: Boolean
        get() = prefs.getBoolean("auto-start-on-wifi", false)
        set(value) { prefs.edit().putBoolean("auto-start-on-wifi", value).apply() }

    var autoStartWifiSsid: String
        get() = prefs.getString("auto-start-wifi-ssid", "")!!
        set(value) { prefs.edit().putString("auto-start-wifi-ssid", value).apply() }

    var listenForUsbDevices: Boolean
        get() = prefs.getBoolean("listen-for-usb-devices", true)
        set(value) { prefs.edit().putBoolean("listen-for-usb-devices", value).apply() }

    var reopenOnReconnection: Boolean
        get() = prefs.getBoolean("reopen-on-reconnection", true)
        set(value) { prefs.edit().putBoolean("reopen-on-reconnection", value).apply() }

    var autoConnectPriorityOrder: List<String>
        get() {
            val stored = prefs.getString("auto-connect-priority-order", null)
            val order = if (stored.isNullOrEmpty()) {
                DEFAULT_AUTO_CONNECT_ORDER.toMutableList()
            } else {
                stored.split(",").toMutableList()
            }
            // Migration safety: append any missing methods at end
            for (method in DEFAULT_AUTO_CONNECT_ORDER) {
                if (method !in order) {
                    order.add(method)
                }
            }
            // Remove unknown methods
            order.retainAll(DEFAULT_AUTO_CONNECT_ORDER)
            return order
        }
        set(value) {
            prefs.edit().putString("auto-connect-priority-order", value.joinToString(",")).apply()
        }

    var autoStartBluetoothDeviceMacs: Set<String>
        get() {
            var macs = prefs.getStringSet("auto-start-bt-macs", null)
            if (macs == null) {
                val legacyMac = prefs.getString("auto-start-bt-mac", "") ?: ""
                macs = if (legacyMac.isNotEmpty()) setOf(legacyMac) else emptySet()
                prefs.edit().putStringSet("auto-start-bt-macs", macs).apply()
            }
            return macs
        }
        set(value) {
            prefs.edit().putStringSet("auto-start-bt-macs", value).apply()
        }

    var autoStartBluetoothDeviceName: String
        get() = prefs.getString("auto-start-bt-name", "")!!
        set(value) { prefs.edit().putString("auto-start-bt-name", value).apply() }

    var autoStartBluetoothDeviceMac: String
        get() = autoStartBluetoothDeviceMacs.firstOrNull() ?: ""
        set(value) {
            val current = autoStartBluetoothDeviceMacs.toMutableSet()
            if (value.isNotEmpty()) {
                current.add(value)
            } else {
                current.clear()
            }
            autoStartBluetoothDeviceMacs = current
        }

    var appLanguage: String
        get() = prefs.getString("app-language", "")!!
        set(value) { prefs.edit().putString("app-language", value).apply() }

    var mediaVolumeOffset: Int
        get() = prefs.getInt("media-volume-offset", 0)
        set(value) { prefs.edit().putInt("media-volume-offset", value).apply() }

    var assistantVolumeOffset: Int
        get() = prefs.getInt("assistant-volume-offset", 0)
        set(value) { prefs.edit().putInt("assistant-volume-offset", value).apply() }

    var navigationVolumeOffset: Int
        get() = prefs.getInt("navigation-volume-offset", 0)
        set(value) { prefs.edit().putInt("navigation-volume-offset", value).apply() }

    // Custom loading screen
    var loadingScreenMediaPath: String
        get() = prefs.getString("loading-screen-media-path", "")!!
        set(value) { prefs.edit().putString("loading-screen-media-path", value).apply() }

    var loadingScreenMediaType: String
        get() = prefs.getString("loading-screen-media-type", "")!!
        set(value) { prefs.edit().putString("loading-screen-media-type", value).apply() }

    var loadingScreenShowText: Boolean
        get() = prefs.getBoolean("loading-screen-show-text", false)
        set(value) { prefs.edit().putBoolean("loading-screen-show-text", value).apply() }

    var loadingScreenKeepAspectRatio: Boolean
        get() = prefs.getBoolean("loading-screen-keep-aspect-ratio", true)
        set(value) { prefs.edit().putBoolean("loading-screen-keep-aspect-ratio", value).apply() }

    var loadingScreenLoopVideo: Boolean
        get() = prefs.getBoolean("loading-screen-loop-video", true)
        set(value) { prefs.edit().putBoolean("loading-screen-loop-video", value).apply() }

    var loadingScreenScalePercent: Int
        get() = prefs.getInt("loading-screen-scale-percent", 100)
        set(value) { prefs.edit().putInt("loading-screen-scale-percent", value).apply() }

    @SuppressLint("ApplySharedPref")
    fun commit() {
        prefs.edit().commit()
    }

    @SuppressLint("ApplySharedPref")
    fun reset() {
        prefs.edit().clear().commit()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Provisioning default: auto-start-on-boot returns to true after a reset
            // (matches the getter/device-read defaults). All other auto-start flags
            // reset to their upstream defaults below.
            syncAutoStartOnBootToDeviceStorage(context, true)
            syncAutoStartOnScreenOnToDeviceStorage(context, false)
            syncAutoStartOnUsbToDeviceStorage(context, false)
            syncAutoStartOnWifiToDeviceStorage(context, false)
            syncListenForUsbDevicesToDeviceStorage(context, true)
            syncAutoStartBtMacToDeviceStorage(context, "")
        }
    }

    /**
     * How large Android Auto's own controls should be drawn.
     *
     * [canvasDpWidth] is the width, in dp, that the video frame should represent. Android Auto sizes
     * its UI in dp, so a narrower canvas means everything is drawn proportionally bigger. The head
     * unit picks the resolution and density together to hit it — see HeadUnitScreenConfig.
     */
    enum class UiSize(val id: Int, val labelRes: Int, val canvasDpWidth: Float) {
        SMALL(0, R.string.ui_size_small, 1280f),
        MEDIUM(1, R.string.ui_size_medium, 1067f),
        LARGE(2, R.string.ui_size_large, 853f);

        companion object {
            private val map = values().associateBy(UiSize::id)
            fun fromId(id: Int) = map[id] ?: LARGE
        }
    }

    enum class SoftwareVideoDecoder(val value: Int) {
        DEVICE_MEDIACODEC(0),
        BUNDLED_FFMPEG(1);

        companion object {
            private val map = values().associateBy(SoftwareVideoDecoder::value)
            fun fromInt(value: Int) = map[value]
        }
    }

    enum class NightMode(val value: Int) {
        AUTO(0),
        DAY(1),
        NIGHT(2),
        MANUAL_TIME(3),
        LIGHT_SENSOR(4),
        SCREEN_BRIGHTNESS(5),
        CAR_SIGNAL(6);

        companion object {
            private val map = NightMode.values().associateBy(NightMode::value)
            fun fromInt(value: Int) = map[value]
        }
    }

    var nightModeManualStart: Int
        get() = prefs.getInt("night-mode-manual-start", 1140) // Default 19:00 (19 * 60)
        set(value) {
            prefs.edit().putInt("night-mode-manual-start", value).apply()
        }

    var nightModeManualEnd: Int
        get() = prefs.getInt("night-mode-manual-end", 420) // Default 07:00 (7 * 60)
        set(value) {
            prefs.edit().putInt("night-mode-manual-end", value).apply()
        }

    // App Theme independent threshold/time settings (separate from Night Mode)
    var appThemeThresholdLux: Int
        get() = prefs.getInt("app-theme-threshold-lux", 100)
        set(value) { prefs.edit().putInt("app-theme-threshold-lux", value).apply() }

    var appThemeThresholdBrightness: Int
        get() = prefs.getInt("app-theme-threshold-brightness", 100)
        set(value) { prefs.edit().putInt("app-theme-threshold-brightness", value).apply() }

    var appThemeManualStart: Int
        get() = prefs.getInt("app-theme-manual-start", 1140)
        set(value) { prefs.edit().putInt("app-theme-manual-start", value).apply() }

    var appThemeManualEnd: Int
        get() = prefs.getInt("app-theme-manual-end", 420)
        set(value) { prefs.edit().putInt("app-theme-manual-end", value).apply() }
    var showFpsCounter: Boolean
        get() = prefs.getBoolean("show-fps-counter", false)
        set(value) {
            prefs.edit().putBoolean("show-fps-counter", value).apply()
        }

    companion object {
        const val PREFS_NAME = "settings"

        /**
         * What MainActivity used to blast on every launch, before the permission dashboard.
         * Seeds [askedPermissions] on upgrade so a previously denied permission is recognised as
         * "already asked" and its card routes straight to the app settings page.
         */
        private val LEGACY_REQUESTED_PERMISSIONS = setOf(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.BLUETOOTH_ADVERTISE,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.NEARBY_WIFI_DEVICES,
            android.Manifest.permission.POST_NOTIFICATIONS
        )

        const val CONNECTION_TYPE_WIFI = "wifi"
        const val CONNECTION_TYPE_USB = "usb"
        const val CONNECTION_TYPE_NEARBY = "nearby"

        /** SharedPreferences key; also used by [AapService] for change listener. */
        const val KEY_SYNC_MEDIA_SESSION_AA_METADATA = "sync-media-session-aa-metadata"
        const val KEY_RIDE_CARD_ID = "ride-card-id"

        /** SharedPreferences key; also used by [AapService] for change listener. */
        const val KEY_LOG_LEVEL = "log-level"
        const val KEY_LOG_SOURCE = "log-source"
        /** Persist whether log capture should be active across restarts. */
        const val KEY_LOG_CAPTURE_ENABLED = "log-capture-enabled"

        const val KEY_MEDIA_VOLUME_OFFSET = "media-volume-offset"
        const val KEY_ASSISTANT_VOLUME_OFFSET = "assistant-volume-offset"
        const val KEY_NAVIGATION_VOLUME_OFFSET = "navigation-volume-offset"

        const val AUTO_CONNECT_LAST_SESSION = "last-session"
        const val AUTO_CONNECT_SELF_MODE = "self-mode"
        const val AUTO_CONNECT_SINGLE_USB = "single-usb"

        val DEFAULT_AUTO_CONNECT_ORDER = listOf(
            AUTO_CONNECT_LAST_SESSION,
            AUTO_CONNECT_SELF_MODE,
            AUTO_CONNECT_SINGLE_USB
        )

        private const val DEVICE_PREFS_NAME = "settings_device_protected"
        private const val KEY_AUTO_START_ON_BOOT = "auto-start-on-boot"

        /**
         * Reads auto-start-on-boot from device-protected storage (API 24+),
         * falling back to regular prefs on older devices.
         * Safe to call during locked boot when credential storage is unavailable.
         */
        fun isAutoStartOnBootEnabled(context: Context): Boolean {
            val prefs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val deviceContext = context.createDeviceProtectedStorageContext()
                deviceContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
            } else {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
            // Provisioning default true — must match the credential-storage default
            // in the [autoStartOnBoot] getter above.
            return prefs.getBoolean(KEY_AUTO_START_ON_BOOT, true)
        }

        /**
         * Syncs the auto-start-on-boot value to device-protected storage.
         * Call this whenever the user saves settings.
         */
        fun syncAutoStartOnBootToDeviceStorage(context: Context, enabled: Boolean) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val deviceContext = context.createDeviceProtectedStorageContext()
                deviceContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_AUTO_START_ON_BOOT, enabled)
                    .apply()
            }
        }

        private const val KEY_AUTO_START_ON_SCREEN_ON = "auto-start-on-screen-on"

        /**
         * Reads auto-start-on-screen-on from device-protected storage (API 24+),
         * falling back to regular prefs on older devices.
         * Safe to call during locked boot when credential storage is unavailable.
         */
        fun isAutoStartOnScreenOnEnabled(context: Context): Boolean {
            val prefs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val deviceContext = context.createDeviceProtectedStorageContext()
                deviceContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
            } else {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
            return prefs.getBoolean(KEY_AUTO_START_ON_SCREEN_ON, false)
        }

        fun syncAutoStartOnScreenOnToDeviceStorage(context: Context, enabled: Boolean) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val deviceContext = context.createDeviceProtectedStorageContext()
                deviceContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_AUTO_START_ON_SCREEN_ON, enabled)
                    .apply()
            }
        }

        private const val KEY_AUTO_START_ON_USB = "auto-start-on-usb"
        const val KEY_SCREEN_ORIENTATION = "screen-orientation"
        private const val KEY_LISTEN_FOR_USB_DEVICES = "listen-for-usb-devices"
        private const val KEY_AUTO_START_BT_MAC = "auto-start-bt-mac"
        private const val KEY_AUTO_START_BT_MACS = "auto-start-bt-macs"

        /**
         * Reads auto-start-on-usb from device-protected storage (API 24+),
         * falling back to regular prefs on older devices.
         * Safe to call during locked boot when credential storage is unavailable.
         */
        fun isAutoStartOnUsbEnabled(context: Context): Boolean {
            val prefs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val deviceContext = context.createDeviceProtectedStorageContext()
                deviceContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
            } else {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
            return prefs.getBoolean(KEY_AUTO_START_ON_USB, false)
        }

        fun syncAutoStartOnUsbToDeviceStorage(context: Context, enabled: Boolean) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val deviceContext = context.createDeviceProtectedStorageContext()
                deviceContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_AUTO_START_ON_USB, enabled)
                    .apply()
            }
        }

        private const val KEY_AUTO_START_ON_WIFI = "auto-start-on-wifi"
        private const val KEY_AUTO_START_WIFI_SSID = "auto-start-wifi-ssid"

        fun isAutoStartOnWifiEnabled(context: Context): Boolean {
            val prefs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val deviceContext = context.createDeviceProtectedStorageContext()
                deviceContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
            } else {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
            return prefs.getBoolean(KEY_AUTO_START_ON_WIFI, false)
        }

        fun syncAutoStartOnWifiToDeviceStorage(context: Context, enabled: Boolean) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val deviceContext = context.createDeviceProtectedStorageContext()
                deviceContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_AUTO_START_ON_WIFI, enabled)
                    .apply()
            }
        }

        fun getAutoStartWifiSsid(context: Context): String {
            val prefs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val deviceContext = context.createDeviceProtectedStorageContext()
                deviceContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
            } else {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
            return prefs.getString(KEY_AUTO_START_WIFI_SSID, "") ?: ""
        }

        fun syncAutoStartWifiSsidToDeviceStorage(context: Context, ssid: String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val deviceContext = context.createDeviceProtectedStorageContext()
                deviceContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_AUTO_START_WIFI_SSID, ssid)
                    .apply()
            }
        }

        fun isListenForUsbDevicesEnabled(context: Context): Boolean {
            val prefs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val deviceContext = context.createDeviceProtectedStorageContext()
                deviceContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
            } else {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
            return prefs.getBoolean(KEY_LISTEN_FOR_USB_DEVICES, true) // Default is TRUE
        }

        fun syncListenForUsbDevicesToDeviceStorage(context: Context, enabled: Boolean) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val deviceContext = context.createDeviceProtectedStorageContext()
                deviceContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_LISTEN_FOR_USB_DEVICES, enabled)
                    .apply()
            }
        }


        fun setUsbAttachedActivityEnabled(context: Context, enabled: Boolean) {
            val component = ComponentName(context, UsbAttachedActivity::class.java)
            val newState = if (enabled)
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (context.packageManager.getComponentEnabledSetting(component) != newState) {
                context.packageManager.setComponentEnabledSetting(
                    component, newState, PackageManager.DONT_KILL_APP
                )
            }
        }

        /**
         * Reads the Bluetooth auto-start MAC from device-protected storage (API 24+),
         * falling back to regular prefs on older devices.
         */
        fun getAutoStartBtMac(context: Context): String {
            val macs = getAutoStartBtMacs(context)
            if (macs.isNotEmpty()) {
                return macs.first()
            }
            val prefs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val deviceContext = context.createDeviceProtectedStorageContext()
                deviceContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
            } else {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
            return prefs.getString(KEY_AUTO_START_BT_MAC, "") ?: ""
        }

        fun syncAutoStartBtMacToDeviceStorage(context: Context, mac: String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val deviceContext = context.createDeviceProtectedStorageContext()
                deviceContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_AUTO_START_BT_MAC, mac)
                    .apply()
            }
        }

        fun getAutoStartBtMacs(context: Context): Set<String> {
            val prefs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val deviceContext = context.createDeviceProtectedStorageContext()
                deviceContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
            } else {
                context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            }
            var macs = prefs.getStringSet(KEY_AUTO_START_BT_MACS, null)
            if (macs == null) {
                val legacyMac = prefs.getString(KEY_AUTO_START_BT_MAC, "") ?: ""
                macs = if (legacyMac.isNotEmpty()) setOf(legacyMac) else emptySet()
            }
            return macs
        }

        fun syncAutoStartBtMacsToDeviceStorage(context: Context, macs: Set<String>) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val deviceContext = context.createDeviceProtectedStorageContext()
                deviceContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putStringSet(KEY_AUTO_START_BT_MACS, macs)
                    .apply()
            }
        }

        val MicSampleRates = listOf(8000, 16000, 24000, 32000, 44100, 48000) // Changed to List

        fun getNextMicSampleRate(currentRate: Int): Int {
            val currentIndex = MicSampleRates.indexOf(currentRate)
            return if (currentIndex != -1 && currentIndex < MicSampleRates.size - 1) {
                MicSampleRates[currentIndex + 1]
            } else {
                MicSampleRates.first() // Loop back to first if at end or not found
            }
        }

        // NightMode is now an enum, so we can iterate its values directly
    }

    enum class ViewMode(val value: Int) {
        SURFACE(0),
        TEXTURE(1),
        GLES(2);

        companion object {
            private val map = values().associateBy(ViewMode::value)
            fun fromInt(value: Int) = map[value]
        }
    }

    enum class ScreenOrientation(val value: Int, val androidOrientation: Int) {
        SYSTEM(0, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER),
        AUTO(1, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR),
        LANDSCAPE(2, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE),
        LANDSCAPE_REVERSE(3, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE),
        PORTRAIT(4, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
        PORTRAIT_REVERSE(5, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);

        companion object {
            private val map = values().associateBy(ScreenOrientation::value)
            fun fromInt(value: Int) = map[value]
        }
    }

    enum class FullscreenMode(val value: Int) {
        NONE(0),
        IMMERSIVE(1),
        STATUS_ONLY(2),
        IMMERSIVE_WITH_NOTCH(3);

        companion object {
            private val map = values().associateBy(FullscreenMode::value)
            fun fromInt(value: Int) = map[value]
        }
    }

    enum class AppTheme(val value: Int) {
        AUTOMATIC(0),
        CLEAR(1),
        DARK(2),
        EXTREME_DARK(3),
        AUTO_SUNRISE(4),
        MANUAL_TIME(5),
        LIGHT_SENSOR(6),
        SCREEN_BRIGHTNESS(7),
        CAR_SIGNAL(8);

        companion object {
            private val map = values().associateBy(AppTheme::value)
            fun fromInt(value: Int) = map[value] ?: AUTOMATIC
        }
    }

    var useExtremeDarkMode: Boolean
        get() = prefs.getBoolean("use-extreme-dark-mode", false)
        set(value) { prefs.edit().putBoolean("use-extreme-dark-mode", value).apply() }

    var aaMonochromeEnabled: Boolean
        get() = prefs.getBoolean("aa-monochrome-enabled", false)
        set(value) { prefs.edit().putBoolean("aa-monochrome-enabled", value).apply() }

    var aaDesaturationLevel: Int
        get() = prefs.getInt("aa-desaturation-level", 100)
        set(value) { prefs.edit().putInt("aa-desaturation-level", value).apply() }

    var appTheme: AppTheme
        get() {
            val value = prefs.getInt("app-theme", 0)
            return AppTheme.fromInt(value) ?: AppTheme.AUTOMATIC
        }
        set(theme) {
            prefs.edit().putInt("app-theme", theme.value).apply()
        }

    var enableRotary: Boolean
        get() = prefs.getBoolean("enable-rotary", false)
        set(value) { prefs.edit().putBoolean("enable-rotary", value).apply() }

    var killOnDisconnect: Boolean
        get() = prefs.getBoolean("kill-on-disconnect", false)
        set(value) { prefs.edit().putBoolean("kill-on-disconnect", value).apply() }

    var autoEnableHotspot: Boolean
        get() = prefs.getBoolean("auto-enable-hotspot", false)
        set(value) { prefs.edit().putBoolean("auto-enable-hotspot", value).apply() }

    var waitForWifiBeforeWifiDirect: Boolean
        get() = prefs.getBoolean("wait-for-wifi-before-wifi-direct", false)
        set(value) { prefs.edit().putBoolean("wait-for-wifi-before-wifi-direct", value).apply() }

    var waitForWifiTimeout: Int
        get() = prefs.getInt("wait-for-wifi-timeout", 10)
        set(value) { prefs.edit().putInt("wait-for-wifi-timeout", value).apply() }

    // 0 = Common Wifi (NSD), 1 = Wifi Direct P2P, 2 = Nearby Devices, 3 = Phone Hotspot (Host), 4 = Headunit Hotspot (Passive)
    var helperConnectionStrategy: Int
        get() = prefs.getInt("helper-connection-strategy", 2) // Default to Nearby Devices (2)
        set(value) = prefs.edit().putInt("helper-connection-strategy", value).apply()

    var lastNearbyDeviceName: String
        get() = prefs.getString("last-nearby-device-name", "")!!
        set(value) = prefs.edit().putString("last-nearby-device-name", value).apply()

    var bluetoothManagerServiceName: String
        get() = prefs.getString("bluetooth-manager-service-name", "bluetooth_manager")!!
        set(value) = prefs.edit().putString("bluetooth-manager-service-name", value).apply()

    var hotspotPassword: String
        get() = prefs.getString("hotspot-password", "")!!
        set(value) = prefs.edit().putString("hotspot-password", value).apply()

    var useLibusb: Boolean
        get() = prefs.getBoolean("use-libusb", false)
        set(value) = prefs.edit().putBoolean("use-libusb", value).apply()

}
