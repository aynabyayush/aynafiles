package com.ayush.apnafiles.core

import java.math.BigInteger
import java.security.MessageDigest

/**
 * A device identity: a secp256k1 key pair plus the Ethereum-style address derived
 * from it. The address (not the key) is exchanged with the relay and shown to the
 * user; every API call is signed with the private key.
 */
class Identity(
    val privateKey: BigInteger,
    val publicKey: ByteArray
) {
    val address: String = Secp256k1.address(publicKey)
    val publicKeyHex: String = Hex.encode(publicKey)

    fun addressShort(): String = address.take(6) + "…" + address.takeLast(4)

    /** SHA-256 of the canonical request string, signed with ECDSA (r || s, low-S). */
    fun signMessage(message: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(message.toByteArray(Charsets.UTF_8))
        return Hex.encode(Secp256k1.sign(privateKey, digest))
    }

    fun export(): String {
        val json = org.json.JSONObject()
        json.put("v", 1)
        json.put("d", privateKey.toString(16))
        json.put("pub", publicKeyHex)
        return json.toString()
    }

    companion object {
        fun create(): Identity {
            val key = Secp256k1.randomPrivateKey()
            return Identity(key, Secp256k1.publicKey(key))
        }

        fun import(serialized: String): Identity {
            val json = org.json.JSONObject(serialized)
            val d = BigInteger(json.getString("d"), 16)
            val pub = Hex.decode(json.getString("pub"))
            val derived = Secp256k1.publicKey(d)
            require(derived.contentEquals(pub)) { "public key does not match private key" }
            return Identity(d, derived)
        }
    }
}
