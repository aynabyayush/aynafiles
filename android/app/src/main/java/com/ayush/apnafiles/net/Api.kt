package com.ayush.apnafiles.net

import com.ayush.apnafiles.core.Hex
import com.ayush.apnafiles.core.Identity
import com.ayush.apnafiles.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Every request carries a secp256k1 signature over
 * `METHOD:path:sha256(body):timestamp`. The relay derives the device address from the
 * public key, so the address can never be spoofed by the client.
 */
class Api(val identity: Identity, private val prefs: Prefs) {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val baseUrl: String get() = prefs.relayUrl.value.trim().trimEnd('/')

    fun isConfigured(): Boolean = baseUrl.startsWith("https://") || baseUrl.startsWith("http://")

    fun authHeaders(method: String, path: String, body: String): Map<String, String> {
        val ts = System.currentTimeMillis() / 1000
        val bodyHash = Hex.encode(MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8)))
        val signature = identity.signMessage("$method:$path:$bodyHash:$ts")
        return mapOf(
            "X-Apna-Pub" to identity.publicKeyHex,
            "X-Apna-Ts" to ts.toString(),
            "X-Apna-Sig" to signature
        )
    }

    fun websocketUrl(path: String): String {
        val base = baseUrl
        require(base.isNotEmpty()) { "relay URL is not configured" }
        val wsBase = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> base
        }
        return wsBase + path
    }

    private fun execute(method: String, path: String, body: String?): String {
        if (!isConfigured()) throw ApiException("Relay URL is not set. Open Settings and paste your Worker URL.")
        val payload = body ?: ""
        val builder = Request.Builder()
            .url(baseUrl + path)
            .header("Accept", "application/json")
        authHeaders(method, path, payload).forEach { (key, value) -> builder.header(key, value) }
        builder.method(
            method,
            if (body == null) null else payload.toRequestBody("application/json".toMediaType())
        )

        try {
            http.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val message = runCatching {
                        json.decodeFromString(ErrorBody.serializer(), text).message
                    }.getOrNull().takeUnless { it.isNullOrBlank() }
                        ?: "Relay error ${response.code}"
                    throw ApiException(message, response.code)
                }
                return text
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: IOException) {
            throw ApiException(e.message ?: "Network unavailable")
        }
    }

    @kotlinx.serialization.Serializable
    private data class ErrorBody(val error: String = "", val message: String = "")

    suspend fun health(): String = withContext(Dispatchers.IO) {
        execute("GET", "/v1/health", null)
    }

    suspend fun register(): SessionDto = withContext(Dispatchers.IO) {
        val body = json.encodeToString(RegisterPayload.serializer(), RegisterPayload(prefs.deviceName.value))
        json.decodeFromString(SessionDto.serializer(), execute("POST", "/v1/register", body))
    }

    suspend fun peers(): SessionDto = withContext(Dispatchers.IO) {
        json.decodeFromString(SessionDto.serializer(), execute("GET", "/v1/peers", null))
    }

    suspend fun requestPair(target: String): PairRequestResult = withContext(Dispatchers.IO) {
        val body = json.encodeToString(PairRequestBody.serializer(), PairRequestBody(target.trim()))
        json.decodeFromString(PairRequestResult.serializer(), execute("POST", "/v1/pair/request", body))
    }

    suspend fun respondPair(requestId: String, accept: Boolean): RespondResult = withContext(Dispatchers.IO) {
        val body = json.encodeToString(RespondBody.serializer(), RespondBody(requestId, accept))
        json.decodeFromString(RespondResult.serializer(), execute("POST", "/v1/pair/respond", body))
    }

    suspend fun createInvite(): InviteResult = withContext(Dispatchers.IO) {
        json.decodeFromString(InviteResult.serializer(), execute("POST", "/v1/pair/invite", "{}"))
    }

    suspend fun claimInvite(token: String): RespondResult = withContext(Dispatchers.IO) {
        val body = json.encodeToString(ClaimBody.serializer(), ClaimBody(token))
        json.decodeFromString(RespondResult.serializer(), execute("POST", "/v1/pair/claim", body))
    }

    suspend fun removePair(pairId: String): Boolean = withContext(Dispatchers.IO) {
        val body = json.encodeToString(RemoveBody.serializer(), RemoveBody(pairId))
        json.decodeFromString(OkResult.serializer(), execute("POST", "/v1/pair/remove", body)).ok
    }
}
