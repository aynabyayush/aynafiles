package com.ayush.apnafiles.data

import org.json.JSONObject

/**
 * The QR code carries a short-lived invite token together with the relay that issued
 * it, so the scanning device does not need to be configured first.
 *
 * The QR never contains a private key: claiming an invite only creates a pairing, and
 * the inviter has already approved it by opening the QR screen.
 */
object QrInvite {

    data class Payload(
        val relay: String,
        val token: String,
        val address: String,
        val name: String
    )

    fun encode(relay: String, token: String, address: String, name: String): String {
        return JSONObject()
            .put("v", 1)
            .put("relay", relay)
            .put("token", token)
            .put("address", address)
            .put("name", name)
            .toString()
    }

    fun parse(text: String): Payload? {
        return try {
            val json = JSONObject(text.trim())
            if (json.optInt("v", 0) != 1) return null
            val token = json.optString("token")
            if (token.isBlank()) return null
            Payload(
                relay = json.optString("relay"),
                token = token,
                address = json.optString("address"),
                name = json.optString("name")
            )
        } catch (_: Exception) {
            null
        }
    }
}
