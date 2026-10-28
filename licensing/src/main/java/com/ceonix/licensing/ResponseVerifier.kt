package com.ceonix.licensing

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/** The fields Google signs into a licensing response. */
class SignedResponse(
    val responseCode: Int,
    val nonce: Long,
    val packageName: String,
    val versionCode: String,
    val userId: String,
    val timestampMillis: Long,
) {
    val grantsLicense: Boolean
        get() = responseCode == ResponseCodes.LICENSED || responseCode == ResponseCodes.LICENSED_OLD_KEY
}

/** Response codes the Play Store reports (from LVL's LicenseValidator). */
object ResponseCodes {
    const val LICENSED = 0x0
    const val NOT_LICENSED = 0x1
    const val LICENSED_OLD_KEY = 0x2
    const val ERROR_NOT_MARKET_MANAGED = 0x3
    const val ERROR_SERVER_FAILURE = 0x4
    const val ERROR_OVER_QUOTA = 0x5
    const val ERROR_CONTACTING_SERVER = 0x101
    const val ERROR_INVALID_PACKAGE_NAME = 0x102
    const val ERROR_NON_MATCHING_UID = 0x103
}

/**
 * Checks that a licensing response really came from Google.
 *
 * The response code the listener receives as a plain `int` is never trusted: a patched Play Store
 * or a hooked binder can report LICENSED for anything. What counts is the code inside
 * `signedData`, and only once its RSA signature verifies against the app's own public key.
 * Anything that fails here is treated as if no answer had arrived at all.
 *
 * Pure JVM (no Android classes), so it is covered by plain unit tests.
 */
class ResponseVerifier(private val publicKey: PublicKey) {

    /** @return the signed fields, or null if the signature or the format does not hold up. */
    fun verify(signedData: String?, signature: String?): SignedResponse? {
        if (signedData.isNullOrEmpty() || signature.isNullOrEmpty()) return null
        val signatureBytes = Base64Codec.decode(signature) ?: return null
        val valid = try {
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initVerify(publicKey)
                update(signedData.toByteArray(Charsets.UTF_8))
                verify(signatureBytes)
            }
        } catch (e: Exception) {
            false
        }
        return if (valid) parse(signedData) else null
    }

    companion object {
        /** What the Play Store signs responses with (LVL: "SHA1withRSA"). */
        const val SIGNATURE_ALGORITHM = "SHA1withRSA"

        /** @return a verifier for the Base64 X.509 key from Play Console, or null if it is malformed. */
        fun fromBase64(publicKeyBase64: String): ResponseVerifier? {
            val bytes = Base64Codec.decode(publicKeyBase64) ?: return null
            if (bytes.isEmpty()) return null
            return try {
                ResponseVerifier(KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes)))
            } catch (e: Exception) {
                null
            }
        }

        /**
         * `responseCode|nonce|packageName|versionCode|userId|timestamp[:extras]`, as LVL's
         * ResponseData reads it. Extras (validity and grace hints for server-managed policies) are
         * ignored: this gate keeps a verified licence until Google itself says otherwise.
         */
        internal fun parse(signedData: String): SignedResponse? {
            val main = signedData.substringBefore(':')
            val fields = main.split('|')
            if (fields.size < 6) return null
            return try {
                SignedResponse(
                    responseCode = fields[0].toInt(),
                    nonce = fields[1].toLong(),
                    packageName = fields[2],
                    versionCode = fields[3],
                    userId = fields[4],
                    timestampMillis = fields[5].toLong(),
                )
            } catch (e: NumberFormatException) {
                null
            }
        }
    }
}
