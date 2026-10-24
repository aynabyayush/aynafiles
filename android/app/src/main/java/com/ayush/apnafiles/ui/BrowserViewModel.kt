package com.ayush.apnafiles.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ayush.apnafiles.ApnaFilesApp
import com.ayush.apnafiles.net.PeerDto
import com.ayush.apnafiles.net.RelayClient
import com.ayush.apnafiles.net.RemoteFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

data class TransferState(
    val name: String,
    val received: Long,
    val total: Long,
    val label: String
) {
    val fraction: Float
        get() = if (total > 0) (received.toFloat() / total).coerceIn(0f, 1f) else 0f
}

sealed interface BrowserEvent {
    data class OpenFile(val file: File, val mime: String) : BrowserEvent
    data class Notify(val text: String) : BrowserEvent
}

enum class FileFilter(val label: String) {
    ALL("All"),
    IMAGES("Photos"),
    VIDEOS("Videos"),
    AUDIO("Audio"),
    FILES("Files");

    fun accepts(file: RemoteFile): Boolean = when (this) {
        ALL -> true
        IMAGES -> file.kind == "image"
        VIDEOS -> file.kind == "video"
        AUDIO -> file.kind == "audio"
        FILES -> file.kind == "file"
    }
}

class BrowserViewModel(application: Application, val pairId: String) : AndroidViewModel(application) {

    private val app = application as ApnaFilesApp

    private val _files = MutableStateFlow<List<RemoteFile>>(emptyList())
    val files: StateFlow<List<RemoteFile>> = _files.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _refreshed = MutableStateFlow(false)
    val refreshed: StateFlow<Boolean> = _refreshed.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _hint = MutableStateFlow<String?>(null)
    val hint: StateFlow<String?> = _hint.asStateFlow()

    private val _transfer = MutableStateFlow<TransferState?>(null)
    val transfer: StateFlow<TransferState?> = _transfer.asStateFlow()

    private val _bulk = MutableStateFlow<String?>(null)
    val bulk: StateFlow<String?> = _bulk.asStateFlow()

    private val _filter = MutableStateFlow(FileFilter.ALL)
    val filter: StateFlow<FileFilter> = _filter.asStateFlow()

    /** fileId -> decoded preview; a null value means "no preview available". */
    val thumbs = mutableStateMapOf<String, Bitmap?>()
    private val requested = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private val gates = Semaphore(4)

    private val events = Channel<BrowserEvent>(Channel.BUFFERED)
    val eventStream = events.receiveAsFlow()

    val peer: StateFlow<PeerDto?> = app.sessions.peers
        .map { list -> list.firstOrNull { it.pairId == pairId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, app.sessions.peer(pairId))

    val online: StateFlow<Boolean> = app.sessions.live
        .map { it[pairId] == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, app.sessions.isPeerOnline(pairId))

    private fun client(): RelayClient? = app.sessions.client(pairId)

    fun setFilter(value: FileFilter) {
        _filter.value = value
    }

    fun visibleFiles(): List<RemoteFile> = _files.value.filter { _filter.value.accepts(it) }

    fun refresh() {
        viewModelScope.launch {
            val relay = client()
            if (relay == null) {
                _error.value = "This device is not connected. Reopen the app on both phones."
                return@launch
            }
            _loading.value = true
            try {
                val listing = relay.listFiles()
                _files.value = listing.files
                _hint.value = when (listing.code) {
                    "no_media_permission" ->
                        "The other device has not granted access to its photos or media."
                    "truncated" -> "Showing the most recent 1,200 files."
                    else -> null
                }
                _error.value = null
                _refreshed.value = true
            } catch (e: Exception) {
                _error.value = e.message ?: "Could not load the file list"
            } finally {
                _loading.value = false
            }
        }
    }

    /** Called by each grid tile as it scrolls into view. */
    fun ensureThumbnail(file: RemoteFile) {
        if (thumbs.containsKey(file.id) || !requested.add(file.id)) return
        viewModelScope.launch {
            gates.withPermit {
                try {
                    val cached = app.cache.thumbFile(file.id)
                    val bytes = if (cached.exists() && cached.length() > 0) {
                        withContext(Dispatchers.IO) { cached.readBytes() }
                    } else {
                        val fetched = client()?.thumbnail(file.id)
                        if (fetched != null) {
                            withContext(Dispatchers.IO) { cached.writeBytes(fetched) }
                        }
                        fetched
                    }
                    val bitmap = bytes?.let { decode(it) }
                    if (thumbs.size > 320) {
                        thumbs.clear()
                        requested.clear()
                    }
                    thumbs[file.id] = bitmap
                } catch (_: Exception) {
                    thumbs[file.id] = null
                }
            }
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 360) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    fun open(file: RemoteFile) {
        if (_transfer.value != null) return
        viewModelScope.launch {
            val relay = client() ?: run {
                _error.value = "Not connected to the other device"
                return@launch
            }
            val target = app.cache.newOpenFile(file.name)
            _transfer.value = TransferState(file.name, 0, file.size, "Downloading")
            try {
                relay.download(file.id, target) { received, total ->
                    _transfer.value = TransferState(file.name, received, total, "Downloading")
                }
                _transfer.value = null
                events.send(BrowserEvent.OpenFile(target, file.mime))
            } catch (e: Exception) {
                target.delete()
                _transfer.value = null
                _error.value = e.message ?: "The file could not be downloaded"
            }
        }
    }

    fun saveToDevice(file: RemoteFile) {
        if (_transfer.value != null) return
        viewModelScope.launch {
            val relay = client() ?: run {
                _error.value = "Not connected to the other device"
                return@launch
            }
            val temp = app.cache.newOpenFile(file.name)
            _transfer.value = TransferState(file.name, 0, file.size, "Saving")
            try {
                relay.download(file.id, temp) { received, total ->
                    _transfer.value = TransferState(file.name, received, total, "Saving")
                }
                app.saver.save(file.name, file.mime) { sink ->
                    temp.inputStream().use { source -> source.copyTo(sink) }
                }
                _transfer.value = null
                events.send(BrowserEvent.Notify("Saved to Downloads/Apna Files"))
            } catch (e: Exception) {
                _error.value = e.message ?: "The file could not be saved"
                _transfer.value = null
            } finally {
                temp.delete()
            }
        }
    }

    fun saveAll(files: List<RemoteFile>) {
        if (files.isEmpty() || _bulk.value != null || _transfer.value != null) return
        viewModelScope.launch {
            val relay = client() ?: run {
                _error.value = "Not connected to the other device"
                return@launch
            }
            var saved = 0
            for ((index, file) in files.withIndex()) {
                _bulk.value = "Saving ${index + 1} of ${files.size}"
                val temp = app.cache.newOpenFile(file.name)
                try {
                    relay.download(file.id, temp) { received, total ->
                        val percent = if (total > 0) (received * 100 / total).toInt() else 0
                        _bulk.value = "Saving ${index + 1} of ${files.size} · ${percent}%"
                    }
                    app.saver.save(file.name, file.mime) { sink ->
                        temp.inputStream().use { source -> source.copyTo(sink) }
                    }
                    saved++
                } catch (e: Exception) {
                    _error.value = e.message ?: "Stopped while saving ${file.name}"
                    break
                } finally {
                    temp.delete()
                }
            }
            _bulk.value = null
            events.send(BrowserEvent.Notify("Saved $saved of ${files.size} files to Downloads/Apna Files"))
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { app.cache.purgeAll() }
            thumbs.clear()
            requested.clear()
            events.send(BrowserEvent.Notify("Transient cache cleared"))
        }
    }

    override fun onCleared() {
        // The relay connection belongs to the app session, not this screen; nothing to
        // close here. Preview bitmaps are dropped with the view model.
        thumbs.clear()
        super.onCleared()
    }

    companion object {
        fun factory(application: Application, pairId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { BrowserViewModel(application, pairId) }
        }
    }
}
