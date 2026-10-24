package com.ayush.apnafiles.core

/**
 * Small hex helpers used by the identity layer. Kept dependency-free so the crypto
 * package can be unit tested on a plain JVM.
 */
object Hex {
    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = DIGITS[v ushr 4]
            out[i * 2 + 1] = DIGITS[v and 0x0F]
        }
        return String(out)
    }

    fun decode(hex: String): ByteArray {
        val s = if (hex.startsWith("0x") || hex.startsWith("0X")) hex.substring(2) else hex
        require(s.length % 2 == 0) { "hex string must have an even length" }
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(s[i * 2], 16)
            val lo = Character.digit(s[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "invalid hex character" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}

/**
 * Keccak-256 (the original Keccak padding used by Ethereum, not SHA3-256).
 *
 * The device address is derived exactly like an Ethereum account address:
 * `0x` + last 20 bytes of `keccak256(uncompressedPublicKey[1..65])`.
 */
object Keccak256 {
    private const val RATE_BYTES = 136 // (1600 - 2 * 256) / 8

    private val ROUND_CONSTANTS = longArrayOf(
        0x0000000000000001uL.toLong(), 0x0000000000008082uL.toLong(),
        0x800000000000808auL.toLong(), 0x8000000080008000uL.toLong(),
        0x000000000000808buL.toLong(), 0x0000000080000001uL.toLong(),
        0x8000000080008081uL.toLong(), 0x8000000000008009uL.toLong(),
        0x000000000000008auL.toLong(), 0x0000000000000088uL.toLong(),
        0x0000000080008009uL.toLong(), 0x000000008000000auL.toLong(),
        0x000000008000808buL.toLong(), 0x800000000000008buL.toLong(),
        0x8000000000008089uL.toLong(), 0x8000000000008003uL.toLong(),
        0x8000000000008002uL.toLong(), 0x8000000000000080uL.toLong(),
        0x000000000000800auL.toLong(), 0x800000008000000auL.toLong(),
        0x8000000080008081uL.toLong(), 0x8000000000008080uL.toLong(),
        0x0000000080000001uL.toLong(), 0x8000000080008008uL.toLong()
    )

    private val ROTATION = intArrayOf(
        1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 2, 14,
        27, 41, 56, 8, 25, 43, 62, 18, 39, 61, 20, 44
    )

    private val PI_LANES = intArrayOf(
        10, 7, 11, 17, 18, 3, 5, 16, 8, 21, 24, 4,
        15, 23, 19, 13, 12, 2, 20, 14, 22, 9, 6, 1
    )

    fun digest(input: ByteArray): ByteArray {
        val state = LongArray(25)
        val padded = pad(input)
        val scratch = LongArray(5)

        var offset = 0
        while (offset < padded.size) {
            var lane = 0
            while (lane < RATE_BYTES / 8) {
                state[lane] = state[lane] xor readLongLe(padded, offset + lane * 8)
                lane++
            }
            permute(state, scratch)
            offset += RATE_BYTES
        }

        val out = ByteArray(32)
        var i = 0
        while (i < 32) {
            val lane = state[i / 8]
            out[i] = (lane ushr (8 * (i % 8))).toByte()
            i++
        }
        return out
    }

    private fun pad(input: ByteArray): ByteArray {
        val padLen = RATE_BYTES - (input.size % RATE_BYTES)
        val padded = ByteArray(input.size + padLen)
        input.copyInto(padded)
        padded[input.size] = 0x01
        padded[padded.size - 1] = (padded[padded.size - 1].toInt() or 0x80).toByte()
        return padded
    }

    private fun readLongLe(buf: ByteArray, at: Int): Long {
        var v = 0L
        var i = 0
        while (i < 8) {
            v = v or ((buf[at + i].toLong() and 0xFFL) shl (8 * i))
            i++
        }
        return v
    }

    private fun rotl(x: Long, n: Int): Long = (x shl n) or (x ushr (64 - n))

    private fun permute(a: LongArray, c: LongArray) {
        for (round in 0 until 24) {
            // Theta
            var i = 0
            while (i < 5) {
                c[i] = a[i] xor a[i + 5] xor a[i + 10] xor a[i + 15] xor a[i + 20]
                i++
            }
            i = 0
            while (i < 5) {
                val t = c[(i + 4) % 5] xor rotl(c[(i + 1) % 5], 1)
                var j = 0
                while (j < 25) {
                    a[j + i] = a[j + i] xor t
                    j += 5
                }
                i++
            }

            // Rho + Pi
            var t = a[1]
            i = 0
            while (i < 24) {
                val j = PI_LANES[i]
                val tmp = a[j]
                a[j] = rotl(t, ROTATION[i])
                t = tmp
                i++
            }

            // Chi
            var row = 0
            while (row < 25) {
                i = 0
                while (i < 5) {
                    c[i] = a[row + i]
                    i++
                }
                i = 0
                while (i < 5) {
                    a[row + i] = a[row + i] xor (c[(i + 1) % 5].inv() and c[(i + 2) % 5])
                    i++
                }
                row += 5
            }

            // Iota
            a[0] = a[0] xor ROUND_CONSTANTS[round]
        }
    }
}
