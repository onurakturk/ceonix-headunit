package com.ceonix.licensing

import android.content.Context
import android.os.Build
import android.os.UserManager

/** Where a copy of the app stands. Only [LICENSED] opens the gate. */
enum class LicenseState {
    /** A Google-signed LICENSED answer is stored on this device and still verifies. */
    LICENSED,

    /** Nothing usable stored yet (new install, cleared data, other device): one online check needed. */
    UNVERIFIED,

    /** Google answered, signed, that this account does not own the app (refund, never bought). */
    NOT_LICENSED,

    /** The APK is not signed with one of the allowed certificates: repackaged or patched. */
    TAMPERED,
}

/**
 * One-time Play licence check that keeps working offline, bound to the device.
 *
 * The rules, in order:
 *  1. The APK must be signed with an allowed certificate, or nothing else is even looked at.
 *  2. The first launch needs one online answer from the Play Store. Only a response whose RSA
 *     signature verifies against the app's public key counts; the plain response code the binder
 *     reports is ignored.
 *  3. That signed answer is stored encrypted with a Keystore key ([BoundStore]) and is valid for
 *     good. There is no expiry and no clock: a head unit that never sees the internet again keeps
 *     working.
 *  4. Whenever the internet is there anyway, the check may be repeated quietly. A signed
 *     NOT_LICENSED revokes the stored answer; an error, a timeout or no network changes nothing.
 *  5. If the app's installer changes (Play → adb, say), the stored answer is set aside until it is
 *     confirmed again online. A legitimate owner passes that check from any installer, because the
 *     licence follows the Google account.
 *
 * [evaluate] re-verifies the stored signature every time it is called. Callers are expected to
 * call it at each place that matters rather than caching a boolean, so patching one branch does not
 * open the rest.
 *
 * Thread-safe: the Android Auto service consults it from its own threads.
 */
class LicenseGate(context: Context, private val config: LicenseConfig) {
    private val appContext = context.applicationContext
    private val packageName = appContext.packageName
    private val log = config.log
    private val verifier = ResponseVerifier.fromBase64(config.publicKeyBase64)
    private val store = BoundStore(appContext, config.keyAlias, config.storeFileName, log)
    private val client = PlayLicenseClient(appContext, config.checkTimeoutMs, log)

    private var integrity: Boolean? = null
    // Only a successful read is remembered. "Nothing there" is never cached: before the first unlock
    // after a boot the file is simply not readable yet (see [isStorageAvailable]).
    private var record: StoredLicense? = null
    private var unreadableLogged = false
    private var deniedByGoogle = false
    private var inFlight: MutableList<(LicenseState) -> Unit>? = null

    init {
        if (verifier == null) log("gate: public key missing or malformed — every check will fail")
    }

    /**
     * Whether the stored answer can be read at all right now. False between a boot and the first
     * unlock: the app is direct-boot aware and starts on LOCKED_BOOT_COMPLETED, while the record
     * lives in credential-encrypted storage. Measured 28 Oct on the Mi 9T (PIN lock, starts locked):
     * the gate read "nothing stored" at boot, cached it, and asked for the internet again after every
     * restart. Devices without a lock screen (the Mi A3) never showed it.
     */
    val isStorageAvailable: Boolean
        get() = Build.VERSION.SDK_INT < 24 ||
            (appContext.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked

    val isChecking: Boolean
        @Synchronized get() = inFlight != null

    @Synchronized
    fun evaluate(): LicenseState {
        if (!integrityOk()) return LicenseState.TAMPERED
        if (deniedByGoogle) return LicenseState.NOT_LICENSED
        val stored = loadRecord() ?: return LicenseState.UNVERIFIED
        if (stored.installer != currentInstaller()) return LicenseState.UNVERIFIED
        val response = verifier?.verify(stored.signedData, stored.signature)
            ?: return LicenseState.UNVERIFIED
        if (response.packageName != packageName || !response.grantsLicense) return LicenseState.UNVERIFIED
        return LicenseState.LICENSED
    }

    /** When Google signed the stored answer, if one is stored and valid. */
    @Synchronized
    fun verifiedAtMillis(): Long? {
        if (evaluate() != LicenseState.LICENSED) return null
        val stored = record ?: return null
        return verifier?.verify(stored.signedData, stored.signature)?.timestampMillis
    }

    /**
     * Asks the Play Store now. [onDone] receives the resulting [evaluate] on the main thread.
     * Concurrent calls share one request.
     */
    @Synchronized
    fun verifyOnline(onDone: (LicenseState) -> Unit = {}) {
        if (!integrityOk()) {
            onDone(LicenseState.TAMPERED)
            return
        }
        inFlight?.let {
            it += onDone
            return
        }
        val waiting = mutableListOf(onDone)
        inFlight = waiting
        client.check { outcome ->
            val state = synchronized(this) {
                when (outcome) {
                    is PlayLicenseClient.Outcome.Unavailable -> log("gate: Play unavailable, nothing changed")
                    is PlayLicenseClient.Outcome.Response -> accept(outcome)
                }
                inFlight = null
                evaluate()
            }
            log("gate: online check finished -> $state")
            waiting.forEach { it(state) }
        }
    }

    private fun accept(outcome: PlayLicenseClient.Outcome.Response) {
        val response = verifier?.verify(outcome.signedData, outcome.signature)
        if (response == null) {
            // Errors (no network, server failure) arrive unsigned; so does every forged LICENSED.
            log("gate: answer not signed by our key (unsigned code ${outcome.responseCode}), ignored")
            return
        }
        if (response.nonce != outcome.nonce || response.packageName != packageName) {
            log("gate: signed answer for another request (nonce/package mismatch), ignored")
            return
        }
        when {
            response.grantsLicense -> {
                val fresh = StoredLicense(outcome.signedData!!, outcome.signature!!, currentInstaller())
                if (!store.write(fresh)) log("gate: could not persist; valid for this run only")
                record = fresh
                deniedByGoogle = false
                log("gate: LICENSED (signed), stored")
            }
            response.responseCode == ResponseCodes.NOT_LICENSED -> {
                store.delete()
                record = null
                deniedByGoogle = true
                log("gate: NOT_LICENSED (signed), stored answer revoked")
            }
            else -> log("gate: signed code ${response.responseCode}, nothing changed")
        }
    }

    private fun integrityOk(): Boolean {
        integrity?.let { return it }
        val ok = IntegrityChecks.isSignedByAllowed(appContext, config.allowedCertSha256)
        if (!ok) log("gate: signing certificate not allowed: ${IntegrityChecks.describeSigners(appContext)}")
        integrity = ok
        return ok
    }

    private fun loadRecord(): StoredLicense? {
        record?.let { return it }
        if (!isStorageAvailable) return null
        return when (val read = store.read()) {
            is BoundStore.Read.Ok -> read.record.also { record = it }
            BoundStore.Read.Missing -> null
            BoundStore.Read.Unreadable -> {
                if (!unreadableLogged) log("gate: stored answer unreadable on this device")
                unreadableLogged = true
                null
            }
        }
    }

    private fun currentInstaller(): String = IntegrityChecks.installer(appContext) ?: ""
}
