package io.timelimit.android.child

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** TOTP of docs/specification/protocol-new-ui.md, section 7: HMAC-SHA1, 30 s, 6 digits, base32 secret. */
// @tag:parent-code
object ParentCodeTotp {
    const val STEP_MILLIS = 30_000L
    const val DIGITS = 6

    fun codeAt(secret: ByteArray, step: Long): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret, "HmacSHA1")) }
        val hash = mac.doFinal(ByteArray(8) { (step ushr (56 - 8 * it)).toByte() })
        val offset = hash.last().toInt() and 0xf
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
                ((hash[offset + 1].toInt() and 0xff) shl 16) or
                ((hash[offset + 2].toInt() and 0xff) shl 8) or
                (hash[offset + 3].toInt() and 0xff)

        return (binary % 1_000_000).toString().padStart(DIGITS, '0')
    }

    /** The step the code belongs to — the current one or a neighbour, as clocks drift; null if it does not match. */
    fun matchingStep(base32Secret: String, code: String, now: Long): Long? {
        val secret = decodeBase32(base32Secret)
        val current = now / STEP_MILLIS

        return listOf(current, current - 1, current + 1).firstOrNull { codeAt(secret, it) == code }
    }

    fun decodeBase32(text: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0

        for (char in text.trimEnd('=').uppercase()) {
            val value = alphabet.indexOf(char)
            if (value < 0) throw IllegalArgumentException("not base32: $char")

            buffer = (buffer shl 5) or value
            bits += 5

            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xff)
            }
        }

        return out.toByteArray()
    }
}
