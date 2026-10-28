package com.ceonix.licensing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class ResponseVerifierTest {

    private val ours = newKeyPair()
    private val theirs = newKeyPair()
    private val verifier = ResponseVerifier.fromBase64(publicKeyBase64(ours))!!

    @Test
    fun acceptsResponseSignedWithOurKey() {
        val data = "0|12345|com.example.app|7|user-hash|1700000000000"
        val response = verifier.verify(data, sign(data, ours))
        assertNotNull(response)
        response!!
        assertEquals(ResponseCodes.LICENSED, response.responseCode)
        assertEquals(12345L, response.nonce)
        assertEquals("com.example.app", response.packageName)
        assertEquals(1700000000000L, response.timestampMillis)
        assertTrue(response.grantsLicense)
    }

    @Test
    fun oldKeyStillGrants() {
        val data = "2|1|com.example.app|7|u|1"
        assertTrue(verifier.verify(data, sign(data, ours))!!.grantsLicense)
    }

    @Test
    fun extrasAfterColonAreIgnored() {
        val data = "0|1|com.example.app|7|u|1:VT=1700000000000&GT=1&GR=10"
        val response = verifier.verify(data, sign(data, ours))
        assertNotNull(response)
        assertEquals(1L, response!!.timestampMillis)
    }

    @Test
    fun notLicensedIsReportedButDoesNotGrant() {
        val data = "1|1|com.example.app|7|u|1"
        val response = verifier.verify(data, sign(data, ours))!!
        assertEquals(ResponseCodes.NOT_LICENSED, response.responseCode)
        assertFalse(response.grantsLicense)
    }

    @Test
    fun rejectsSignatureFromAnotherKey() {
        val data = "0|1|com.example.app|7|u|1"
        assertNull(verifier.verify(data, sign(data, theirs)))
    }

    @Test
    fun rejectsEditedData() {
        val data = "1|1|com.example.app|7|u|1"
        val signature = sign(data, ours)
        // NOT_LICENSED flipped to LICENSED after signing: the classic patch.
        assertNull(verifier.verify(data.replaceFirst("1|", "0|"), signature))
    }

    @Test
    fun rejectsMissingOrGarbageSignature() {
        val data = "0|1|com.example.app|7|u|1"
        assertNull(verifier.verify(data, null))
        assertNull(verifier.verify(data, ""))
        assertNull(verifier.verify(data, "not base64 !!!"))
        assertNull(verifier.verify(null, sign(data, ours)))
    }

    @Test
    fun rejectsSignedButMalformedData() {
        val short = "0|1|com.example.app"
        assertNull(verifier.verify(short, sign(short, ours)))
        val badNumber = "0|x|com.example.app|7|u|1"
        assertNull(verifier.verify(badNumber, sign(badNumber, ours)))
    }

    @Test
    fun malformedPublicKeyGivesNoVerifier() {
        assertNull(ResponseVerifier.fromBase64(""))
        assertNull(ResponseVerifier.fromBase64("AAAA"))
        assertNull(ResponseVerifier.fromBase64("%%%"))
    }

    @Test
    fun keyWithLineBreaksStillParses() {
        val wrapped = publicKeyBase64(ours).chunked(64).joinToString("\n")
        assertNotNull(ResponseVerifier.fromBase64(wrapped))
    }

    @Test
    fun base64MatchesTheJdk() {
        for (size in 0..40) {
            val bytes = ByteArray(size) { (it * 37 + size).toByte() }
            val encoded = Base64.getEncoder().encodeToString(bytes)
            assertTrue(bytes.contentEquals(Base64Codec.decode(encoded)!!))
        }
        assertNull(Base64Codec.decode("QUJD=QQ"))
    }

    @Test
    fun digestsNormalise() {
        assertEquals("DF4E5E90AB", LicenseConfig.normaliseDigest("df:4e:5e:90:ab"))
    }

    private fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun publicKeyBase64(pair: KeyPair): String =
        Base64.getEncoder().encodeToString(pair.public.encoded)

    private fun sign(data: String, pair: KeyPair): String {
        val signer = Signature.getInstance(ResponseVerifier.SIGNATURE_ALGORITHM)
        signer.initSign(pair.private)
        signer.update(data.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(signer.sign())
    }
}
