package com.ceonix.licensing

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.android.vending.licensing.ILicenseResultListener
import com.android.vending.licensing.ILicensingService
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One round trip to the Play Store's licensing service.
 *
 * Deliberately says nothing about what the answer means — that is [ResponseVerifier]'s job. All
 * this distinguishes is "the Play Store answered" from "it could not be asked": not installed,
 * refused the bind, timed out, or went away. The second case never counts against the user; a
 * head unit without internet simply gets [Outcome.Unavailable].
 */
internal class PlayLicenseClient(
    context: Context,
    private val timeoutMs: Long,
    private val log: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    sealed class Outcome {
        /** Raw, unverified. [responseCode] is the unsigned int from the binder — do not trust it. */
        class Response(
            val nonce: Long,
            val responseCode: Int,
            val signedData: String?,
            val signature: String?,
        ) : Outcome()

        object Unavailable : Outcome()
    }

    /** Asks once; [onResult] runs on the main thread, exactly once. */
    fun check(onResult: (Outcome) -> Unit) {
        // LVL draws an int; the service echoes it back inside the signed data.
        val nonce = SecureRandom().nextInt().toLong()
        val done = AtomicBoolean(false)
        lateinit var connection: ServiceConnection

        fun finish(outcome: Outcome) {
            if (!done.compareAndSet(false, true)) return
            main.removeCallbacksAndMessages(done)
            try {
                appContext.unbindService(connection)
            } catch (e: Exception) {
                // Never bound, or already gone.
            }
            main.post { onResult(outcome) }
        }

        val listener = object : ILicenseResultListener.Stub() {
            override fun verifyLicense(responseCode: Int, signedData: String?, signature: String?) {
                log("client: Play answered (unsigned code $responseCode)")
                finish(Outcome.Response(nonce, responseCode, signedData, signature))
            }
        }

        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                try {
                    ILicensingService.Stub.asInterface(service)
                        .checkLicense(nonce, appContext.packageName, listener)
                } catch (e: Exception) {
                    log("client: checkLicense failed: ${e.javaClass.simpleName}")
                    finish(Outcome.Unavailable)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                log("client: licensing service disconnected")
                finish(Outcome.Unavailable)
            }
        }

        val intent = Intent(SERVICE_ACTION).setPackage(IntegrityChecks.PLAY_STORE_PACKAGE)
        val bound = try {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            log("client: bind refused: ${e.message}")
            false
        }
        if (!bound) {
            log("client: could not bind to the Play Store licensing service")
            finish(Outcome.Unavailable)
            return
        }
        main.postAtTime({
            log("client: no answer within ${timeoutMs} ms")
            finish(Outcome.Unavailable)
        }, done, android.os.SystemClock.uptimeMillis() + timeoutMs)
    }

    private companion object {
        const val SERVICE_ACTION = "com.android.vending.licensing.ILicensingService"
    }
}
