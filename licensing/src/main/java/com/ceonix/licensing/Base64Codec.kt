package com.ceonix.licensing

/**
 * Standard-alphabet Base64 decoding.
 *
 * Neither of the usual choices fits: `java.util.Base64` is API 26+ and the module goes down to 21,
 * and `android.util.Base64` is a stub on the JVM, which would keep [ResponseVerifier] out of plain
 * unit tests. Whitespace is skipped (Play Console shows the key wrapped); anything else that is
 * not in the alphabet makes the input invalid.
 */
internal object Base64Codec {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val INDEX = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { i, c -> table[c.code] = i }
    }

    /** @return the decoded bytes, or null when [input] is not valid Base64. */
    fun decode(input: String): ByteArray? {
        val out = java.io.ByteArrayOutputStream(input.length * 3 / 4)
        var buffer = 0
        var bits = 0
        var padding = 0
        for (c in input) {
            if (c.isWhitespace()) continue
            if (c == '=') {
                padding++
                continue
            }
            // Data after padding is malformed.
            if (padding > 0) return null
            val value = if (c.code < 128) INDEX[c.code] else -1
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        if (padding > 2) return null
        return out.toByteArray()
    }
}
