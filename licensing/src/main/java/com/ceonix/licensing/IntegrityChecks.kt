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
     * Whether the installed APK is signed with one of [allowedSha256] (normalised digests).
     *
     * API 28+: asks [PackageManager.hasSigningCertificate], which also accepts a certificate the
     * package has *proven* it rotated away from. That matters for Google Play: on recent Android
     * the APK Play delivers carries a key-rotation record, and the platform then reports a
     * different, newer Play key as the current signer — measured 28 Oct on a Galaxy S23 (Android
     * 16): the app signing certificate from Play Console (86:D0…) was the oldest entry of a
     * three-certificate lineage and apkContentsSigners returned another one (85:9F…), so every
     * Play copy read as re-signed. Accepting the lineage is safe: a rotation proof has to be signed
     * by the old key, which a repackager does not have.
     *
     * Below 28 there is no rotation; the current signers must all be allowed.
     */
    @SuppressLint("PackageManagerGetSignatures")
    @Suppress("DEPRECATION")
    fun isSignedByAllowed(context: Context, allowedSha256: Set<String>): Boolean {
        if (allowedSha256.isEmpty()) return false
        val pm = context.packageManager
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                allowedSha256.any { digest ->
                    val bytes = hexToBytes(digest) ?: return@any false
                    pm.hasSigningCertificate(context.packageName, bytes, PackageManager.CERT_INPUT_SHA256)
                }
            } else {
                val signatures = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                    .signatures ?: emptyArray<Signature>()
                val actual = signatures.map { sha256Hex(it.toByteArray()) }.toSet()
                actual.isNotEmpty() && allowedSha256.containsAll(actual)
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * For diagnostics only: digests of the current signers and, on API 28+, of the whole proven
     * signing history. Never used to decide anything.
     */
    @SuppressLint("PackageManagerGetSignatures")
    @Suppress("DEPRECATION")
    fun describeSigners(context: Context): String = try {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            val current = info?.apkContentsSigners.orEmpty().map { sha256Hex(it.toByteArray()) }
            val history = info?.signingCertificateHistory.orEmpty().map { sha256Hex(it.toByteArray()) }
            "current=$current history=$history"
        } else {
            val sigs = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures.orEmpty()
            "current=${sigs.map { sha256Hex(it.toByteArray()) }}"
        }
    } catch (e: Exception) {
        "unavailable (${e.javaClass.simpleName})"
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            null
        }
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
