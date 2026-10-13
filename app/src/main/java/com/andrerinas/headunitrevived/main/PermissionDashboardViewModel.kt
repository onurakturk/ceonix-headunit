package com.andrerinas.headunitrevived.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.andrerinas.headunitrevived.main.permissions.DashboardState
import com.andrerinas.headunitrevived.main.permissions.PermissionCard
import com.andrerinas.headunitrevived.main.permissions.PermissionItem
import com.andrerinas.headunitrevived.main.permissions.PermissionKind
import com.andrerinas.headunitrevived.utils.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds what the permission dashboard is showing.
 *
 * There is no subscription to keep here — permission state only changes while we are away from the
 * screen, so the flow is recomputed on demand rather than observed. It is still a StateFlow so the
 * fragment renders from one place and survives rotation and the theme `recreate()` without a
 * flicker of stale rows.
 */
class PermissionDashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(DashboardState.EMPTY)
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    /**
     * Re-reads every card.
     *
     * @param rationale delegates to `Fragment.shouldShowRequestPermissionRationale`. Passed in per
     *        call and never stored: that API needs an Activity, and holding one in a ViewModel is
     *        exactly the leak ViewModels exist to prevent. The application context this class does
     *        keep is enough for every other check (grant state, overlay, battery).
     */
    fun refresh(rationale: (String) -> Boolean) {
        val context = getApplication<Application>()
        val settings = Settings(context)
        val cards = PermissionItem.applicableSpecs().map { spec ->
            PermissionCard(spec, PermissionItem.statusOf(context, settings, spec, rationale))
        }
        _state.value = DashboardState(
            cards = cards,
            allRequiredGranted = cards.none { it.spec.kind == PermissionKind.REQUIRED && !it.isSatisfied },
            requiredMissing = cards.count { it.spec.kind == PermissionKind.REQUIRED && !it.isSatisfied },
            recommendedMissing = cards.count { it.spec.kind == PermissionKind.RECOMMENDED && !it.isSatisfied }
        )
    }
}
