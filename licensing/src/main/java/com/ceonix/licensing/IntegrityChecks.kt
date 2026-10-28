package com.ceonix.licensing

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

/** Facts about how this copy of the app got onto the device. */
internal object IntegrityChecks {

    const val PLAY_STORE_PACKAGE = "com.android.vending"

    /**
     * SHA-256 digests (upper-case hex, no separators) of the certificates the installed APK is
     * signed with right now. Empty if the package manager would not say — which callers treat as
     * a failure, never as a pass.
     */
    @SuppressLint("PackageManagerGetSignatures")
    @Suppress("DEPRECATION")
    fun signingCertDigests(context: Context): Set<String> {
        val pm = context.packageManager
        val signatures: Array<Signature> = try {
            if (Build.VERSION.SDK_INT >= 28) {
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                // The current signers only, not past certificates from a key rotation: a copy
                // re-signed with someone else's key must not pass on the strength of our history.
                info.signingInfo?.apkContentsSigners ?: emptyArray()
            } else {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
                    ?: emptyArray()
            }
        } catch (e: Exception) {
            emptyArray()
        }
        return signatures.map { sha256Hex(it.toByteArray()) }.toSet()
    }

    /** Package name of whatever installed this app, or null (adb, a file manager, unknown). */
    @Suppress("DEPRECATION")
    fun installer(context: Context): String? = try {
        if (Build.VERSION.SDK_INT >= 30) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            context.packageManager.getInstallerPackageName(context.packageName)
        }
    } catch (e: Exception) {
        null
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }
}
