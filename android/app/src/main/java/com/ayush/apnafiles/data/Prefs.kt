package com.ayush.apnafiles.data

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("apna_files_prefs", Context.MODE_PRIVATE)

    private val _relayUrl = MutableStateFlow(sp.getString(KEY_RELAY_URL, "").orEmpty())
    val relayUrl: StateFlow<String> = _relayUrl

    private val _deviceName = MutableStateFlow(
        sp.getString(KEY_DEVICE_NAME, null) ?: (Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android device")
    )
    val deviceName: StateFlow<String> = _deviceName

    private val _dynamicColor = MutableStateFlow(sp.getBoolean(KEY_DYNAMIC_COLOR, false))
    val dynamicColor: StateFlow<Boolean> = _dynamicColor

    private val _sharePhotos = MutableStateFlow(sp.getBoolean(KEY_SHARE_PHOTOS, true))
    val sharePhotos: StateFlow<Boolean> = _sharePhotos

    private val _shareVideos = MutableStateFlow(sp.getBoolean(KEY_SHARE_VIDEOS, true))
    val shareVideos: StateFlow<Boolean> = _shareVideos

    private val _shareAudio = MutableStateFlow(sp.getBoolean(KEY_SHARE_AUDIO, true))
    val shareAudio: StateFlow<Boolean> = _shareAudio

    private val _shareFolder = MutableStateFlow(sp.getBoolean(KEY_SHARE_FOLDER, true))
    val shareFolder: StateFlow<Boolean> = _shareFolder

    fun setRelayUrl(value: String) {
        val clean = value.trim().trimEnd('/')
        _relayUrl.value = clean
        sp.edit().putString(KEY_RELAY_URL, clean).apply()
    }

    fun setDeviceName(value: String) {
        val clean = value.trim().ifBlank { "Android device" }
        _deviceName.value = clean
        sp.edit().putString(KEY_DEVICE_NAME, clean).apply()
    }

    fun setDynamicColor(value: Boolean) {
        _dynamicColor.value = value
        sp.edit().putBoolean(KEY_DYNAMIC_COLOR, value).apply()
    }

    fun setSharePhotos(value: Boolean) {
        _sharePhotos.value = value
        sp.edit().putBoolean(KEY_SHARE_PHOTOS, value).apply()
    }

    fun setShareVideos(value: Boolean) {
        _shareVideos.value = value
        sp.edit().putBoolean(KEY_SHARE_VIDEOS, value).apply()
    }

    fun setShareAudio(value: Boolean) {
        _shareAudio.value = value
        sp.edit().putBoolean(KEY_SHARE_AUDIO, value).apply()
    }

    fun setShareFolder(value: Boolean) {
        _shareFolder.value = value
        sp.edit().putBoolean(KEY_SHARE_FOLDER, value).apply()
    }

    private companion object {
        const val KEY_RELAY_URL = "relay_url"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val KEY_SHARE_PHOTOS = "share_photos"
        const val KEY_SHARE_VIDEOS = "share_videos"
        const val KEY_SHARE_AUDIO = "share_audio"
        const val KEY_SHARE_FOLDER = "share_folder"
    }
}
