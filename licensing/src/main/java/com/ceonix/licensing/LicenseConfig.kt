package com.ceonix.licensing

/**
 * What [LicenseGate] needs to know about the app it guards.
 *
 * @param publicKeyBase64 the app's licensing key from Play Console (Monetize with Play ›
 *   Monetization setup › Licensing), Base64 X.509. Every response the Play Store hands back is
 *   checked against it; without it nothing can be verified and the gate stays closed.
 * @param allowedCertSha256 SHA-256 digests of the certificates this app may be signed with, hex
 *   with or without colons, any case. For a Play app that is the **app signing** certificate
 *   (Play Console › Test and release › App integrity) — not the upload key, because Play re-signs
 *   what it delivers. Add the upload certificate as well to keep locally built release APKs usable.
 *   On recent Android the platform may report a newer, rotated Play key as the current signer; the
 *   check accepts the proven key history, so the digest shown in Play Console keeps matching.
 * @param keyAlias Android Keystore alias of the device-bound key that wraps the stored response.
 * @param storeFileName file under `noBackupFilesDir`, so Auto Backup never carries it to another
 *   device (where it could not be decrypted anyway).
 * @param checkTimeoutMs how long one online check may take before it counts as "unavailable".
 * @param log diagnostics sink; every decision is reported through it.
 */
class LicenseConfig(
    val publicKeyBase64: String,
    allowedCertSha256: Set<String>,
    val keyAlias: String = "license_binding_v1",
    val storeFileName: String = "license.bin",
    val checkTimeoutMs: Long = 20_000L,
    val log: (String) -> Unit = {},
) {
    /** Normalised: upper-case hex, no separators. */
    val allowedCertSha256: Set<String> = allowedCertSha256.map(::normaliseDigest).toSet()

    companion object {
        fun normaliseDigest(digest: String): String =
            digest.filter { it.isLetterOrDigit() }.uppercase()
    }
}
