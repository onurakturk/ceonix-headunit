package com.andrerinas.headunitrevived.main

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.navigation.fragment.NavHostFragment
import android.content.res.Configuration
import android.os.Build
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.app.BaseActivity
import com.andrerinas.headunitrevived.utils.Settings
import com.andrerinas.headunitrevived.utils.SystemUI

class SettingsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = Settings(this).screenOrientation.androidOrientation
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val appSettings = Settings(this)
        val isNightActive = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (appSettings.appTheme == Settings.AppTheme.EXTREME_DARK ||
            (appSettings.useExtremeDarkMode && isNightActive)) {
            theme.applyStyle(R.style.ThemeOverlay_ExtremeDark, true)
        }
        requestedOrientation = appSettings.screenOrientation.androidOrientation

        setContentView(R.layout.activity_settings)

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.settings_nav_host) as NavHostFragment
        val navController = navHostFragment.navController

        // Set the start destination to settingsFragment instead of homeFragment
        val navGraph = navController.navInflater.inflate(R.navigation.nav_graph)
        navGraph.startDestination = R.id.settingsFragment
        navController.graph = navGraph

        // Restore sub-screen after recreate() (e.g. theme change from DarkModeFragment, or
        // Screen mode from Settings › Display). Every settings section shares one destination
        // id, so the section argument has to be restored with it — without it the user would
        // land back on the hub instead of the screen they were on.
        val restoredDestination = savedInstanceState?.getInt(KEY_CURRENT_DESTINATION, 0) ?: 0
        val restoredSection = savedInstanceState?.getString(KEY_CURRENT_SECTION)
        val isSettingsSection = restoredDestination == R.id.settingsFragment &&
                restoredSection != null && restoredSection != SettingsFragment.SECTION_ROOT
        if (restoredDestination != 0 && (restoredDestination != R.id.settingsFragment || isSettingsSection)) {
            try {
                val args = restoredSection?.let {
                    Bundle().apply { putString(SettingsFragment.ARG_SECTION, it) }
                }
                navController.navigate(restoredDestination, args)
            } catch (_: Exception) {}
        }

        val root = findViewById<View>(R.id.settings_nav_host)
        SystemUI.apply(window, root, appSettings.fullscreenMode)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.settings_nav_host) as? NavHostFragment
        val navController = navHostFragment?.navController
        val currentDest = navController?.currentDestination?.id ?: 0
        outState.putInt(KEY_CURRENT_DESTINATION, currentDest)
        navController?.currentBackStackEntry?.arguments?.getString(SettingsFragment.ARG_SECTION)?.let {
            outState.putString(KEY_CURRENT_SECTION, it)
        }
    }

    companion object {
        private const val KEY_CURRENT_DESTINATION = "current_nav_destination"
        private const val KEY_CURRENT_SECTION = "current_settings_section"
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val appSettings = Settings(this)
            val root = findViewById<View>(R.id.settings_nav_host)
            SystemUI.apply(window, root, appSettings.fullscreenMode)
        }
    }
}
