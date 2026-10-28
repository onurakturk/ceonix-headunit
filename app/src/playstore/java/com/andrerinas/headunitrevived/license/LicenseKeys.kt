package com.andrerinas.headunitrevived.license

/**
 * The Play flavour's licensing constants.
 *
 * Neither value is secret — the public key is public by definition, the digests identify
 * certificates — and the source is published anyway. The key is kept masked only so it is not a
 * single greppable string to swap in a repackaged APK; swapping it would also mean re-signing,
 * which the certificate check catches.
 *
 * Release builds refuse to start while a placeholder below is still in place (app/build.gradle.kts).
 * Generate the parts with tools/licensing/obscure_key.ps1.
 */
internal object LicenseKeys {

    // Play Console › Monetize with Play › Monetization setup › Licensing, masked with
    // tools/licensing/obscure_key.ps1 (2026-10-28).
    private val PUBLIC_KEY_PARTS: Array<String> = arrayOf(
        "c9033962570fb8d7c62d1b51760e90debd3d40625f34bcdfc50b3f635f34c1d8c90339625d02b2dac51b35616e34bef5fd33426b2e2cbefbf22e3653642b83ede26527534b5783eed0281c185912b7d4f62431555d349dc0fc7237585c02b0e8ee33",
        "196b5704d6a8c265194c7106d2cdb02e2913711695a1d03b24515f22cbd0e3083565490cd6ccea7e3a565651b5d6d60206596a13bea0de1d1c16582e9dc9cf380a6f5c08d6feaf044848564aadccec7d08155d1096aaf621197a710d9ce0c2281d70",
        "4f2c93abbd04004e2620d2ece93248524e3d81fcb6203d477137d2ecec3234152a0894b2b5230058782cd6d4b42b22464f20c9f5e93d174b442095cbf7191c65770eacd8ce731d654803cac0ed03196d4808bbdae77e43736e24aef4f61845662c1f",
        "ccfef27c0468743588dfb67c05155136a3e1ec101c762c16d6dfc30117632616bfe8c807391946539eacaf2f1f73312bb7a0ca1f3d656815bfdacb023d4951079ffdfc2323104720cacfc37226104e4ac1eaf43e3d572f33caf1b33d39645f34b8db",
    )
    private val PUBLIC_KEY_MASK = byteArrayOf(0x84.toByte(), 0x4a.toByte(), 0x70.toByte(), 0x20.toByte(), 0x1e.toByte(), 0x65.toByte(), 0xf9.toByte(), 0x99.toByte())

    /**
     * Certificates the Play build may be signed with.
     *
     * - App signing certificate: what Google Play signs delivered APKs with (Play Console › Test and
     *   release › App integrity › App signing). Customers only ever see this one.
     * - Upload certificate: locally built release APKs, for testing before upload.
     */
    val ALLOWED_CERTS: Set<String> = setOf(
        // App signing key certificate (Play Console › App integrity › App signing), 2026-10-28.
        "86:D0:1D:FB:A5:0B:B0:9D:44:F7:33:E9:98:E9:BB:0B:62:C3:20:07:CA:E8:B2:A8:C5:0F:89:61:78:4E:FB:9E",
        // Upload key (CN=Ceonix), read from dist/play-1.3.0 with keytool -printcert.
        "DF:4E:5E:90:3E:57:AE:FE:74:C1:66:16:4D:32:64:CE:99:1B:91:1F:83:81:6A:11:2C:4A:38:08:99:FE:EE:1B",
    )

    /** The developer machine's debug key. Accepted only by debug builds (see LicenseGuard). */
    const val DEBUG_CERT =
        "54:F8:E1:4F:B2:9E:CF:4E:FA:C7:8B:C1:AB:19:8F:1F:8C:6F:2C:C0:ED:99:46:E5:43:E1:0D:E5:7F:DE:90:B4"

    fun publicKey(): String {
        val out = StringBuilder()
        var i = 0
        for (part in PUBLIC_KEY_PARTS) {
            var p = 0
            while (p + 1 < part.length) {
                val b = part.substring(p, p + 2).toInt(16)
                out.append((b xor (PUBLIC_KEY_MASK[i % PUBLIC_KEY_MASK.size].toInt() and 0xFF)).toChar())
                i++
                p += 2
            }
        }
        return out.toString()
    }
}
