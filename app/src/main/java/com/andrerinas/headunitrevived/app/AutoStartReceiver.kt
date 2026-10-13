package com.andrerinas.headunitrevived.app

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.main.MainActivity
import com.andrerinas.headunitrevived.main.ScreenFocus
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings
import android.os.UserManager
import android.os.Build

class AutoStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        // Use device-protected storage so the BT MACs are readable during locked boot
        val targetMacs = Settings.getAutoStartBtMacs(context)

        if (targetMacs.isEmpty()) return
        
        val isLocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && 
                      !(context.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked
        
        // [FIX] Don't trigger auto-start if we are already connected!
        // This prevents activity restarts if BT reconnects during a session.
        if (!isLocked && com.andrerinas.headunitrevived.App.provide(context).commManager.isConnected) {
            AppLog.d("AutoStartReceiver: Already connected to Android Auto. Ignoring BT event.")
            return
        }

        if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
            val device = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }

            AppLog.i("BT Device connected: ${device?.name} (${device?.address})")

            if (device != null && targetMacs.contains(device.address)) {
                AppLog.i("MATCH! Starting AapService via Bluetooth Auto-start...")

                // Recorded, not acted on: this is the moment the phone comes back into range,
                // and no poke is sent here. Whether the phone then connects on its own is
                // exactly the question the RECONNECT log line answers.
                com.andrerinas.headunitrevived.connection.ReconnectTrigger.record(
                    com.andrerinas.headunitrevived.connection.ReconnectTrigger.BT_ACL_CONNECTED
                )
                
                // Start the service to make the app alive
                val serviceIntent = Intent(context, AapService::class.java)
                try {
                    androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
                } catch (e: Exception) {
                    AppLog.e("Failed to start AapService from background: ${e.message}")
                }

                // Also attempt to start the UI (might be blocked on Android 10+ without special
                // permission) — but only when the app isn't already showing.
                //
                // MainActivity is singleTask, so relaunching it finishes everything above it in
                // the task. Settings is an activity of its own and sits there, which is why
                // unlocking the head unit while in Settings used to close it: the phone comes back
                // over Bluetooth, this fires, and Settings goes with it. Ride and Apps never
                // showed it because they are fragments inside MainActivity, where there is nothing
                // above to clear. Bringing the app to the front when it is already there buys
                // nothing and costs the rider the screen they were on.
                if (ScreenFocus.isAnyScreenForeground()) {
                    AppLog.i("FOCUS: BT auto-start UI launch skipped — already in front (${ScreenFocus.describe()})")
                } else {
                    val launchIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra(MainActivity.EXTRA_LAUNCH_SOURCE, "Bluetooth auto-start")
                    }
                    try {
                        context.startActivity(launchIntent)
                    } catch (e: Exception) {
                        AppLog.w("Could not start UI from background (expected on Android 10+): ${e.message}")
                    }
                }
            }
        }
    }
}