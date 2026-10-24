package com.ayush.apnafiles.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ayush.apnafiles.ApnaFilesApp
import com.ayush.apnafiles.data.QrInvite
import com.ayush.apnafiles.net.ApiException
import com.ayush.apnafiles.net.InviteResult
import com.ayush.apnafiles.net.PairRequestDto
import com.ayush.apnafiles.net.PeerDto
import com.ayush.apnafiles.net.SessionDto
import com.ayush.apnafiles.service.RelayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ApnaViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ApnaFilesApp

    val identity = app.identity
    val prefs = app.prefs

    private val _session = MutableStateFlow(SessionDto(address = identity.address, name = prefs.deviceName.value))
    private val _error = MutableStateFlow<String?>(null)
    private val _message = MutableStateFlow<String?>(null)
    private val _busy = MutableStateFlow(false)
    private val _invite = MutableStateFlow<InviteResult?>(null)
    private val _relayStatus = MutableStateFlow<String?>(null)
    private val _cacheSize = MutableStateFlow(0L)

    val error: StateFlow<String?> = _error.asStateFlow()
    val message: StateFlow<String?> = _message.asStateFlow()
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    val invite: StateFlow<InviteResult?> = _invite.asStateFlow()
    val relayStatus: StateFlow<String?> = _relayStatus.asStateFlow()
    val cacheSize: StateFlow<Long> = _cacheSize.asStateFlow()

    val relayUrl: StateFlow<String> = prefs.relayUrl
    val deviceName: StateFlow<String> = prefs.deviceName
    val dynamicColor: StateFlow<Boolean> = prefs.dynamicColor
    val sharePhotos: StateFlow<Boolean> = prefs.sharePhotos
    val shareVideos: StateFlow<Boolean> = prefs.shareVideos
    val shareAudio: StateFlow<Boolean> = prefs.shareAudio
    val shareFolder: StateFlow<Boolean> = prefs.shareFolder

    val configured: StateFlow<Boolean> = prefs.relayUrl
        .map { it.isNotBlank() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.relayUrl.value.isNotBlank())

    val peers: StateFlow<List<PeerDto>> = combine(_session, app.sessions.live) { session, live ->
        session.peers.map { peer -> peer.copy(online = live[peer.pairId] == true) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val incoming: StateFlow<List<PairRequestDto>> = _session
        .map { it.incoming }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val outgoing: StateFlow<List<PairRequestDto>> = _session
        .map { it.outgoing }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var pollJob: Job? = null
    private var registeredOnce = false
    private var lastPeerCount = 0

    // ------------------------------------------------------------ lifecycle

    fun onForeground() {
        refreshCacheSize()
        if (!app.api.isConfigured()) {
            _error.value = "Add your Cloudflare relay URL in Settings first."
            return
        }
        ensureSessionService()
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                refreshNow(silent = true)
                delay(if (_invite.value != null) 3_000 else 6_000)
            }
        }
    }

    private fun ensureSessionService() {
        if (app.sessions.peers.value.isNotEmpty() || _session.value.peers.isNotEmpty()) {
            RelayService.start(app)
        }
    }

    fun onBackground() {
        pollJob?.cancel()
        pollJob = null
    }

    // ------------------------------------------------------------ data

    suspend fun refreshNow(silent: Boolean) {
        if (!app.api.isConfigured()) return
        try {
            val dto = if (!registeredOnce) app.api.register() else app.api.peers()
            registeredOnce = true
            // A fresh pairing makes the current QR invite stale; drop it so the pairing
            // screen hands out a new code instead of showing a used one.
            if (dto.peers.size > lastPeerCount) _invite.value = null
            lastPeerCount = dto.peers.size
            _session.value = dto
            app.sessions.applyPeers(dto.peers)
            _error.value = null
            if (dto.peers.isNotEmpty()) RelayService.start(app)
        } catch (e: Exception) {
            if (!silent) _error.value = e.message ?: "Could not reach the relay"
        }
    }

    fun refresh() {
        viewModelScope.launch { refreshNow(silent = false) }
    }

    // ------------------------------------------------------------ pairing

    fun createInvite() {
        if (!app.api.isConfigured()) {
            _error.value = "Add your Cloudflare relay URL in Settings first."
            return
        }
        viewModelScope.launch {
            try {
                _invite.value = app.api.createInvite()
            } catch (e: Exception) {
                _invite.value = null
                _error.value = e.message ?: "Could not create an invite"
            }
        }
    }

    fun clearInvite() {
        _invite.value = null
    }

    fun sendPairRequest(address: String) {
        val clean = address.trim().lowercase()
        if (!Regex("^0x[0-9a-f]{40}$").matches(clean)) {
            _error.value = "Device IDs look like 0x followed by 40 hex characters."
            return
        }
        if (clean == identity.address.lowercase()) {
            _error.value = "That is this device's own ID."
            return
        }
        viewModelScope.launch {
            _busy.value = true
            try {
                app.api.requestPair(clean)
                registeredOnce = false
                refreshNow(silent = true)
                _message.value = "Request sent. Approve it on the other device."
            } catch (e: Exception) {
                _error.value = e.message ?: "Could not send the request"
            } finally {
                _busy.value = false
            }
        }
    }

    fun claimInvite(text: String) {
        val payload = QrInvite.parse(text)
        if (payload == null) {
            _error.value = "That code is not an Apna Files invite."
            return
        }
        viewModelScope.launch {
            _busy.value = true
            try {
                if (payload.relay.isNotBlank() && payload.relay != prefs.relayUrl.value) {
                    prefs.setRelayUrl(payload.relay)
                }
                val result = app.api.claimInvite(payload.token)
                registeredOnce = false
                refreshNow(silent = true)
                RelayService.start(app)
                _message.value = "Paired with ${result.peer?.name?.ifBlank { "the other device" } ?: "the other device"}"
            } catch (e: Exception) {
                _error.value = e.message ?: "Could not claim the invite"
            } finally {
                _busy.value = false
            }
        }
    }

    fun respond(requestId: String, accept: Boolean) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val result = app.api.respondPair(requestId, accept)
                registeredOnce = false
                refreshNow(silent = true)
                if (accept) {
                    RelayService.start(app)
                    _message.value = "Paired with ${result.peer?.name?.ifBlank { "the other device" } ?: "the other device"}"
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Could not answer the request"
            } finally {
                _busy.value = false
            }
        }
    }

    fun removePair(pairId: String, displayName: String) {
        viewModelScope.launch {
            try {
                app.api.removePair(pairId)
                registeredOnce = false
                refreshNow(silent = true)
                _message.value = "Unpaired $displayName"
            } catch (e: Exception) {
                _error.value = e.message ?: "Could not unpair"
            }
        }
    }

    // ------------------------------------------------------------ settings

    fun saveRelayUrl(url: String) {
        prefs.setRelayUrl(url)
        registeredOnce = false
        _relayStatus.value = null
        if (url.isNotBlank()) {
            checkRelay()
            viewModelScope.launch { refreshNow(silent = false) }
        }
    }

    fun checkRelay() {
        if (!app.api.isConfigured()) {
            _relayStatus.value = "No relay URL set"
            return
        }
        viewModelScope.launch {
            _relayStatus.value = "Checking…"
            _relayStatus.value = try {
                val health = app.api.health()
                val version = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(health)?.groupValues?.get(1)
                if (version != null) "Reachable · relay v$version" else "Reachable"
            } catch (e: ApiException) {
                e.message ?: "Unreachable"
            }
        }
    }

    fun setDeviceName(name: String) {
        prefs.setDeviceName(name)
        registeredOnce = false
    }

    fun setDynamicColor(enabled: Boolean) = prefs.setDynamicColor(enabled)
    fun setSharePhotos(enabled: Boolean) = prefs.setSharePhotos(enabled)
    fun setShareVideos(enabled: Boolean) = prefs.setShareVideos(enabled)
    fun setShareAudio(enabled: Boolean) = prefs.setShareAudio(enabled)
    fun setShareFolder(enabled: Boolean) = prefs.setShareFolder(enabled)

    fun refreshCacheSize() {
        viewModelScope.launch {
            _cacheSize.value = withContext(Dispatchers.IO) { app.cache.totalBytes() }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { app.cache.purgeAll() }
            _cacheSize.value = 0L
            _message.value = "Transient cache cleared"
        }
    }

    fun importDocuments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _busy.value = true
            val imported = withContext(Dispatchers.IO) {
                uris.count { uri ->
                    val name = displayName(uri)
                    app.repo.importIntoShared(uri, name) != null
                }
            }
            _busy.value = false
            _message.value = if (imported > 0) {
                "Added $imported file(s) to the shared folder"
            } else {
                "Nothing was imported"
            }
        }
    }

    private fun displayName(uri: Uri): String {
        return try {
            getApplication<Application>().contentResolver.query(uri, null, null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                }
        } catch (_: Exception) {
            null
        } ?: "file-${System.currentTimeMillis()}"
    }

    fun consumeError() {
        _error.value = null
    }

    fun consumeMessage() {
        _message.value = null
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer { ApnaViewModel(application) }
        }
    }
}
