package com.ayush.apnafiles.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ayush.apnafiles.ui.ApnaViewModel
import com.ayush.apnafiles.ui.components.MonoText
import com.ayush.apnafiles.ui.components.OutlinedSurface
import com.ayush.apnafiles.ui.components.SectionLabel
import com.ayush.apnafiles.ui.components.formatBytes
import com.ayush.apnafiles.ui.components.mediaPermissions
import com.ayush.apnafiles.ui.components.rememberMediaAccess

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ApnaViewModel, onBack: () -> Unit) {
    val relay by vm.relayUrl.collectAsStateWithLifecycle()
    val relayStatus by vm.relayStatus.collectAsStateWithLifecycle()
    val deviceName by vm.deviceName.collectAsStateWithLifecycle()
    val dynamicColor by vm.dynamicColor.collectAsStateWithLifecycle()
    val sharePhotos by vm.sharePhotos.collectAsStateWithLifecycle()
    val shareVideos by vm.shareVideos.collectAsStateWithLifecycle()
    val shareAudio by vm.shareAudio.collectAsStateWithLifecycle()
    val shareFolder by vm.shareFolder.collectAsStateWithLifecycle()
    val cacheSize by vm.cacheSize.collectAsStateWithLifecycle()
    val hasMedia = rememberMediaAccess()

    var relayDraft by rememberSaveable(relay) { mutableStateOf(relay) }
    var nameDraft by rememberSaveable(deviceName) { mutableStateOf(deviceName) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> vm.importDocuments(uris) }

    LaunchedEffect(Unit) { vm.refreshCacheSize() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionLabel("Relay")
            OutlinedSurface {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = relayDraft,
                        onValueChange = { relayDraft = it },
                        label = { Text("Worker URL") },
                        placeholder = { Text("https://apnafiles-relay.you.workers.dev") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.saveRelayUrl(relayDraft) },
                            contentPadding = PaddingValues(horizontal = 18.dp)
                        ) { Text("Save") }
                        TextButton(onClick = { vm.checkRelay() }) { Text("Test connection") }
                    }
                    val status = relayStatus
                    if (status != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            SectionLabel("This device")
            OutlinedSurface {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it },
                        label = { Text("Device name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = { vm.setDeviceName(nameDraft) },
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) { Text("Save name") }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "IDENTITY",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    MonoText(vm.identity.address, maxLines = 2)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "A secp256k1 key pair generated on this phone. The ID is the Ethereum-style address of its public key; every request to your relay is signed with it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SectionLabel("What this device offers")
            OutlinedSurface {
                Column(Modifier.padding(vertical = 6.dp)) {
                    SwitchRow("Photos", sharePhotos) { vm.setSharePhotos(it) }
                    SwitchRow("Videos", shareVideos) { vm.setShareVideos(it) }
                    SwitchRow("Audio", shareAudio) { vm.setShareAudio(it) }
                    SwitchRow("Shared folder", shareFolder) { vm.setShareFolder(it) }
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        TextButton(
                            onClick = { importLauncher.launch(arrayOf("*/*")) },
                            contentPadding = PaddingValues(horizontal = 0.dp)
                        ) { Text("Import files into the shared folder") }
                        Text(
                            "Documents and archives go here. They live in Android/data/com.ayush.apnafiles/files/Shared on this phone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            SectionLabel("Permissions")
            OutlinedSurface {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (hasMedia) "Media access granted" else "Media access not granted",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            if (hasMedia) "Photos, videos and audio can be offered to paired devices."
                            else "Without this, only imported files can be shared.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!hasMedia) {
                        Button(
                            onClick = { permissionLauncher.launch(mediaPermissions()) },
                            contentPadding = PaddingValues(horizontal = 16.dp)
                        ) { Text("Grant") }
                    }
                }
            }

            SectionLabel("Appearance")
            OutlinedSurface {
                SwitchRow("Match system colours", dynamicColor) { vm.setDynamicColor(it) }
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        "Off by default so Apna Files keeps its own palette. Turn on to follow Material You on Android 12+.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SectionLabel("Storage")
            OutlinedSurface {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Transient cache", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${formatBytes(cacheSize)} · previews and opened files",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { vm.clearCache() }) { Text("Clear now") }
                }
            }

            SectionLabel("How it works")
            Text(
                "Apna Files keeps a WebSocket to your Cloudflare relay for each paired device. " +
                    "Your relay sees file bytes in transit but never writes them to storage. " +
                    "Previews are cached while you browse and opened files are cached only long enough " +
                    "for the viewer app to read them; both are purged when the session ends.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Permanent copies only exist when you choose “Save to device”.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
