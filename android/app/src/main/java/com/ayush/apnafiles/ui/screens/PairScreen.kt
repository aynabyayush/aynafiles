package com.ayush.apnafiles.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ayush.apnafiles.data.QrInvite
import com.ayush.apnafiles.ui.ApnaViewModel
import com.ayush.apnafiles.ui.components.MonoText
import com.ayush.apnafiles.ui.components.OutlinedSurface
import com.ayush.apnafiles.ui.components.QrCodeView
import com.ayush.apnafiles.ui.components.shortAddress
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairScreen(vm: ApnaViewModel, onBack: () -> Unit) {
    val invite by vm.invite.collectAsStateWithLifecycle()
    val outgoing by vm.outgoing.collectAsStateWithLifecycle()
    val incoming by vm.incoming.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val relay by vm.relayUrl.collectAsStateWithLifecycle()
    val configured by vm.configured.collectAsStateWithLifecycle()
    val deviceName by vm.deviceName.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var tab by remember { mutableStateOf(0) }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (!contents.isNullOrBlank()) vm.claimInvite(contents)
    }

    LaunchedEffect(tab, configured, invite) {
        if (tab == 0 && configured && invite == null) vm.createInvite()
    }

    LaunchedEffect(invite?.expiresAt) {
        val expiresAt = invite?.expiresAt ?: return@LaunchedEffect
        val wait = expiresAt * 1000L - System.currentTimeMillis()
        if (wait > 0) delay(wait + 1000)
        vm.createInvite()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Pair a device") },
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
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    label = { Text("QR code") }
                )
                FilterChip(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    label = { Text("Device ID") }
                )
            }

            if (incoming.isNotEmpty()) {
                OutlinedSurface {
                    Column(Modifier.padding(16.dp)) {
                        Text("Requests waiting for you", style = MaterialTheme.typography.titleSmall)
                        incoming.forEach { request ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(request.fromName.ifBlank { "Unknown device" })
                                    MonoText(shortAddress(request.from))
                                }
                                TextButton(onClick = { vm.respond(request.id, false) }) { Text("Decline") }
                                Button(onClick = { vm.respond(request.id, true) }) { Text("Accept") }
                            }
                        }
                    }
                }
            }

            if (!configured) {
                OutlinedSurface {
                    Column(Modifier.padding(16.dp)) {
                        Text("Relay not configured", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Set your Cloudflare Worker URL in Settings before pairing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (tab == 0) {
                QrTab(
                    payload = invite?.let {
                        QrInvite.encode(relay, it.token, vm.identity.address, deviceName)
                    },
                    deviceAddress = vm.identity.address,
                    expiresAtSeconds = invite?.expiresAt ?: 0L,
                    onRefresh = { vm.createInvite() },
                    onScan = {
                        scanner.launch(
                            ScanOptions().apply {
                                setDesiredBarcodeFormats("QR_CODE")
                                setPrompt("Scan the other device's Apna Files code")
                                setBeepEnabled(false)
                                setOrientationLocked(false)
                            }
                        )
                    }
                )
            } else {
                IdTab(
                    busy = busy,
                    outgoing = outgoing,
                    onSend = { vm.sendPairRequest(it) },
                    clipboardText = { pasteFromClipboard(context) }
                )
            }

            Text(
                "Pairing links the two devices by their keys. Anyone with access to your QR code during its lifetime can request a pairing, so only show it to the device in front of you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun QrTab(
    payload: String?,
    deviceAddress: String,
    expiresAtSeconds: Long,
    onRefresh: () -> Unit,
    onScan: () -> Unit
) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(payload) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val remainingMs = (expiresAtSeconds * 1000L - now).coerceAtLeast(0L)
    val minutes = if (remainingMs > 0) (remainingMs / 60_000L) + 1 else 0

    OutlinedSurface {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Scan this with the other device", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .size(248.dp),
                contentAlignment = Alignment.Center
            ) {
                if (payload == null) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                } else {
                    QrCodeView(payload, Modifier.size(248.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            MonoText(shortAddress(deviceAddress))
            Spacer(Modifier.height(6.dp))
            Text(
                if (minutes > 0) "Code expires in about $minutes min" else "Code expired",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRefresh) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("New code")
                }
                Button(onClick = onScan) {
                    Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Scan instead")
                }
            }
        }
    }
}

@Composable
private fun IdTab(
    busy: Boolean,
    outgoing: List<com.ayush.apnafiles.net.PairRequestDto>,
    onSend: (String) -> Unit,
    clipboardText: () -> String?
) {
    var target by rememberSaveable { mutableStateOf("") }

    OutlinedSurface {
        Column(Modifier.padding(18.dp)) {
            Text("Enter the other device's ID", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = target,
                onValueChange = { target = it },
                label = { Text("Device ID") },
                placeholder = { Text("0x…") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { clipboardText()?.let { target = it } }) {
                        Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onSend(target) },
                enabled = target.isNotBlank() && !busy,
                contentPadding = PaddingValues(horizontal = 20.dp)
            ) {
                Text("Send pairing request")
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "The other device has to approve the request before anything is shared.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (outgoing.isNotEmpty()) {
        OutlinedSurface {
            Column(Modifier.padding(18.dp)) {
                Text("Waiting for approval", style = MaterialTheme.typography.titleSmall)
                outgoing.forEach { request ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                request.toName.ifBlank { "Paired device" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            MonoText(shortAddress(request.to))
                        }
                        Text(
                            "Pending",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun pasteFromClipboard(context: Context): String? {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = manager.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}
