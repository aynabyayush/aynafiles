package com.ayush.apnafiles

import com.ayush.apnafiles.core.Hex
import com.ayush.apnafiles.core.Keccak256
import com.ayush.apnafiles.core.Secp256k1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Cross-checks the app's crypto against reference values produced by @noble/curves +
 * @noble/hashes (the exact libraries the Cloudflare Worker verifies with), so a
 * signature produced on the phone is guaranteed to validate on the relay.
 */
class CryptoTest {

    private val privateKeyHex = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
    private val publicKeyHex =
        "04555a14d855d2bb43117e50a9783bdc573c23a3e6999029ddae7347490dbed3b2268f9a17a047458e7703b48db713e713993a40e5e3319d10872514672916f692"
    private val addressHex = "0xbb2dc553881fa2142ecebefc81f7a973fcf2470f"
    private val digestHex = "a0489b61ce2e501958ee3711fafb6c55b6fc98b170d0d4bdad59cb77e2e5bc31"
    private val signatureHex =
        "2b4d763cc97f08cc1f3aafe0bde5ae62fc2fff0d69959ca921d03f98a1a0ef281d44989368b1b0a0a2e56e50702763de46175714707138608dcdcdf1d9e6078f"

    @Test
    fun `keccak256 matches ethereum test vectors`() {
        assertEquals(
            "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470",
            Hex.encode(Keccak256.digest(ByteArray(0)))
        )
        assertEquals(
            "1c8aff950685c2ed4bc3174f3472287b56d9517b9c948127319a09a7a36deac8",
            Hex.encode(Keccak256.digest("hello".toByteArray()))
        )
    }

    @Test
    fun `public key derivation matches reference`() {
        val pub = Secp256k1.publicKey(BigInteger(privateKeyHex, 16))
        assertEquals(publicKeyHex, Hex.encode(pub))
    }

    @Test
    fun `address derivation matches reference`() {
        assertEquals(addressHex, Secp256k1.address(Hex.decode(publicKeyHex)))
    }

    @Test
    fun `signature is byte-identical to the reference implementation`() {
        val signature = Secp256k1.sign(BigInteger(privateKeyHex, 16), Hex.decode(digestHex))
        assertEquals(signatureHex, Hex.encode(signature))
    }

    @Test
    fun `reference signature verifies locally`() {
        assertTrue(
            Secp256k1.verify(
                Hex.decode(publicKeyHex),
                Hex.decode(digestHex),
                Hex.decode(signatureHex)
            )
        )
    }

    @Test
    fun `tampered signatures and digests are rejected`() {
        val pub = Hex.decode(publicKeyHex)
        val sig = Hex.decode(signatureHex)
        val badDigest = Hex.decode(digestHex).copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse(Secp256k1.verify(pub, badDigest, sig))

        val badSig = sig.copyOf().also { it[63] = (it[63].toInt() xor 1).toByte() }
        assertFalse(Secp256k1.verify(pub, Hex.decode(digestHex), badSig))
    }

    @Test
    fun `signed request digest is stable`() {
        // Must stay in lockstep with the Worker: sha256("METHOD:path:sha256(body):ts")
        val body = """{"name":"Pixel","platform":"android"}"""
        val bodyHash = Hex.encode(MessageDigest.getInstance("SHA-256").digest(body.toByteArray()))
        val message = "POST:/v1/register:$bodyHash:1700000000"
        val digest = MessageDigest.getInstance("SHA-256").digest(message.toByteArray())
        assertEquals(digestHex, Hex.encode(digest))
    }
}
