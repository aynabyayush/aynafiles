package com.ayush.apnafiles.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ayush.apnafiles.net.PeerDto
import com.ayush.apnafiles.ui.ApnaViewModel
import com.ayush.apnafiles.ui.components.EmptyState
import com.ayush.apnafiles.ui.components.MonoText
import com.ayush.apnafiles.ui.components.OutlinedSurface
import com.ayush.apnafiles.ui.components.SectionLabel
import com.ayush.apnafiles.ui.components.StatusDot
import com.ayush.apnafiles.ui.components.mediaPermissions
import com.ayush.apnafiles.ui.components.rememberMediaAccess
import com.ayush.apnafiles.ui.components.shortAddress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: ApnaViewModel,
    onPair: () -> Unit,
    onOpenPeer: (String) -> Unit,
    onSettings: () -> Unit
) {
    val peers by vm.peers.collectAsStateWithLifecycle()
    val incoming by vm.incoming.collectAsStateWithLifecycle()
    val configured by vm.configured.collectAsStateWithLifecycle()
    val deviceName by vm.deviceName.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val hasMedia = rememberMediaAccess()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Apna Files") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onPair,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Rounded.AddLink, contentDescription = null) },
                text = { Text("Pair a device") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                ThisDeviceCard(
                    name = deviceName,
                    address = vm.identity.address,
                    onCopy = {
                        copyToClipboard(context, vm.identity.address)
                        Toast.makeText(context, "Device ID copied", Toast.LENGTH_SHORT).show()
                    },
                    onShowQr = onPair
                )
            }

            if (!configured) {
                item {
                    NoticeCard(
                        title = "Connect your relay",
                        body = "Add the URL of your Cloudflare Worker so devices can find each other.",
                        actionLabel = "Open settings",
                        onAction = onSettings
                    )
                }
            }

            if (!hasMedia) {
                item {
                    NoticeCard(
                        title = "Allow media access",
                        body = "Without it, this device can only offer files you import into its shared folder.",
                        actionLabel = "Grant access",
                        onAction = { permissionLauncher.launch(mediaPermissions()) }
                    )
                }
            }

            if (incoming.isNotEmpty()) {
                item {
                    OutlinedSurface {
                        Column(Modifier.padding(16.dp)) {
                            Text("Pairing requests", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            incoming.forEach { request ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(top = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            request.fromName.ifBlank { "Unknown device" },
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        MonoText(shortAddress(request.from))
                                    }
                                    TextButton(onClick = { vm.respond(request.id, false) }) {
                                        Text("Decline")
                                    }
                                    Button(
                                        onClick = { vm.respond(request.id, true) },
                                        contentPadding = PaddingValues(horizontal = 14.dp)
                                    ) {
                                        Text("Accept")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item { SectionLabel("Paired devices") }

            if (peers.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.Devices,
                        title = "No devices yet",
                        body = "Pair another phone to browse its photos and files from here — nothing is uploaded or kept on a server."
                    )
                }
            } else {
                items(peers, key = { it.pairId }) { peer ->
                    PeerCard(
                        peer = peer,
                        onOpen = { onOpenPeer(peer.pairId) },
                        onUnpair = { vm.removePair(peer.pairId, peer.name.ifBlank { shortAddress(peer.address) }) }
                    )
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Both devices must have Apna Files open. Files are relayed end to end through your Worker and only exist in a cache while the session lasts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ThisDeviceCard(
    name: String,
    address: String,
    onCopy: () -> Unit,
    onShowQr: () -> Unit
) {
    OutlinedSurface {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(46.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.Smartphone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "THIS DEVICE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    MonoText(shortAddress(address))
                }
                IconButton(onClick = onCopy) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy device ID", modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onShowQr) {
                    Icon(Icons.Rounded.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Show QR code")
                }
            }
        }
    }
}

@Composable
private fun NoticeCard(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    OutlinedSurface {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Outlined.PhotoLibrary,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun PeerCard(peer: PeerDto, onOpen: () -> Unit, onUnpair: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }

    OutlinedSurface(Modifier.clickable(onClick = onOpen)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Smartphone,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    peer.name.ifBlank { "Paired device" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(online = peer.online)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (peer.online) "Reachable" else "Not reachable",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(10.dp))
                    MonoText(shortAddress(peer.address), maxLines = 1)
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Device options")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Unpair") },
                        onClick = {
                            menuOpen = false
                            onUnpair()
                        }
                    )
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("Apna Files device ID", text))
}
