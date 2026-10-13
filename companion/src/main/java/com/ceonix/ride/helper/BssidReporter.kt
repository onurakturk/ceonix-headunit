package com.ceonix.ride.helper

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.contract.BssidHelperProtocol
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Hands what [BssidScanner] found to the head unit over Bluetooth.
 *
 * The first version of this simply walked the bonded devices calling connect() on each until one
 * worked, and reported a single "could not reach the head unit" when none did. In the field it
 * failed with the head unit sitting there listening, and that one sentence was everything we had —
 * the same blind spot that cost us a round on the scanning side. So every step now says what it
 * actually did, and the caller puts it on screen.
 *
 * Three things it does that the first version did not:
 *
 *  - **Refreshes the service records first.** Android caches the UUID list it learned when the two
 *    devices were paired, and the head unit was paired long before this service existed. A cached
 *    list with no entry for us is enough to make connect() fail against a service that is plainly
 *    running.
 *  - **Connects to a device the rider chose**, rather than trying everything that happens to be
 *    paired. A failed connect can block for the best part of half a minute, so a phone with a watch
 *    and a pair of earbuds turned this into a multi-minute wait — and there was no reason to be
 *    knocking on their doors in the first place.
 *  - **Falls back** to the insecure socket and then to a raw channel, since the failure modes here
 *    are stack-specific and one of them usually gets through.
 *
 * Deliberately still a separate step from the looking: if this ever turns out to disturb the
 * Android Auto connection it can be dropped whole, and the address stays on screen to be typed in.
 */
class BssidReporter(private val context: Context) {

    /** One thing that was tried, and what came of it. Shown verbatim. */
    data class Step(val name: String, val ok: Boolean, val detail: String)

    sealed class Result {
        /** The head unit recognised one of the sightings and stored it. */
        data class Stored(val source: String, val bssid: String) : Result()

        /** We got through, but the head unit did not recognise anything we reported. */
        object NoMatch : Result()

        data class Failed(val reason: Reason) : Result()
    }

    enum class Reason {
        MISSING_BLUETOOTH_PERMISSION,
        BLUETOOTH_OFF,
        NO_LISTENER,
        CONNECTION_FAILED
    }

    data class Outcome(val result: Result, val steps: List<Step>)

    /** Paired devices, for the rider to point at the head unit. */
    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<BluetoothDevice> {
        if (!hasPermission()) return emptyList()
        val adapter = adapter() ?: return emptyList()
        return try { adapter.bondedDevices.orEmpty().toList() } catch (e: Exception) { emptyList() }
    }

    /**
     * @param onProgress called on the main thread before each attempt, so the screen can say what
     *        is happening. A bare spinner over a chain of attempts that each block for the best
     *        part of half a minute reads as "hung", and that is how it got reported.
     */
    suspend fun send(
        device: BluetoothDevice,
        sightings: List<BssidHelperProtocol.Sighting>,
        onProgress: (String) -> Unit = {}
    ): Outcome = withContext(Dispatchers.IO) {
        val steps = mutableListOf<Step>()

        if (!hasPermission()) {
            return@withContext Outcome(Result.Failed(Reason.MISSING_BLUETOOTH_PERMISSION), steps)
        }
        val adapter = adapter()
        if (adapter == null || !adapter.isEnabled) {
            return@withContext Outcome(Result.Failed(Reason.BLUETOOTH_OFF), steps)
        }

        report(onProgress, "service records")
        steps.add(refreshServiceRecords(device))

        val socket = connect(device, steps, onProgress)
            ?: return@withContext Outcome(Result.Failed(Reason.NO_LISTENER), steps)

        try {
            report(onProgress, "sending")
            val result = handOver(socket, sightings, steps)
            Outcome(result, steps)
        } catch (e: Exception) {
            steps.add(Step("handover", false, e.describe()))
            Outcome(Result.Failed(Reason.CONNECTION_FAILED), steps)
        } finally {
            try { socket.close() } catch (e: Exception) {}
        }
    }

    private suspend fun report(onProgress: (String) -> Unit, what: String) {
        withContext(Dispatchers.Main) { onProgress(what) }
    }

    /**
     * Asks the stack to re-read the head unit's service records, and reports whether ours is among
     * them. This is the single most useful line in the whole report: if our UUID is listed and the
     * connect still fails the problem is the socket, and if it is missing while the head unit says
     * it is listening the problem is discovery.
     */
    @SuppressLint("MissingPermission")
    private suspend fun refreshServiceRecords(device: BluetoothDevice): Step {
        val found = withTimeoutOrNull(SDP_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        if (intent.action != BluetoothDevice.ACTION_UUID) return
                        try { context.unregisterReceiver(this) } catch (e: Exception) {}
                        if (cont.isActive) cont.resume(hasOurUuid(device))
                    }
                }
                ContextCompat.registerReceiver(
                    context, receiver, IntentFilter(BluetoothDevice.ACTION_UUID),
                    ContextCompat.RECEIVER_EXPORTED
                )
                cont.invokeOnCancellation {
                    try { context.unregisterReceiver(receiver) } catch (e: Exception) {}
                }
                val started = try { device.fetchUuidsWithSdp() } catch (e: Exception) { false }
                if (!started && cont.isActive) cont.resume(hasOurUuid(device))
            }
        } ?: hasOurUuid(device)

        return Step(
            "service records",
            found,
            if (found) "head unit is advertising our service"
            else "our service is NOT in the head unit's records"
        )
    }

    @SuppressLint("MissingPermission")
    private fun hasOurUuid(device: BluetoothDevice): Boolean = try {
        device.uuids.orEmpty().any { it.uuid == BssidHelperProtocol.SERVICE_UUID }
    } catch (e: Exception) { false }

    /**
     * Secure socket first, since that is what the head unit is listening with; insecure after,
     * because the failure modes here are stack-specific and a one-off setup tool is the wrong place
     * to be precious about which door it comes in through.
     *
     * Two attempts, not five. Each failed connect blocks for the best part of half a minute, and a
     * chain long enough to take minutes is exactly the trap that broke the first version of this
     * app — the rider gives up before the tool does. The raw-channel dial that used to follow was a
     * guess anyway: the head unit serves three RFCOMM services and the stack decides the numbers.
     */
    @SuppressLint("MissingPermission")
    private suspend fun connect(
        device: BluetoothDevice,
        steps: MutableList<Step>,
        onProgress: (String) -> Unit
    ): BluetoothSocket? {
        report(onProgress, "secure connect")
        try {
            val socket = device.createRfcommSocketToServiceRecord(BssidHelperProtocol.SERVICE_UUID)
            socket.connect()
            steps.add(Step("secure connect", true, "connected"))
            return socket
        } catch (e: Exception) {
            steps.add(Step("secure connect", false, e.describe()))
        }

        report(onProgress, "insecure connect")
        try {
            val socket = device.createInsecureRfcommSocketToServiceRecord(BssidHelperProtocol.SERVICE_UUID)
            socket.connect()
            steps.add(Step("insecure connect", true, "connected"))
            return socket
        } catch (e: Exception) {
            steps.add(Step("insecure connect", false, e.describe()))
        }

        return null
    }

    private suspend fun handOver(
        socket: BluetoothSocket,
        sightings: List<BssidHelperProtocol.Sighting>,
        steps: MutableList<Step>
    ): Result {
        val writer = BufferedWriter(OutputStreamWriter(socket.outputStream))
        writer.write(BssidHelperProtocol.HELLO); writer.write("\n")
        sightings.forEach { writer.write(BssidHelperProtocol.encode(it)); writer.write("\n") }
        writer.write(BssidHelperProtocol.END); writer.write("\n")
        writer.flush()
        steps.add(Step("sent", true, "${sightings.size} sightings"))

        val reply = awaitReply(socket)
        if (reply == null) {
            steps.add(Step("reply", false, "no answer from the head unit"))
            return Result.Failed(Reason.CONNECTION_FAILED)
        }
        steps.add(Step("reply", true, reply))

        return when {
            reply.startsWith("${BssidHelperProtocol.REPLY_OK} ") -> {
                val parts = reply.split(' ')
                Result.Stored(parts.getOrNull(1) ?: "", parts.getOrNull(2) ?: "")
            }
            reply == BssidHelperProtocol.REPLY_NO_MATCH -> Result.NoMatch
            else -> Result.Failed(Reason.CONNECTION_FAILED)
        }
    }

    /**
     * Reads the head unit's one-line answer, or gives up.
     *
     * An RFCOMM stream has no read timeout, so a head unit that accepts the connection and then
     * never answers leaves this blocked for good — which is precisely how this was reported:
     * "handing the address over" spinning forever with nothing arriving at the other end. There is
     * no way to interrupt a blocking socket read, so the watchdog closes the socket underneath it;
     * the read then throws and we are out.
     */
    private suspend fun awaitReply(socket: BluetoothSocket): String? = coroutineScope {
        val watchdog = launch {
            delay(REPLY_TIMEOUT_MS)
            try { socket.close() } catch (e: Exception) {}
        }
        try {
            withContext(Dispatchers.IO) {
                BufferedReader(socket.inputStream.reader()).readLine()
            }
        } catch (e: Exception) {
            null
        } finally {
            watchdog.cancel()
        }
    }

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun adapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** Exception class plus message: the class alone is rarely enough to tell these apart. */
    private fun Exception.describe(): String {
        val name = this::class.java.simpleName
        val text = message?.takeIf { it.isNotBlank() }
        return if (text != null) "$name: $text" else name
    }

    private companion object {
        const val SDP_TIMEOUT_MS = 12_000L

        /** Generous — the head unit answers in milliseconds when it answers at all. */
        const val REPLY_TIMEOUT_MS = 15_000L
    }
}
