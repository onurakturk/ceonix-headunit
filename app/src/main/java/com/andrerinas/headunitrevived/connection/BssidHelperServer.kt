package com.andrerinas.headunitrevived.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.contract.BssidHelperProtocol
import com.andrerinas.headunitrevived.contract.BssidHelperProtocol.Sighting
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.BluetoothHelper
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.OutputStreamWriter

/**
 * Listens for the phone-side helper app, which exists for one reason: to tell us the BSSID of our
 * own Wi-Fi Direct group, because Android will not.
 *
 * **Owned by the setup screen, and by nothing else.** It is opened when that screen appears and
 * closed when it goes away, so outside of a deliberate one-off setup this class does not run at
 * all: no extra SDP record on the adapter, no accept loop, no code between the rider and a
 * connection. That is a hard requirement — an earlier draft hung this off the service for the whole
 * of Native AA mode, and nothing about the Android Auto handshake is worth risking for a feature
 * used once in the life of a head unit.
 *
 * @param onSightings called off the main thread with everything the helper reported; returns the
 *        address that was adopted, or null if nothing matched.
 *
 * @see BssidHelperProtocol for the wire format.
 */
class BssidHelperServer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onSightings: (List<Sighting>) -> BssidMatcher.Match?
) {

    @Volatile private var isRunning = false
    private var serverSocket: BluetoothServerSocket? = null

    /** Why the listener could not be opened, for the screen to show. Null while it is running. */
    @Volatile var failure: Failure? = null
        private set

    /**
     * Set once the RFCOMM server socket really exists.
     *
     * [start] returns before that happens — the socket is opened on a worker — so without this the
     * screen would report "listening" for a listener that had thrown on the way up. It cost us a
     * field round not being able to tell those two apart.
     */
    @Volatile var isSocketOpen = false
        private set

    /** Exception text if the server socket could not be opened. Shown as-is. */
    @Volatile var errorText: String? = null
        private set

    /** How many phones have connected since this listener opened. */
    @Volatile var sessions = 0
        private set

    enum class Failure { MISSING_PERMISSION, BLUETOOTH_OFF, SOCKET_ERROR }

    val isListening: Boolean get() = isRunning

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isRunning) return true

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            AppLog.w("BssidHelper: missing BLUETOOTH_CONNECT — listener not started.")
            failure = Failure.MISSING_PERMISSION
            return false
        }

        // Same ordering rule as the handshake servers (v1.0.66): prove there is an adapter before
        // claiming to be running, or the flag ends up set with nothing behind it.
        val adapter = BluetoothHelper.getBluetoothAdapter(context)
        if (adapter == null || !adapter.isEnabled) {
            AppLog.w("BssidHelper: Bluetooth unavailable — listener not started.")
            failure = Failure.BLUETOOTH_OFF
            return false
        }

        failure = null
        errorText = null
        sessions = 0
        isRunning = true
        scope.launch(Dispatchers.IO + CoroutineName("BssidHelper-Server")) {
            try {
                serverSocket = adapter.listenUsingRfcommWithServiceRecord(
                    BssidHelperProtocol.SERVICE_NAME,
                    BssidHelperProtocol.SERVICE_UUID
                )
                isSocketOpen = true
                AppLog.i("BssidHelper: listening on ${BssidHelperProtocol.SERVICE_UUID}")
                while (isRunning && isActive) {
                    val socket = serverSocket?.accept() ?: continue
                    sessions++
                    handleSession(socket)
                }
                AppLog.i("BssidHelper: accept loop exited (isRunning=$isRunning).")
            } catch (e: Exception) {
                if (isRunning) {
                    AppLog.e("BssidHelper: server socket error: ${e.message}", e)
                    failure = Failure.SOCKET_ERROR
                    errorText = "${e::class.java.simpleName}: ${e.message ?: ""}".trim()
                    isRunning = false
                } else {
                    AppLog.i("BssidHelper: server socket closed cleanly (${e.message}).")
                }
            } finally {
                isSocketOpen = false
            }
        }
        return true
    }

    fun stop() {
        if (!isRunning) return
        AppLog.i("BssidHelper: stopping listener.")
        isRunning = false
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null
    }

    /**
     * Sessions are handled on the accept thread rather than in a coroutine of their own. There is
     * only ever one helper, it sends a handful of lines and leaves, and serialising it keeps two
     * runs from racing each other into the settings.
     */
    private fun handleSession(socket: BluetoothSocket) {
        AppLog.i("BssidHelper: helper connected.")
        try {
            val reader = BufferedReader(socket.inputStream.reader())
            val writer = BufferedWriter(OutputStreamWriter(socket.outputStream))

            val hello = reader.readLine()
            if (hello != BssidHelperProtocol.HELLO && hello != BssidHelperProtocol.HELLO_V1) {
                AppLog.w("BssidHelper: unexpected greeting '$hello' — dropping.")
                return
            }

            val sightings = mutableListOf<Sighting>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line == BssidHelperProtocol.END) break
                val sighting = BssidHelperProtocol.decode(line)
                if (sighting != null) sightings.add(sighting)
                else AppLog.w("BssidHelper: skipping malformed line '$line'")
            }
            val networks = sightings.count { it is Sighting.AccessPoint }
            val peers = sightings.size - networks
            AppLog.i("BssidHelper: helper reported $networks network(s) and $peers peer(s).")

            val match = onSightings(sightings)
            writer.write(
                if (match != null) "${BssidHelperProtocol.REPLY_OK} ${match.source} ${match.bssid}"
                else BssidHelperProtocol.REPLY_NO_MATCH
            )
            writer.write("\n")
            writer.flush()
        } catch (e: Exception) {
            AppLog.e("BssidHelper: session error: ${e.message}", e)
        } finally {
            try { socket.close() } catch (e: Exception) {}
        }
    }
}
