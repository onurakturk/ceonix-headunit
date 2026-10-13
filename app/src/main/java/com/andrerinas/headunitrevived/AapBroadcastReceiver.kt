package com.andrerinas.headunitrevived

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.IntentFilter
import android.view.KeyEvent
import com.andrerinas.headunitrevived.aap.AapProjectionActivity
import com.andrerinas.headunitrevived.aap.ProjectionFocusPolicy
import com.andrerinas.headunitrevived.main.ScreenFocus
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.aap.protocol.messages.LocationUpdateEvent
import com.andrerinas.headunitrevived.connection.CommManager
import com.andrerinas.headunitrevived.contract.KeyIntent
import com.andrerinas.headunitrevived.contract.LocationUpdateIntent
import com.andrerinas.headunitrevived.contract.MediaKeyIntent
import com.andrerinas.headunitrevived.contract.ProjectionActivityRequest
import android.os.UserManager
import android.os.Build

class AapBroadcastReceiver : BroadcastReceiver() {

    companion object {
        val filter: IntentFilter by lazy {
            val filter = IntentFilter()
            filter.addAction(LocationUpdateIntent.action)
            filter.addAction(MediaKeyIntent.action)
            filter.addAction(ProjectionActivityRequest.action)
            filter
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val isLocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && 
                      !(context.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked
        
        if (isLocked) return

        val component = App.provide(context)
        if (intent.action == LocationUpdateIntent.action) {
            val location = LocationUpdateIntent.extractLocation(intent)
            
            // Apply Fake Speed if enabled
            if (component.settings.fakeSpeed) {
                location.speed = 0.5f // 0.5 m/s corresponds to 500 mm/s in Emil's logic
            }

            if (component.settings.useGpsForNavigation) {
                component.commManager.send(LocationUpdateEvent(location))
            }

            if (location.latitude != 0.0 && location.longitude != 0.0) {
                component.settings.lastKnownLocation = location
            }
        } else if (intent.action == MediaKeyIntent.action) {
            val event: KeyEvent? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(KeyIntent.extraEvent, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(KeyIntent.extraEvent)
            }
            event?.let {
                component.commManager.sendKey(it.keyCode, it.action == KeyEvent.ACTION_DOWN)
            }
        } else if (intent.action == ProjectionActivityRequest.action){
            // Sent by the phone, not the rider: AapControl fires this when the video channel is
            // set up, which happens within about a second of every read loop starting. Ungated it
            // would drag the rider into projection right after a session comes up in the
            // background — the exact thing the focus policy exists to prevent.
            if (component.commManager.connectionState.value is CommManager.ConnectionState.TransportStarted) {
                val decision = ProjectionFocusPolicy.evaluate(ProjectionFocusPolicy.Trigger.SPONTANEOUS)
                AppLog.i(
                    "FOCUS: phone asked for the screen (video channel setup) " +
                        "allow=${decision.allow} screen=${ScreenFocus.describe()} — ${decision.reason}"
                )
                if (decision.allow) {
                    val aapIntent = Intent(context, AapProjectionActivity::class.java)
                    aapIntent.putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                    aapIntent.flags = FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(aapIntent)
                }
            }
        }
    }
}

