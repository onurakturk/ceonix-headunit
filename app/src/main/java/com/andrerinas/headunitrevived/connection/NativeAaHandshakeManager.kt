package com.andrerinas.headunitrevived.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.utils.BluetoothHelper
import com.andrerinas.headunitrevived.aap.protocol.proto.Wireless
import com.andrerinas.headunitrevived.utils.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.*

/**
 * Manages the official Android Auto Wireless Bluetooth handshake.
 * This class implements the RFCOMM server protocol to exchange WiFi credentials with the phone.
 */
class NativeAaHandshakeManager(
    private val context: AapService,
    private val scope: CoroutineScope
) {
    companion object {
        private val AA_UUID = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66")
        private val HFP_UUID = UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb")
        private val A2DP_SOURCE_UUID = UUID.fromString("00001112-0000-1000-8000-00805f9b34fb")

        /** How long the phone gets to join the WiFi group after taking the credentials. */
        private const val WIFI_JOIN_GRACE_MS = 20_000L

        /**
         * While the Static BSSID is missing the phone keeps dialling back, so the "go to Wi-Fi
         * Setup" message would repeat on every attempt. Say it at most this often.
         */
        private const val MISSING_BSSID_WARN_INTERVAL_MS = 60_000L

        fun checkCompatibility(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) 
                    != PackageManager.PERMISSION_GRANTED) {
                    AppLog.w("NativeAA: Compatibility Check skipped - Missing BLUETOOTH_CONNECT")
                    return false
                }
            }
            val adapter = BluetoothHelper.getBluetoothAdapter(context) ?: return false
            if (!adapter.isEnabled) return false
            return try {
                val socket = adapter.listenUsingRfcommWithServiceRecord("Compatibility Check", AA_UUID)
                socket.close()
                AppLog.i("NativeAA: Compatibility Check SUCCESS")
                true
            } catch (e: Exception) {
                AppLog.w("NativeAA: Compatibility Check FAILED: ${e.message}")
                false
            }
        }
    }

    private val settings = com.andrerinas.headunitrevived.App.provide(context).settings
    private val commManager = com.andrerinas.headunitrevived.App.provide(context).commManager
    private var aaServerSocket: BluetoothServerSocket? = null
    private var hfpServerSocket: BluetoothServerSocket? = null
    private var isRunning = false

    // Written from WifiDirectManager's credential-delivery thread, read from the handshake IO
    // coroutine — @Volatile so the wait loop in handleHandshake actually sees an update.
    @Volatile private var currentSsid: String? = null
    @Volatile private var currentPsk: String? = null
    @Volatile private var currentIp: String? = null
    @Volatile private var currentBssid: String? = null
    private var pokeJob: Job? = null

    /** @see warnMissingBssid */
    @Volatile private var lastMissingBssidWarnMs = 0L

    /**
     * Updates the WiFi credentials that will be sent to the phone during the next handshake.
     */
    fun updateWifiCredentials(ssid: String, psk: String, ip: String, bssid: String) {
        AppLog.i("NativeAA: Credentials updated. SSID=$ssid, IP=$ip, BSSID=$bssid")
        currentSsid = ssid
        currentPsk = psk
        currentIp = ip
        currentBssid = bssid
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) {
            AppLog.i("DIAG: NativeAA.start() ignored — isRunning already true (aaSocket=${aaServerSocket != null}, hfpSocket=${hfpServerSocket != null}).")
            return
        }
        AppLog.i("DIAG: NativeAA.start() entering (aaSocket=${aaServerSocket != null}, hfpSocket=${hfpServerSocket != null}).")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) 
                != PackageManager.PERMISSION_GRANTED) {
                AppLog.e("NativeAA: Missing BLUETOOTH_CONNECT permission. Handshake server cannot start.")
                return
            }
        }

        // Look the adapter up BEFORE claiming to be running. Bluetooth is often still coming up
        // when the service starts on boot, and marking ourselves running on the way out of that
        // check left the flag set with no server sockets behind it: every later start() returned
        // at the guard above, and the poke paths only ever call start(), so nothing recovered
        // until the process was restarted. On a head unit that is the ignition-on case.
        val adapter = BluetoothHelper.getBluetoothAdapter(context)
        if (adapter == null || !adapter.isEnabled) {
            AppLog.e("NativeAA: Bluetooth adapter unavailable or disabled — handshake servers not started, will retry on the next request.")
            return
        }

        isRunning = true
        AppLog.i("NativeAA: Starting Bluetooth Handshake Servers...")

        // Start AA RFCOMM Server
        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-RfcommServer")) {
            try {
                aaServerSocket = adapter.listenUsingRfcommWithServiceRecord("AA BT Listener", AA_UUID)
                AppLog.i("NativeAA: ACTIVELY LISTENING on Android Auto UUID ($AA_UUID)... Waiting for phone to connect back!")
                while (isRunning && isActive) {
                    val socket = aaServerSocket?.accept()
                    if (socket != null) {
                        AppLog.i("NativeAA: Connection accepted from ${socket.remoteDevice.name}")
                        // The phone has just connected to us. Whatever we last did to invite it
                        // back — or that we did nothing at all — decides whether the reconnect
                        // path needs help. See [ReconnectTrigger].
                        AppLog.i("RECONNECT: inbound AA connection from ${socket.remoteDevice.name} — last trigger: ${ReconnectTrigger.describe()}")
                        // [FIX] Launch handshake in a separate coroutine so the server can accept the next connection!
                        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-Handshake-${socket.remoteDevice.address}")) {
                            handleHandshake(socket)
                        }
                    }
                }
                AppLog.i("DIAG: NativeAA AA accept loop exited (isRunning=$isRunning, isActive=$isActive).")
            } catch (e: Exception) {
                if (isRunning) {
                    AppLog.e("NativeAA: AA Server socket error: ${e.message}", e)
                    dropServersAfterUnexpectedError("AA")
                } else {
                    AppLog.i("DIAG: NativeAA AA server socket closed cleanly (${e.message}).")
                }
            }
        }

        // Start HFP RFCOMM Server (Required by some phones to detect HU)
        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-HfpServer")) {
            try {
                hfpServerSocket = adapter.listenUsingRfcommWithServiceRecord("Hands-Free Unit", HFP_UUID)
                AppLog.i("DIAG: NativeAA HFP server listening.")
                while (isRunning && isActive) {
                    val socket = hfpServerSocket?.accept()
                    if (socket != null) {
                        AppLog.i("NativeAA: HFP connection accepted from ${socket.remoteDevice.name}. Starting responder.")
                        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-HfpResponder-${socket.remoteDevice.address}")) {
                            handleHfp(socket)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    AppLog.e("NativeAA: HFP Server socket error: ${e.message}", e)
                    dropServersAfterUnexpectedError("HFP")
                } else {
                    AppLog.d("NativeAA: HFP Server socket closed cleanly.")
                }
            }
        }
    }

    /**
     * Minimal HFP responder to satisfy phones that require a stable HFP connection
     * during the Android Auto Wireless handshake.
     */
    private suspend fun handleHfp(socket: BluetoothSocket) = withContext(Dispatchers.IO) {
        try {
            val input = socket.inputStream
            val output = socket.outputStream
            val buf = ByteArray(1024)
            
            AppLog.i("NativeAA: HFP responder active for ${socket.remoteDevice.name}")
            
            while (isRunning && isActive && socket.isConnected) {
                if (input.available() > 0) {
                    val read = input.read(buf)
                    if (read == -1) break
                    
                    val cmd = String(buf, 0, read, Charsets.US_ASCII).trim()
                    AppLog.d("NativeAA: HFP RX: $cmd")
                    
                    // Respond to standard HFP initialization commands
                    when {
                        cmd.contains("AT+BRSF") -> {
                            output.write("+BRSF: 20\r\n".toByteArray())
                            output.write("OK\r\n".toByteArray())
                        }
                        cmd.contains("AT+CIND=?") -> {
                            output.write("+CIND: (\"service\",(0,1)),(\"call\",(0,1))\r\n".toByteArray())
                            output.write("OK\r\n".toByteArray())
                        }
                        cmd.contains("AT+CIND?") -> {
                            output.write("+CIND: 1,0\r\n".toByteArray())
                            output.write("OK\r\n".toByteArray())
                        }
                        else -> {
                            output.write("OK\r\n".toByteArray())
                        }
                    }
                    output.flush()
                }
                delay(200)
            }
        } catch (e: Exception) {
            AppLog.d("NativeAA: HFP responder error: ${e.message}")
        } finally {
            try { socket.close() } catch (e: Exception) {}
            AppLog.i("NativeAA: HFP socket for ${socket.remoteDevice.address} closed.")
        }
    }

    /**
     * Wakes up the phone by attempting a brief connection to the A2DP profile.
     * This acts as a signal for the phone to start looking for the headunit.
     *
     * Skips the request when a poke is already in flight. Bringing up the P2P group fires the
     * credentials callback several times in a row — on the 25 August boot it fired three times
     * inside 700ms — and cancelling the previous job does not protect us there: cancel() is
     * asynchronous, so a job that has already cleared its delay walks into the blocking
     * socket.connect() where it can no longer be interrupted. That boot ended up with two
     * concurrent connects to the same phone a millisecond apart and neither one landed; the
     * headunit sat there until the rider woke the screen. Across the six v1.1.7 captures every
     * single poke succeeded and only that doubled one failed. Poking twice buys nothing anyway,
     * so declining the duplicate is the honest fix.
     */
    fun triggerPoke() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                AppLog.w("NativeAA: Missing BLUETOOTH_CONNECT. Cannot triggerPoke.")
                return
            }
        }
        val adapter = BluetoothHelper.getBluetoothAdapter(context) ?: return
        val lastMacs = settings.autoStartBluetoothDeviceMacs

        if (pokeJob?.isActive == true) {
            AppLog.i("NativeAA: A poke is already in flight — skipping this duplicate request.")
            return
        }
        pokeJob = scope.launch(Dispatchers.IO + CoroutineName("NativeAa-Wakeup")) {
            AppLog.d("NativeAA: triggerPoke() delay starting (2s)...")
            delay(2000) // Small safety delay before connecting

            if (commManager.isConnected ||
                commManager.connectionState.value is CommManager.ConnectionState.Connecting) {
                AppLog.i("NativeAA: USB/other session became active during poke delay. Skipping poke.")
                return@launch
            }

            val devicesToPoke = if (lastMacs.isNotEmpty()) {
                lastMacs.mapNotNull { mac ->
                    try {
                        adapter.getRemoteDevice(mac)
                    } catch (e: Exception) {
                        null
                    }
                }
            } else {
                AppLog.w("NativeAA: No 'Auto Start BT Device' selected in settings. Poking all paired devices as fallback...")
                adapter.bondedDevices.toList()
            }

            if (devicesToPoke.isEmpty()) {
                AppLog.w("NativeAA: No paired Bluetooth devices found to poke.")
                return@launch
            }

            for (device in devicesToPoke) {
                if (!isRunning || !isActive) break
                if (commManager.isConnected) {
                    AppLog.i("NativeAA: USB/other session became active mid-poke. Stopping poke loop.")
                    break
                }
                AppLog.i("NativeAA: Attempting active A2DP poke to device: ${device.name} (${device.address})...")
                var socket: BluetoothSocket? = null
                try {
                    socket = device.createRfcommSocketToServiceRecord(A2DP_SOURCE_UUID)
                    AppLog.i("NativeAA: Calling socket.connect() for ${device.name}...")
                    socket.connect()
                    AppLog.i("NativeAA: Successfully poked ${device.name}. Keeping socket alive for 15s...")
                    delay(15000)
                } catch (e: Exception) {
                    // Warn, not debug: this is often harmless (the phone simply isn't reachable),
                    // but when auto-start fails to bring Android Auto up it is the one line that
                    // says why — and at the Info log level a debug line never reaches the file.
                    AppLog.w("NativeAA: Poke for ${device.name} failed (normal if device disconnected): ${e.message}")
                } finally {
                    try { socket?.close() } catch (e: Exception) {}
                    AppLog.d("NativeAA: Poke socket for ${device.name} closed in finally.")
                }
            }
        }
    }

    /**
     * Start a manual poke (wakeup) for a specific Bluetooth device.
     */
    fun manualPoke(address: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) 
                != PackageManager.PERMISSION_GRANTED) {
                AppLog.w("NativeAA: Missing BLUETOOTH_CONNECT. Cannot manualPoke.")
                return
            }
        }
        val adapter = BluetoothHelper.getBluetoothAdapter(context) ?: return
        try {
            val device = adapter.getRemoteDevice(address)
            AppLog.i("NativeAA: Manual poke requested for ${device.name} ($address)")
            
            pokeJob?.cancel()
            pokeJob = scope.launch(Dispatchers.IO + CoroutineName("NativeAa-ManualWakeup")) {
                AppLog.i("NativeAA: Attempting manual A2DP poke to ${device.name}...")
                var socket: BluetoothSocket? = null
                try {
                    socket = device.createRfcommSocketToServiceRecord(A2DP_SOURCE_UUID)
                    AppLog.i("NativeAA: Calling socket.connect() for ${device.name}...")
                    socket.connect()
                    AppLog.i("NativeAA: Successfully poked ${device.name}. Keeping socket alive for 20s...")
                    delay(20000)
                } catch (e: Exception) {
                    AppLog.d("NativeAA: Manual poke for ${device.name} failed: ${e.message}")
                } finally {
                    try { socket?.close() } catch (e: Exception) {}
                    AppLog.i("NativeAA: Manual poke socket for ${device.name} closed in finally.")
                }
            }
        } catch (e: Exception) {
            AppLog.e("NativeAA: Manual poke error", e)
        }
    }

    private suspend fun handleHandshake(socket: BluetoothSocket) = withContext(Dispatchers.IO) {
        try {
            val device = socket.remoteDevice
            AppLog.i("NativeAA: Handling handshake for ${device.name} (${device.address})")

            if (commManager.isConnected ||
                commManager.connectionState.value is CommManager.ConnectionState.Connecting) {
                AppLog.i("NativeAA: USB/other session already active. Aborting BT handshake so phone does not start a parallel wireless attempt.")
                try { socket.close() } catch (_: Exception) {}
                return@withContext
            }

            val macs = settings.autoStartBluetoothDeviceMacs
            if (!macs.contains(device.address)) {
                AppLog.i("NativeAA: Saving ${device.address} (${device.name}) to the list of auto-start devices.")
                val newMacs = macs + device.address
                settings.autoStartBluetoothDeviceMacs = newMacs
                settings.autoStartBluetoothDeviceName = device.name ?: "Unknown Device"
                com.andrerinas.headunitrevived.utils.Settings.syncAutoStartBtMacsToDeviceStorage(context, newMacs)
            }

            val input = DataInputStream(socket.inputStream)
            val output = socket.outputStream

            AppLog.i("NativeAA: Phone connected. Current credentials state: SSID=${currentSsid ?: "<null>"}, IP=${currentIp ?: "<null>"}")

            // Credentials can outlive a session (a user Exit keeps the P2P group up), so before
            // trusting cached ones make sure that group is still there — the group owner address
            // we cached must still be assigned locally. Sending the phone an SSID that no longer
            // exists would fail silently and never reach the wait loop below, which is the only
            // thing that asks for a new group.
            val cachedGoIp = currentIp
            if (currentSsid != null && cachedGoIp != null && !context.isNativeAaHostAlive(cachedGoIp)) {
                AppLog.w("NativeAA: cached credentials exist but the P2P host looks dead — dropping them and forcing a refresh.")
                clearWifiCredentials()
                context.triggerWifiDirectRefresh(force = true)
            }

            // Backstop for the other way cached credentials go bad: the group is alive but was
            // built before the address was written, so the BSSID we cached is the one we resolved
            // back then. Writing the address already rebuilds the group, so this only matters if
            // the app was killed in between. Compared case-insensitively — the value goes out
            // uppercased, settings holds it as the helper wrote it.
            //
            // Only asks the question when there is actually a cached address to disagree with. A
            // null cache is the normal state of a cold process, not a stale one: the wait loop
            // below is already waiting for credentials, and those re-read Settings.staticBSSID
            // fresh. Treating null as a mismatch fired this on every cold-start handshake — boot
            // and range-return included — and tore down the group that was still being created.
            val cachedBssid = currentBssid
            val configuredBssid = settings.staticBSSID
            if (!cachedBssid.isNullOrEmpty() && !configuredBssid.isNullOrEmpty() && configuredBssid != "0" &&
                !configuredBssid.equals(cachedBssid, ignoreCase = true)) {
                AppLog.w("NativeAA: cached BSSID ($cachedBssid) does not match the configured Static BSSID ($configuredBssid) — rebuilding the group so the handshake advertises the right address.")
                clearWifiCredentials()
                // Age-gated on purpose, unlike the settings-write path: a group creation may be in
                // flight right now, and the latch is what keeps the phone from chasing a moving
                // target. If the gate holds, the wait loop's own 10s refresh takes over.
                context.triggerWifiDirectRefresh(force = true)
            }

            AppLog.i("NativeAA: Waiting for WiFi credentials to be ready (Max 60s)...")

            // Wait up to 60 seconds for credentials (P2P group creation can be slow)
            var attempts = 0
            while ((currentSsid == null || currentIp == null) && attempts < 120 && isRunning && isActive) {
                if (attempts % 20 == 0 && attempts > 0) {
                    AppLog.w("NativeAA: Still waiting for credentials after ${attempts / 2}s. Requesting P2P refresh...")
                    context.triggerWifiDirectRefresh(force = true)
                } else if (attempts % 10 == 0 && attempts > 0) {
                    AppLog.d("NativeAA: Still waiting... SSID=${currentSsid != null}, IP=${currentIp != null} (Attempt $attempts/120)")
                }
                delay(500)
                attempts++
            }

            if (currentSsid == null || currentIp == null) {
                AppLog.e("NativeAA: Handshake failed - No WiFi credentials available after 60s wait. Missing: ${if(currentSsid == null) "SSID " else ""}${if(currentIp == null) "IP" else ""}")
                return@withContext
            }

            val ip = currentIp!!
            val ssid = currentSsid!!
            val psk = currentPsk ?: ""
            var bssid = currentBssid ?: ""

            // [FIX] Ensure BSSID is uppercase and not zeroed if possible
            bssid = bssid.uppercase()
            if (bssid.isEmpty() || bssid == "00:00:00:00:00:00" || bssid == "02:00:00:00:00:00") {
                // Rebuilding the group cannot un-mask this. Android 10+ refuses to tell an app its
                // own P2P MAC (AOSP's maybeEraseOwnDeviceAddress wants LOCAL_MAC_ADDRESS, which is
                // signature|privileged), so the address has to arrive from outside — the helper, or
                // typed in. Asking for a forced refresh here rebuilt the group on every failed
                // handshake, and since removeGroup clears WifiDirectManager's latch nothing
                // throttled it: a fresh install measured ~2.5 groups per second, which meant the
                // SSID the phone had just been told was already gone before it could join. Abort
                // quietly, leave the group alone, and say where the address comes from.
                AppLog.e("NativeAA: BSSID is masked/empty ($bssid) at Type 3 time — the phone would reject these credentials. Aborting handshake; the address has to come from Settings > Wi-Fi Setup, Android 10+ hides it from us.")
                warnMissingBssid()
                return@withContext
            }

            AppLog.i("NativeAA: Starting Handshake Exchange:")
            AppLog.i("  > Target SSID: $ssid")
            AppLog.i("  > Target IP:   $ip:5288")
            AppLog.i("  > BSSID:       $bssid")

            AppLog.i("NativeAA: [TX] Sending WifiStartRequest (Type 1)")
            sendWifiStartRequest(output, ip, 5288)

            AppLog.i("NativeAA: Waiting for response from phone...")
            val response = readProtobuf(input)
            AppLog.i("NativeAA: [RX] Received Type ${response.type} (Payload size: ${response.payload.size})")

            if (response.type == 2) {
                AppLog.i("NativeAA: Phone ready for WiFi association. Delivering credentials...")
                AppLog.i("NativeAA: [TX] Sending WifiInfoResponse (Type 3) with full credentials in 1000ms...")
                delay(1000) // [FIX] Increased delay to give phone more processing time
                sendWifiSecurityResponse(output, ssid, psk, bssid)
                AppLog.i("NativeAA: Handshake completed successfully on Bluetooth side.")
                watchForMissingWifiJoin(ssid, bssid)

                // Keep the socket open to maintain the handshake session
                AppLog.i("NativeAA: Maintaining Bluetooth session...")
                while (isRunning && isActive && socket.isConnected) {
                    delay(5000)
                }
                AppLog.i("NativeAA: Handshake session ending (isRunning=$isRunning, isConnected=${socket.isConnected})")
            } else {
                AppLog.w("NativeAA: Handshake failed - Unexpected response type ${response.type}. Expected Type 2.")
            }

        } catch (e: Exception) {
            AppLog.e("NativeAA: Handshake error: ${e.message}", e)
        } finally {
            try { socket.close() } catch (e: Exception) {}
            AppLog.i("NativeAA: BT Handshake socket closed.")
        }
    }

    /**
     * The masked-BSSID abort is otherwise completely silent — the same shape of failure as a stale
     * address, which is exactly what the rider cannot tell apart. Reuses that warning's wording
     * because it already names Settings > Wi-Fi Setup and already exists in all twenty languages.
     */
    private fun warnMissingBssid() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastMissingBssidWarnMs < MISSING_BSSID_WARN_INTERVAL_MS) return
        lastMissingBssidWarnMs = now
        context.warnPhoneDidNotJoinWifi()
    }

    /**
     * The phone can accept the credentials over Bluetooth and then never join the WiFi group, and
     * nothing in the protocol tells us so — the handshake looks like a complete success and the
     * projection simply never arrives. By far the most common cause is a stale Static BSSID: the
     * P2P MAC is re-randomised whenever the WiFi/P2P subsystem restarts, the framework masks it
     * from us on Android 10+, so a value entered by hand silently stops matching. Say so instead
     * of leaving the user with a dead screen and no explanation.
     *
     * Any sign of the phone inside the window counts, not only being connected when it ends. A
     * session that came up and was closed again before the window ran out — the rider tapped
     * Exit a few seconds in — proves the phone joined, and used to be reported as a stale BSSID.
     */
    private fun watchForMissingWifiJoin(ssid: String, bssid: String) {
        scope.launch {
            val phoneArrived = withTimeoutOrNull(WIFI_JOIN_GRACE_MS) {
                commManager.connectionState.first {
                    it !is CommManager.ConnectionState.Disconnected &&
                        it !is CommManager.ConnectionState.Error
                }
            } != null
            if (phoneArrived) return@launch
            AppLog.w("NativeAA: phone took the credentials but never joined '$ssid' within ${WIFI_JOIN_GRACE_MS / 1000}s. The BSSID we advertised ($bssid) is most likely stale — check it against the live group.")
            context.warnPhoneDidNotJoinWifi()
        }
    }

    private fun sendWifiStartRequest(output: OutputStream, ip: String, port: Int) {
        val request = Wireless.WifiStartRequest.newBuilder()
            .setIpAddress(ip)
            .setPort(port)
            .setStatus(0)
            .build()
        sendProtobuf(output, request.toByteArray(), 1)
    }

    private fun sendWifiSecurityResponse(output: OutputStream, ssid: String, key: String, bssid: String) {
        val response = Wireless.WifiInfoResponse.newBuilder()
            .setSsid(ssid)
            .setKey(key)
            .setBssid(bssid)
            .setSecurityMode(Wireless.SecurityMode.WPA2_PERSONAL)
            .setAccessPointType(Wireless.AccessPointType.STATIC)
            .build()
        sendProtobuf(output, response.toByteArray(), 3)
    }

    private fun sendProtobuf(output: OutputStream, data: ByteArray, type: Short) {
        val buffer = ByteBuffer.allocate(data.size + 4)
        buffer.put((data.size shr 8).toByte())
        buffer.put((data.size and 0xFF).toByte())
        buffer.putShort(type)
        buffer.put(data)
        output.write(buffer.array())
        output.flush()
        AppLog.i("NativeAA: Successfully delivered Protobuf TYPE $type (size ${data.size}) over Bluetooth!")
    }

    private fun readProtobuf(input: DataInputStream): ProtobufMessage {
        val header = ByteArray(4)
        input.readFully(header)
        val size = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
        val type = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        val payload = if (size > 0) {
            val p = ByteArray(size)
            input.readFully(p)
            p
        } else ByteArray(0)
        return ProtobufMessage(type, payload)
    }

    data class ProtobufMessage(val type: Int, val payload: ByteArray)

    /**
     * Stops the Bluetooth handshake servers.
     *
     * @param clearCredentials whether to also forget the cached WiFi credentials. Pass `false`
     * when the P2P group they describe is deliberately left running (a user Exit in Native AA
     * mode) — the same SSID/PSK/IP/BSSID are still valid and re-using them lets the next
     * handshake start immediately instead of waiting for a group that is never rebuilt.
     */
    /**
     * An accept loop died on its own. Drop the whole handshake server so the next request can
     * rebuild it.
     *
     * Without this the loop simply ended: `isRunning` stayed true and the socket fields stayed
     * non-null, so every later [start] returned at its guard - and since the poke paths only ever
     * call [start], nothing recovered until the process was restarted. Measured on 8 September:
     * both loops died at 21:42:52 with `read failed, socket might closed or timeout`, and the four
     * pokes between 22:00 and 22:02 all reported success into a head unit with nothing listening.
     * No inbound connection arrived until the rider restarted the app.
     *
     * Credentials are kept: the P2P group they describe is still up, and the phone will need them
     * on the way back in (the v1.0.54 rule).
     *
     * Rebuilding is deliberately left to the existing triggers - Bluetooth ACL, USER_PRESENT, the
     * home screen's Android Auto card, a credentials poke - rather than restarting here, so this
     * adds no new step of its own to the connection flow.
     *
     * Both loops can land here: on 13 September they threw within a millisecond of each other on
     * separate threads, the second had already passed its `isRunning` check before the first
     * cleared it, and [stop] ran twice. That is harmless because [stop] is idempotent - the second
     * call finds both sockets null and the credentials already kept - not because the second loop
     * is guaranteed to take the "closed cleanly" branch.
     */
    private fun dropServersAfterUnexpectedError(which: String) {
        AppLog.w("NativeAA: $which handshake server went down unexpectedly — dropping both so the next request can rebuild them.")
        stop(clearCredentials = false)
    }

    fun stop(clearCredentials: Boolean = true) {
        val caller = Throwable().stackTrace.drop(1).take(4)
            .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        AppLog.i("DIAG: NativeAA.stop(clearCredentials=$clearCredentials) isRunning=$isRunning caller=$caller")
        isRunning = false
        try { aaServerSocket?.close() } catch (e: Exception) {}
        try { hfpServerSocket?.close() } catch (e: Exception) {}
        aaServerSocket = null
        hfpServerSocket = null
        if (clearCredentials) {
            clearWifiCredentials()
        } else {
            AppLog.i("NativeAA: stop() keeping cached WiFi credentials (SSID=${currentSsid ?: "<null>"}) — P2P group stays up.")
        }
        pokeJob?.cancel()
        pokeJob = null
    }

    /** Forgets the cached WiFi credentials. Call whenever the P2P group they describe is gone. */
    fun clearWifiCredentials() {
        currentSsid = null
        currentIp = null
        currentPsk = null
        currentBssid = null
    }
}
