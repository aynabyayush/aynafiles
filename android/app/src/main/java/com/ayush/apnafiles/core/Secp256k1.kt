package com.ayush.apnafiles.core

import java.math.BigInteger
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal secp256k1 ECDSA with RFC 6979 deterministic nonces.
 *
 * The app deliberately ships its own small implementation instead of depending on a
 * Bouncy Castle swap: Android's platform providers do not consistently expose
 * secp256k1 and bundling BC collides with the platform's `org.bouncycastle` classes.
 * Only what the identity layer needs is implemented: key generation, public key
 * derivation, deterministic signing and verification.
 *
 * Signatures are 64-byte `r || s` with low-S normalisation, which is what the relay
 * (and every blockchain toolchain) expects.
 */
object Secp256k1 {

    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    private val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val GX = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
    private val GY = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    private val HALF_N = N.shiftRight(1)
    private val SEVEN = BigInteger.valueOf(7)
    private val THREE = BigInteger.valueOf(3)
    private val EIGHT = BigInteger.valueOf(8)

    private data class Jac(val x: BigInteger, val y: BigInteger, val z: BigInteger)

    private val INFINITY = Jac(BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
    private val G = Jac(GX, GY, BigInteger.ONE)

    fun randomPrivateKey(random: SecureRandom = SecureRandom()): BigInteger {
        while (true) {
            val candidate = BigInteger(256, random)
            if (candidate.signum() > 0 && candidate < N) return candidate
        }
    }

    /** Uncompressed public key: `0x04 || X(32) || Y(32)`. */
    fun publicKey(privateKey: BigInteger): ByteArray {
        require(privateKey.signum() > 0 && privateKey < N) { "private key out of range" }
        val point = multiply(G, privateKey)
        val affine = toAffine(point) ?: throw IllegalStateException("invalid private key")
        val out = ByteArray(65)
        out[0] = 0x04
        writePadded(affine.first, out, 1)
        writePadded(affine.second, out, 33)
        return out
    }

    /** Ethereum-style address: `0x` + last 20 bytes of keccak256(X || Y). */
    fun address(publicKey: ByteArray): String {
        require(publicKey.size == 65 && publicKey[0] == 0x04.toByte()) { "uncompressed public key required" }
        val body = publicKey.copyOfRange(1, 65)
        val hash = Keccak256.digest(body)
        return "0x" + Hex.encode(hash.copyOfRange(12, 32))
    }

    fun sign(privateKey: BigInteger, digest: ByteArray): ByteArray {
        require(digest.size == 32) { "digest must be 32 bytes" }
        val d = privateKey.mod(N)
        val h1 = BigInteger(1, digest)

        var v = ByteArray(32) { 0x01 }
        var k = ByteArray(32) { 0x00 }
        val xOctets = toOctets32(d)
        val hOctets = toOctets32(h1.mod(N))

        k = hmac(k, v, byteArrayOf(0x00), xOctets, hOctets)
        v = hmac(k, v)
        k = hmac(k, v, byteArrayOf(0x01), xOctets, hOctets)
        v = hmac(k, v)

        while (true) {
            v = hmac(k, v)
            val nonce = BigInteger(1, v)
            if (nonce.signum() > 0 && nonce < N) {
                val point = toAffine(multiply(G, nonce))
                if (point != null) {
                    val r = point.first.mod(N)
                    if (r.signum() != 0) {
                        var s = nonce.modInverse(N).multiply(h1.add(r.multiply(d))).mod(N)
                        if (s.signum() != 0) {
                            if (s > HALF_N) s = N.subtract(s)
                            val out = ByteArray(64)
                            writePadded(r, out, 0)
                            writePadded(s, out, 32)
                            return out
                        }
                    }
                }
            }
            k = hmac(k, v, byteArrayOf(0x00))
            v = hmac(k, v)
        }
    }

    fun verify(publicKey: ByteArray, digest: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 65 || publicKey[0] != 0x04.toByte()) return false
        if (signature.size != 64 || digest.size != 32) return false

        val r = BigInteger(1, signature.copyOfRange(0, 32))
        val s = BigInteger(1, signature.copyOfRange(32, 64))
        if (r.signum() <= 0 || r >= N || s.signum() <= 0 || s >= N) return false

        val z = BigInteger(1, digest).mod(N)
        val w = s.modInverse(N)
        val u1 = z.multiply(w).mod(N)
        val u2 = r.multiply(w).mod(N)

        val qx = BigInteger(1, publicKey.copyOfRange(1, 33))
        val qy = BigInteger(1, publicKey.copyOfRange(33, 65))
        if (!isOnCurve(qx, qy)) return false

        val result = toAffine(add(multiply(G, u1), multiply(Jac(qx, qy, BigInteger.ONE), u2))) ?: return false
        return result.first.mod(N) == r
    }

    private fun isOnCurve(x: BigInteger, y: BigInteger): Boolean {
        if (x.signum() < 0 || x >= P || y.signum() < 0 || y >= P) return false
        val lhs = y.multiply(y).mod(P)
        val rhs = x.multiply(x).mod(P).multiply(x).add(SEVEN).mod(P)
        return lhs == rhs
    }

    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (part in parts) mac.update(part)
        return mac.doFinal()
    }

    private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray = hmacSha256(key, *parts)

    private fun toOctets32(value: BigInteger): ByteArray {
        val out = ByteArray(32)
        writePadded(value.mod(N), out, 0)
        return out
    }

    private fun writePadded(value: BigInteger, dest: ByteArray, offset: Int) {
        val raw = value.toByteArray()
        val trimmed = if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else raw
        val target = offset + 32 - trimmed.size
        for (i in trimmed.indices) {
            val index = target + i
            if (index in dest.indices) dest[index] = trimmed[i]
        }
    }

    private fun multiply(base: Jac, scalar: BigInteger): Jac {
        var result = INFINITY
        var addend = base
        var i = 0
        val bits = scalar.bitLength()
        while (i < bits) {
            if (scalar.testBit(i)) result = add(result, addend)
            addend = double(addend)
            i++
        }
        return result
    }

    private fun double(p: Jac): Jac {
        if (p.z.signum() == 0 || p.y.signum() == 0) return INFINITY
        val a = p.x.multiply(p.x).mod(P)
        val b = p.y.multiply(p.y).mod(P)
        val c = b.multiply(b).mod(P)
        val xb = p.x.add(b).mod(P)
        val d = xb.multiply(xb).subtract(a).subtract(c).shiftLeft(1).mod(P)
        val e = a.multiply(THREE).mod(P)
        val f = e.multiply(e).mod(P)
        val x3 = f.subtract(d.shiftLeft(1)).mod(P)
        val y3 = e.multiply(d.subtract(x3)).subtract(c.multiply(EIGHT)).mod(P)
        val z3 = p.y.multiply(p.z).shiftLeft(1).mod(P)
        return Jac(x3, y3, z3)
    }

    private fun add(p: Jac, q: Jac): Jac {
        if (p.z.signum() == 0) return q
        if (q.z.signum() == 0) return p

        val z1z1 = p.z.multiply(p.z).mod(P)
        val z2z2 = q.z.multiply(q.z).mod(P)
        val u1 = p.x.multiply(z2z2).mod(P)
        val u2 = q.x.multiply(z1z1).mod(P)
        val s1 = p.y.multiply(z2z2).multiply(q.z).mod(P)
        val s2 = q.y.multiply(z1z1).multiply(p.z).mod(P)

        if (u1 == u2) {
            return if (s1 == s2) double(p) else INFINITY
        }

        val h = u2.subtract(u1).mod(P)
        val r = s2.subtract(s1).mod(P)
        val h2 = h.multiply(h).mod(P)
        val h3 = h2.multiply(h).mod(P)
        val u1h2 = u1.multiply(h2).mod(P)
        val x3 = r.multiply(r).subtract(h3).subtract(u1h2.shiftLeft(1)).mod(P)
        val y3 = r.multiply(u1h2.subtract(x3)).subtract(s1.multiply(h3)).mod(P)
        val z3 = h.multiply(p.z).multiply(q.z).mod(P)
        return Jac(x3, y3, z3)
    }

    private fun toAffine(p: Jac): Pair<BigInteger, BigInteger>? {
        if (p.z.signum() == 0) return null
        val zInv = p.z.modInverse(P)
        val zInv2 = zInv.multiply(zInv).mod(P)
        val x = p.x.multiply(zInv2).mod(P)
        val y = p.y.multiply(zInv2).multiply(zInv).mod(P)
        return x to y
    }

    /** Exposed for tests only. */
    internal fun curveOrder(): BigInteger = N
}
