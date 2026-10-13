package com.andrerinas.headunitrevived.main.permissions

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.utils.Settings

/**
 * What the permission dashboard shows, and how each row works out whether it is satisfied.
 *
 * The screen exists because the first thing a new rider used to see was eight system dialogs in a
 * row, none of which said what the permission was for, followed by an unlabelled battery settings
 * screen. Worse, nothing ever read the result: a denied Bluetooth permission means Android Auto
 * never connects, with no error line anywhere — the same silent failure shape as a stale BSSID.
 *
 * Cards are grouped by what the rider would recognise, not by manifest entry: "Bluetooth" is three
 * permissions, "Location" is two. The special-access rows (overlay, battery) are not runtime
 * permissions at all — they are system settings screens — but they belong here for the same reason:
 * they are things that must be true before the head unit works, and they were previously asked for
 * in isolation with no way to see afterwards whether they had taken.
 */
enum class PermissionKind {
    /** Gates the Continue button. Every card ships as this one — see [PermissionItem.SPECS]. */
    REQUIRED,

    /**
     * Something works worse without it: shown in amber, never blocks, and silenced for good once
     * the rider closes the screen with Continue. Currently unused, kept so a single card can be
     * softened again without rebuilding the machinery.
     */
    RECOMMENDED
}

enum class PermissionStatus {
    GRANTED,

    /** Not held, but the system will still show a dialog if we ask. */
    DENIED,

    /**
     * Not held and the system now swallows the request. Only ever produced for
     * [PermissionRequest.Runtime] — a settings screen has no dialog to suppress.
     */
    PERMANENTLY_DENIED
}

sealed interface PermissionRequest {
    /** Requested together as one group, so the rider sees one dialog per card. */
    data class Runtime(val permissions: List<String>) : PermissionRequest

    /**
     * A system settings screen rather than a dialog.
     *
     * @param action the intent action to launch.
     * @param fallbackAction used when [action] is missing on the ROM, which happens for the
     *        battery exemption on some builds.
     * @param withPackageUri whether the intent carries `package:<us>` — the exemption request and
     *        the overlay screen both want it; the plain battery *list* screen does not.
     */
    data class SpecialAccess(
        val action: String,
        val fallbackAction: String? = null,
        val withPackageUri: Boolean = true
    ) : PermissionRequest
}

/** The static half of a card: everything that does not depend on the current grant state. */
data class PermissionGroupSpec(
    val id: String,
    @StringRes val title: Int,
    @StringRes val description: Int,
    @DrawableRes val icon: Int,
    val kind: PermissionKind,
    val request: PermissionRequest,
    /** Below this the card is omitted entirely, rather than shown as "not granted". */
    val minSdk: Int
)

/** One bound row. A pure value, so DiffUtil can compare it structurally. */
data class PermissionCard(val spec: PermissionGroupSpec, val status: PermissionStatus) {
    val isSatisfied: Boolean get() = status == PermissionStatus.GRANTED
}

data class DashboardState(
    val cards: List<PermissionCard>,
    val allRequiredGranted: Boolean,
    val requiredMissing: Int,
    val recommendedMissing: Int
) {
    companion object {
        val EMPTY = DashboardState(emptyList(), true, 0, 0)
    }
}

object PermissionItem {

    /**
     * Every card, in the order the rider works through them. The first five are required;
     * Microphone and Notifications are recommended.
     *
     * This started out split — only Bluetooth and Nearby Wi-Fi blocked the Continue button, on the
     * grounds that the rest merely degraded the experience. Field use said otherwise: an optional
     * permission could be dismissed once and then went quiet forever, so a rider who skipped
     * Location never found out why the Ride screen stayed blank. This is a set-and-forget head
     * unit; a half-configured one is worse than one that insists up front.
     *
     * For a while all seven were required. Microphone and Notifications went back to
     * [PermissionKind.RECOMMENDED] for the Play Store: the connection, the projection and the
     * Ride screen all work without them, and refusing to start over a microphone a stranger may
     * never use is hard to defend in review. They show amber, and Continue dismisses them.
     *
     * Note that on API 33+ the Bluetooth trio and Nearby Wi-Fi devices live in the same permission
     * group, so granting one auto-grants the other with no second dialog and both cards go green at
     * once. That is correct, just surprising — which is why the callers re-read every card after a
     * result rather than updating the one that was tapped.
     */
    val SPECS: List<PermissionGroupSpec> = listOf(
        PermissionGroupSpec(
            id = "bluetooth",
            title = R.string.permission_bluetooth_title,
            description = R.string.permission_bluetooth_desc,
            icon = R.drawable.ic_m3_bt,
            kind = PermissionKind.REQUIRED,
            request = PermissionRequest.Runtime(
                listOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE
                )
            ),
            minSdk = Build.VERSION_CODES.S
        ),
        PermissionGroupSpec(
            id = "nearby_wifi",
            title = R.string.permission_nearby_wifi_title,
            description = R.string.permission_nearby_wifi_desc,
            icon = R.drawable.ic_m3_wifi,
            kind = PermissionKind.REQUIRED,
            request = PermissionRequest.Runtime(listOf(Manifest.permission.NEARBY_WIFI_DEVICES)),
            minSdk = Build.VERSION_CODES.TIRAMISU
        ),
        PermissionGroupSpec(
            id = "location",
            title = R.string.permission_location_title,
            description = R.string.permission_location_desc,
            icon = R.drawable.ic_m3_gps,
            kind = PermissionKind.REQUIRED,
            request = PermissionRequest.Runtime(
                listOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            ),
            minSdk = Build.VERSION_CODES.M
        ),
        PermissionGroupSpec(
            id = "overlay",
            title = R.string.permission_overlay_title,
            description = R.string.permission_overlay_desc,
            icon = R.drawable.ic_m3_display,
            kind = PermissionKind.REQUIRED,
            request = PermissionRequest.SpecialAccess(
                action = android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION
            ),
            minSdk = Build.VERSION_CODES.M
        ),
        PermissionGroupSpec(
            id = "battery",
            title = R.string.permission_battery_title,
            description = R.string.permission_battery_desc,
            icon = R.drawable.ic_m3_battery,
            kind = PermissionKind.REQUIRED,
            request = PermissionRequest.SpecialAccess(
                action = android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                fallbackAction = android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
            ),
            minSdk = Build.VERSION_CODES.M
        ),
        PermissionGroupSpec(
            id = "microphone",
            title = R.string.permission_microphone_title,
            description = R.string.permission_microphone_desc,
            icon = R.drawable.ic_m3_audio,
            kind = PermissionKind.RECOMMENDED,
            request = PermissionRequest.Runtime(listOf(Manifest.permission.RECORD_AUDIO)),
            minSdk = Build.VERSION_CODES.M
        ),
        PermissionGroupSpec(
            id = "notifications",
            title = R.string.permission_notifications_title,
            description = R.string.permission_notifications_desc,
            icon = R.drawable.ic_m3_info,
            kind = PermissionKind.RECOMMENDED,
            request = PermissionRequest.Runtime(listOf(Manifest.permission.POST_NOTIFICATIONS)),
            minSdk = Build.VERSION_CODES.TIRAMISU
        )
    )

    /** The cards that apply to this device, in display order. */
    fun applicableSpecs(): List<PermissionGroupSpec> =
        SPECS.filter { Build.VERSION.SDK_INT >= it.minSdk }

    /** True when [spec] is satisfied right now. Application context is enough for every check. */
    fun isGranted(context: Context, spec: PermissionGroupSpec): Boolean = when (val r = spec.request) {
        is PermissionRequest.Runtime -> r.permissions.all {
            ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        is PermissionRequest.SpecialAccess -> when (spec.id) {
            "overlay" -> Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                    android.provider.Settings.canDrawOverlays(context)
            "battery" -> Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                    (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                        ?.isIgnoringBatteryOptimizations(context.packageName) ?: true
            else -> true
        }
    }

    /**
     * Works out the three-way status.
     *
     * The persisted "already asked" set is load-bearing:
     * [android.app.Activity.shouldShowRequestPermissionRationale] returns false *both* before the
     * first request and after a permanent denial, so on its own it cannot tell the two apart. Ask
     * the wrong way round and a first-time rider gets dumped in the app settings page instead of a
     * dialog.
     *
     * @param rationale delegates to the hosting fragment. Never retained by the caller.
     */
    fun statusOf(
        context: Context,
        settings: Settings,
        spec: PermissionGroupSpec,
        rationale: (String) -> Boolean
    ): PermissionStatus {
        if (isGranted(context, spec)) return PermissionStatus.GRANTED
        val request = spec.request as? PermissionRequest.Runtime ?: return PermissionStatus.DENIED
        val asked = settings.askedPermissions
        val everAsked = request.permissions.all { it in asked }
        val canStillAsk = request.permissions.any { rationale(it) }
        return if (everAsked && !canStillAsk) PermissionStatus.PERMANENTLY_DENIED else PermissionStatus.DENIED
    }

    /**
     * Whether anything that blocks a connection is missing. Called from MainActivity's first-run
     * chain, where no fragment (and so no rationale lookup) exists yet — grant state alone is
     * enough to decide whether the screen is worth showing.
     */
    fun anyRequiredMissing(context: Context): Boolean =
        applicableSpecs().any { it.kind == PermissionKind.REQUIRED && !isGranted(context, it) }

    /**
     * Whether anything merely recommended is missing. Unlike [anyRequiredMissing] this is a
     * silenceable condition: the setup chain stops offering the screen for it once the rider has
     * closed the dashboard with Continue.
     */
    fun anyRecommendedMissing(context: Context): Boolean =
        applicableSpecs().any { it.kind == PermissionKind.RECOMMENDED && !isGranted(context, it) }
}
