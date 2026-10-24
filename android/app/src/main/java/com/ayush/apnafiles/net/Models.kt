package com.ayush.apnafiles.net

import kotlinx.serialization.Serializable

/** A device paired with this one, as reported by the relay. */
@Serializable
data class PeerDto(
    val address: String = "",
    val name: String = "",
    val pairId: String = "",
    val online: Boolean = false,
    val lastSeen: Long = 0L
)

@Serializable
data class PairRequestDto(
    val id: String = "",
    val from: String = "",
    val to: String = "",
    val fromName: String = "",
    val toName: String = "",
    val ts: Long = 0L
)

/** Response of `POST /v1/register` and `GET /v1/peers`. */
@Serializable
data class SessionDto(
    val address: String = "",
    val name: String = "",
    val peers: List<PeerDto> = emptyList(),
    val incoming: List<PairRequestDto> = emptyList(),
    val outgoing: List<PairRequestDto> = emptyList()
)

@Serializable
data class RegisterPayload(val name: String, val platform: String = "android")

@Serializable
data class PairRequestBody(val target: String)

@Serializable
data class PairRequestResult(val requestId: String = "")

@Serializable
data class RespondBody(val requestId: String, val accept: Boolean)

@Serializable
data class RespondResult(val pairId: String = "", val peer: PeerDto? = null)

@Serializable
data class InviteResult(val token: String = "", val expiresAt: Long = 0L)

@Serializable
data class ClaimBody(val token: String)

@Serializable
data class RemoveBody(val pairId: String)

@Serializable
data class OkResult(val ok: Boolean = false)

@Serializable
data class HealthResult(val ok: Boolean = false, val version: String = "")

/**
 * One file offered by a device. [id] is only meaningful on the device that owns it
 * (it wraps a MediaStore id or a path inside the shared folder).
 */
@Serializable
data class RemoteFile(
    val id: String = "",
    val name: String = "",
    val size: Long = 0L,
    val mime: String = "",
    val kind: String = "file",
    val folder: String = "",
    val modified: Long = 0L
)

/**
 * Single envelope for every relay message. A flat shape keeps the protocol cheap to
 * encode and forgiving when either side adds fields.
 */
@Serializable
data class Envelope(
    val t: String = "",
    val id: String = "",
    val action: String = "",
    val fileId: String = "",
    val ok: Boolean = true,
    val code: String = "",
    val message: String = "",
    val files: List<RemoteFile> = emptyList(),
    val data: String = "",
    val size: Long = 0L,
    val mime: String = "",
    val name: String = "",
    val seq: Int = 0,
    val peerOnline: Boolean = false,
    val address: String = "",
    val ts: Long = 0L
)

object RelayAction {
    const val LIST = "list"
    const val THUMB = "thumb"
    const val OPEN = "open"
}

object RelayType {
    const val REQUEST = "req"
    const val RESPONSE = "res"
    const val ERROR = "err"
    const val CHUNK = "chunk"
    const val DONE = "done"
    const val ACK = "ack"
    const val PRESENCE = "presence"
    const val WELCOME = "welcome"
    const val PING = "ping"
}

class ApiException(message: String, val statusCode: Int = 0) : Exception(message)
