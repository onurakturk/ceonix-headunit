package com.andrerinas.headunitrevived.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings as AndroidSettings

/**
 * Toggles the app's optional HOME-launcher capability.
 *
 * The capability lives in a manifest `<activity-alias>` (`.main.LauncherAlias`, targeting
 * MainActivity) that ships DISABLED. Enabling/disabling that component is the only
 * version-independent way to add/remove a HOME intent-filter at runtime (an intent-filter
 * itself cannot be changed dynamically). Works identically on every API level and needs no
 * permission because the component belongs to this app.
 *
 * The user still picks the default home themselves via [openHomeSettings]
 * (Settings.ACTION_HOME_SETTINGS) — the universal, RoleManager-free path.
 */
object LauncherMode {

    private const val ALIAS = "com.andrerinas.headunitrevived.main.LauncherAlias"

    /** Enable/disable the HOME alias component. Does not kill the app process. */
    fun setEnabled(context: Context, enabled: Boolean) {
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        try {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context.packageName, ALIAS),
                state,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {
        }
    }

    /** Open the system "Home app" picker so the user can select (or leave) this app. */
    fun openHomeSettings(context: Context) {
        try {
            context.startActivity(
                Intent(AndroidSettings.ACTION_HOME_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }
}
