package com.ayush.apnafiles.session

import com.ayush.apnafiles.files.MediaRepo
import com.ayush.apnafiles.net.Api
import com.ayush.apnafiles.net.PeerDto
import com.ayush.apnafiles.net.RelayClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps a relay connection open to every paired device while the app is in use.
 * [stopAll] is called when the transfer session ends (app backgrounded or closed).
 */
class SessionManager(
    private val api: Api,
    private val repo: MediaRepo,
    private val scope: CoroutineScope
) {
    private val clients = ConcurrentHashMap<String, RelayClient>()
    private val watchers = ConcurrentHashMap<String, Job>()

    private val _peers = MutableStateFlow<List<PeerDto>>(emptyList())
    val peers: StateFlow<List<PeerDto>> = _peers.asStateFlow()

    /** pairId -> both sides of the relay are up and the peer is present. */
    private val _live = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val live: StateFlow<Map<String, Boolean>> = _live.asStateFlow()

    fun applyPeers(peers: List<PeerDto>) {
        _peers.value = peers
        val wanted = peers.filter { it.pairId.isNotBlank() }.associateBy { it.pairId }

        clients.keys.toList().filterNot { wanted.containsKey(it) }.forEach { drop(it) }
        wanted.forEach { (pairId, peer) ->
            if (!clients.containsKey(pairId)) open(pairId, peer.address)
        }
    }

    fun client(pairId: String): RelayClient? = clients[pairId]

    fun peer(pairId: String): PeerDto? = _peers.value.firstOrNull { it.pairId == pairId }

    fun isPeerOnline(pairId: String): Boolean = _live.value[pairId] == true

    fun stopAll() {
        clients.keys.toList().forEach { drop(it) }
    }

    private fun open(pairId: String, address: String) {
        val client = RelayClient(pairId, address, api, repo, scope)
        clients[pairId] = client
        client.start()
        watchers[pairId] = scope.launch {
            combine(client.connected, client.peerOnline) { relayUp, peerUp -> relayUp && peerUp }
                .collect { online -> _live.update { it + (pairId to online) } }
        }
    }

    private fun drop(pairId: String) {
        watchers.remove(pairId)?.cancel()
        clients.remove(pairId)?.stop()
        _live.update { it - pairId }
    }
}
