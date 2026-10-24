package com.ayush.apnafiles.net

import android.util.Base64
import com.ayush.apnafiles.files.MediaRepo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class PeerListing(val files: List<RemoteFile>, val code: String)

/**
 * One WebSocket to one paired device, through the Cloudflare relay.
 *
 * The same connection carries both directions: this client can ask the peer for a
 * listing, a thumbnail or a file, and it answers those requests when the peer asks.
 * Nothing is stored on the relay server.
 */
class RelayClient(
    val pairId: String,
    val peerAddress: String,
    private val api: Api,
    private val repo: MediaRepo,
    private val scope: CoroutineScope
) {

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Envelope>>()
    private val sinks = ConcurrentHashMap<String, DownloadSink>()
    private val gates = ConcurrentHashMap<String, CreditGate>()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _peerOnline = MutableStateFlow(false)
    val peerOnline: StateFlow<Boolean> = _peerOnline.asStateFlow()

    @Volatile
    private var socket: WebSocket? = null
    @Volatile
    private var stopped = false
    private var attempts = 0
    private var reconnectJob: Job? = null

    fun start() {
        stopped = false
        if (socket == null) connect()
    }

    fun stop() {
        stopped = true
        reconnectJob?.cancel()
        failEverything(IOException("Session closed"))
        socket?.close(1000, "bye")
        socket = null
        _connected.value = false
        _peerOnline.value = false
    }

    fun isReady(): Boolean = socket != null && _connected.value

    // ---------------------------------------------------------------- outgoing

    suspend fun listFiles(): PeerListing = withContext(Dispatchers.IO) {
        val response = roundTrip(
            Envelope(t = RelayType.REQUEST, id = newId(), action = RelayAction.LIST),
            timeoutMs = 25_000
        )
        if (response.t == RelayType.ERROR || !response.ok) {
            throw IOException(response.message.ifBlank { "The other device could not list its files" })
        }
        PeerListing(response.files, response.code)
    }

    suspend fun thumbnail(fileId: String): ByteArray? = withContext(Dispatchers.IO) {
        val response = roundTrip(
            Envelope(t = RelayType.REQUEST, id = newId(), action = RelayAction.THUMB, fileId = fileId),
            timeoutMs = 25_000
        )
        if (response.t == RelayType.ERROR || !response.ok) return@withContext null
        if (response.data.isEmpty()) null
        else runCatching { Base64.decode(response.data, Base64.NO_WRAP) }.getOrNull()
    }

    /**
     * Streams [fileId] into [dest]. The peer never stores the file for us; bytes flow
     * through the relay and are discarded there.
     */
    suspend fun download(
        fileId: String,
        dest: File,
        onProgress: (received: Long, total: Long) -> Unit
    ): Envelope = withContext(Dispatchers.IO) {
        val id = newId()
        dest.parentFile?.mkdirs()
        val sink = DownloadSink(id, dest, onProgress)
        sinks[id] = sink
        try {
            if (!send(Envelope(t = RelayType.REQUEST, id = id, action = RelayAction.OPEN, fileId = fileId))) {
                throw IOException("Not connected to the other device")
            }
            withTimeout(30 * 60 * 1000L) { sink.done.await() }
            sink.info
        } catch (t: Throwable) {
            sink.abort(t)
            throw if (t is IOException) t else IOException(t.message ?: "Transfer failed", t)
        } finally {
            sinks.remove(id)
        }
    }

    private suspend fun roundTrip(envelope: Envelope, timeoutMs: Long): Envelope {
        val deferred = CompletableDeferred<Envelope>()
        pending[envelope.id] = deferred
        if (!send(envelope)) {
            pending.remove(envelope.id)
            throw IOException("Not connected to the other device")
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(envelope.id)
        }
    }

    // ---------------------------------------------------------------- incoming

    private suspend fun serve(envelope: Envelope) = withContext(Dispatchers.IO) {
        val id = envelope.id
        try {
            when (envelope.action) {
                RelayAction.LIST -> {
                    val mediaGranted = repo.hasAnyMediaPermission()
                    val files = repo.list().take(MAX_LIST_ENTRIES)
                    val code = when {
                        !mediaGranted -> "no_media_permission"
                        files.size >= MAX_LIST_ENTRIES -> "truncated"
                        else -> ""
                    }
                    send(Envelope(t = RelayType.RESPONSE, id = id, ok = true, files = files, code = code))
                }

                RelayAction.THUMB -> {
                    val bytes = repo.thumbnail(envelope.fileId, THUMB_PX)
                    send(
                        Envelope(
                            t = RelayType.RESPONSE,
                            id = id,
                            ok = true,
                            data = if (bytes == null) "" else Base64.encodeToString(bytes, Base64.NO_WRAP)
                        )
                    )
                }

                RelayAction.OPEN -> stream(envelope)

                else -> send(
                    Envelope(t = RelayType.ERROR, id = id, ok = false, message = "Unknown action")
                )
            }
        } catch (t: Throwable) {
            send(
                Envelope(
                    t = RelayType.ERROR,
                    id = id,
                    ok = false,
                    message = t.message ?: "The other device could not read this file"
                )
            )
        }
    }

    private suspend fun stream(envelope: Envelope) = withContext(Dispatchers.IO) {
        val id = envelope.id
        var gate: CreditGate? = null
        try {
            val handle = repo.describe(envelope.fileId)
                ?: throw IOException("File is no longer available on the other device")
            if (handle.size > MAX_TRANSFER_BYTES) {
                throw IOException("File is too large to stream (limit 2 GB)")
            }

            val announced = send(
                Envelope(
                    t = RelayType.RESPONSE,
                    id = id,
                    ok = true,
                    // 0 means the provider does not know the size; -1 tells the peer to
                    // skip the length check instead of expecting an empty file.
                    size = if (handle.size > 0) handle.size else -1,
                    mime = handle.mime,
                    name = handle.name
                )
            )
            if (!announced) return@withContext

            val credit = CreditGate(WINDOW_CHUNKS)
            gate = credit
            gates[id] = credit

            val input = repo.openStream(envelope.fileId)
                ?: throw IOException("Could not open the file")

            input.use { source ->
                val buffer = ByteArray(CHUNK_BYTES)
                var seq = 0
                while (true) {
                    credit.awaitCredit(seq)
                    val read = source.read(buffer)
                    if (read <= 0) break
                    val payload = if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read)
                    val sent = send(
                        Envelope(
                            t = RelayType.CHUNK,
                            id = id,
                            seq = seq,
                            data = Base64.encodeToString(payload, Base64.NO_WRAP)
                        )
                    )
                    if (!sent) throw IOException("Connection lost during transfer")
                    seq++
                }
            }
            send(Envelope(t = RelayType.DONE, id = id))
        } finally {
            gate?.let { gates.remove(id) }
        }
    }

    private inner class DownloadSink(
        private val id: String,
        private val target: File,
        private val onProgress: (Long, Long) -> Unit
    ) {
        private val part = File(target.parentFile, target.name + ".part")
        private val output = FileOutputStream(part)
        val done = CompletableDeferred<Unit>()
        var info: Envelope = Envelope()
            private set
        private var received = 0L
        private var total = -1L
        private var chunks = 0
        private var closed = false

        fun onResponse(envelope: Envelope) {
            if (closed) return
            if (!envelope.ok || envelope.t == RelayType.ERROR) {
                abort(IOException(envelope.message.ifBlank { "The other device refused the file" }))
                return
            }
            info = envelope
            total = envelope.size
            onProgress(0L, total)
        }

        fun onChunk(envelope: Envelope) {
            if (closed) return
            try {
                val bytes = Base64.decode(envelope.data, Base64.NO_WRAP)
                output.write(bytes)
                received += bytes.size
                chunks++
                onProgress(received, total)
                if (chunks % ACK_EVERY == 0) {
                    send(Envelope(t = RelayType.ACK, id = id, seq = envelope.seq))
                }
            } catch (t: Throwable) {
                abort(IOException("Could not write the incoming file", t))
            }
        }

        fun onDone() {
            if (closed) return
            closed = true
            runCatching { output.close() }
            if (total >= 0 && received != total) {
                part.delete()
                done.completeExceptionally(IOException("Transfer ended early ($received of $total bytes)"))
                return
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.delete()
                done.completeExceptionally(IOException("Could not finalise the downloaded file"))
                return
            }
            done.complete(Unit)
        }

        fun abort(cause: Throwable) {
            if (closed) return
            closed = true
            runCatching { output.close() }
            part.delete()
            done.completeExceptionally(cause)
        }
    }

    private class CreditGate(private val window: Int) {
        private val acked = MutableStateFlow(-1)

        fun onAck(seq: Int) {
            if (seq > acked.value) acked.value = seq
        }

        suspend fun awaitCredit(seq: Int) {
            if (seq - window <= acked.value) return
            val reached = withTimeoutOrNull(90_000) { acked.first { it >= seq - window } }
            if (reached == null) throw IOException("Transfer stalled (the other device stopped responding)")
        }
    }

    // ---------------------------------------------------------------- plumbing

    private fun connect() {
        if (stopped || !api.isConfigured()) return
        val path = "/v1/relay/$pairId"
        val request = try {
            Request.Builder()
                .url(api.websocketUrl(path))
                .apply { api.authHeaders("GET", path, "").forEach { (k, v) -> header(k, v) } }
                .build()
        } catch (t: Throwable) {
            scheduleReconnect()
            return
        }
        socket = api.http.newWebSocket(request, Listener())
    }

    private fun scheduleReconnect() {
        if (stopped) return
        reconnectJob?.cancel()
        val wait = minOf(30_000L, 1_000L shl minOf(attempts, 5))
        attempts++
        reconnectJob = scope.launch {
            delay(wait)
            if (!stopped && socket == null) connect()
        }
    }

    private fun send(envelope: Envelope): Boolean {
        val ws = socket ?: return false
        return try {
            ws.send(api.json.encodeToString(Envelope.serializer(), envelope))
        } catch (t: Throwable) {
            false
        }
    }

    private fun dispatch(text: String) {
        val envelope = runCatching { api.json.decodeFromString(Envelope.serializer(), text) }.getOrNull() ?: return
        when (envelope.t) {
            RelayType.WELCOME, RelayType.PRESENCE -> _peerOnline.value = envelope.peerOnline

            RelayType.REQUEST -> scope.launch { serve(envelope) }

            RelayType.ACK -> gates[envelope.id]?.onAck(envelope.seq)

            RelayType.CHUNK -> sinks[envelope.id]?.onChunk(envelope)

            RelayType.DONE -> sinks[envelope.id]?.onDone()

            RelayType.RESPONSE -> {
                val sink = sinks[envelope.id]
                if (sink != null) sink.onResponse(envelope)
                else pending.remove(envelope.id)?.complete(envelope)
            }

            RelayType.ERROR -> {
                val sink = sinks[envelope.id]
                if (sink != null) sink.onResponse(envelope)
                else pending.remove(envelope.id)?.complete(envelope)
            }
        }
    }

    private fun failEverything(cause: Throwable) {
        pending.values.forEach { it.completeExceptionally(cause) }
        pending.clear()
        sinks.values.forEach { it.abort(cause) }
        sinks.clear()
        gates.clear()
        _peerOnline.value = false
    }

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(20)

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (stopped) {
                // The session ended while the handshake was in flight.
                webSocket.close(1000, "session closed")
                return
            }
            socket = webSocket
            attempts = 0
            _connected.value = true
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            dispatch(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleDrop(webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            handleDrop(webSocket)
        }

        private fun handleDrop(ws: WebSocket) {
            if (socket === ws) {
                socket = null
                _connected.value = false
                failEverything(IOException("Connection to the other device closed"))
                scheduleReconnect()
            }
        }
    }

    private companion object {
        const val CHUNK_BYTES = 64 * 1024
        const val WINDOW_CHUNKS = 48
        const val ACK_EVERY = 16
        const val MAX_LIST_ENTRIES = 1200
        const val THUMB_PX = 320
        const val MAX_TRANSFER_BYTES = 2L * 1024 * 1024 * 1024
    }
}
