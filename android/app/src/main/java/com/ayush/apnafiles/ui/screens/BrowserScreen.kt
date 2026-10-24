package com.ayush.apnafiles.ui.screens

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ayush.apnafiles.net.RemoteFile
import com.ayush.apnafiles.ui.BrowserEvent
import com.ayush.apnafiles.ui.BrowserViewModel
import com.ayush.apnafiles.ui.FileFilter
import com.ayush.apnafiles.ui.components.EmptyState
import com.ayush.apnafiles.ui.components.MonoText
import com.ayush.apnafiles.ui.components.OutlinedSurface
import com.ayush.apnafiles.ui.components.StatusDot
import com.ayush.apnafiles.ui.components.formatBytes
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(vm: BrowserViewModel, onBack: () -> Unit, onNotify: (String) -> Unit) {
    val files by vm.files.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val refreshed by vm.refreshed.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val hint by vm.hint.collectAsStateWithLifecycle()
    val transfer by vm.transfer.collectAsStateWithLifecycle()
    val bulk by vm.bulk.collectAsStateWithLifecycle()
    val peer by vm.peer.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<RemoteFile?>(null) }

    val visible = remember(files, filter) { files.filter { filter.accepts(it) } }

    LaunchedEffect(Unit) { vm.refresh() }

    LaunchedEffect(online) {
        if (online && !refreshed) vm.refresh()
    }

    LaunchedEffect(Unit) {
        vm.eventStream.collect { event ->
            when (event) {
                is BrowserEvent.OpenFile -> openCachedFile(context, event.file, event.mime, onNotify)
                is BrowserEvent.Notify -> onNotify(event.text)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            peer?.name?.ifBlank { "Paired device" } ?: "Paired device",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(online)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (online) "Reachable" else "Not reachable",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Save all shown files") },
                                enabled = visible.isNotEmpty() && transfer == null && bulk == null,
                                onClick = {
                                    menuOpen = false
                                    vm.saveAll(visible)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Clear transient cache") },
                                onClick = {
                                    menuOpen = false
                                    vm.clearCache()
                                }
                            )
                        }
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
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FileFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { vm.setFilter(option) },
                        label = { Text(option.label) }
                    )
                }
            }

            val currentTransfer = transfer
            if (currentTransfer != null) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${currentTransfer.label} ${currentTransfer.name}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            if (currentTransfer.total > 0) {
                                "${(currentTransfer.fraction * 100).toInt()}%"
                            } else {
                                formatBytes(currentTransfer.received)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    if (currentTransfer.total > 0) {
                        LinearProgressIndicator(
                            progress = { currentTransfer.fraction },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }

            val currentBulk = bulk
            if (currentBulk != null) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(currentBulk, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }

            if (!online) {
                HintCard(
                    title = "Device not reachable",
                    body = "Ask the other person to open Apna Files. Transfers only work while both apps are open.",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            val currentError = error
            if (currentError != null) {
                HintCard(
                    title = "Something went wrong",
                    body = currentError,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    action = {
                        TextButton(onClick = { vm.refresh() }) { Text("Retry") }
                    }
                )
            }

            val currentHint = hint
            if (currentHint != null) {
                HintCard(
                    title = "Heads up",
                    body = currentHint,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            if (loading && files.isEmpty()) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            if (visible.isEmpty() && !loading) {
                EmptyState(
                    icon = Icons.Outlined.FolderOpen,
                    title = if (files.isEmpty()) "Nothing to show yet" else "No ${filter.label.lowercase()} here",
                    body = if (files.isEmpty()) {
                        "Pull to refresh, or make sure the other device has allowed access to its files."
                    } else {
                        "Try another filter to see the rest of the shared files."
                    },
                    modifier = Modifier.fillMaxWidth(),
                    action = {
                        TextButton(onClick = { vm.refresh() }) { Text("Refresh") }
                    }
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(visible, key = { it.id }) { file ->
                        LaunchedEffect(file.id) { vm.ensureThumbnail(file) }
                        FileTile(
                            file = file,
                            thumb = vm.thumbs[file.id],
                            onClick = { vm.open(file) },
                            onLongClick = { selected = file }
                        )
                    }
                }
            }
        }
    }

    val sheetFile = selected
    if (sheetFile != null) {
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Column(Modifier.padding(start = 22.dp, end = 22.dp, bottom = 28.dp)) {
                Text(sheetFile.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(
                    listOfNotNull(
                        formatBytes(sheetFile.size),
                        sheetFile.mime.takeIf { it.isNotBlank() },
                        sheetFile.folder.takeIf { it.isNotBlank() }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                SheetAction(Icons.Outlined.FolderOpen, "Open") {
                    selected = null
                    vm.open(sheetFile)
                }
                SheetAction(Icons.Outlined.Download, "Save to device") {
                    selected = null
                    vm.saveToDevice(sheetFile)
                }
                Spacer(Modifier.height(6.dp))
                MonoText(sheetFile.id, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun HintCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    OutlinedSurface(modifier) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (action != null) action()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTile(
    file: RemoteFile,
    thumb: Bitmap?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            if (thumb != null) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = file.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = iconFor(file.kind),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp)
                )
            }
            if (file.kind == "video") {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .size(20.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.PlayCircle,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            file.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            formatBytes(file.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun iconFor(kind: String) = when (kind) {
    "image" -> Icons.Outlined.Photo
    "video" -> Icons.Outlined.PlayCircle
    "audio" -> Icons.Outlined.MusicNote
    else -> Icons.Outlined.InsertDriveFile
}

private fun openCachedFile(context: Context, file: File, mime: String, onNotify: (String) -> Unit) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime.ifBlank { "application/octet-stream" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("Apna Files", uri)
        }
        val chooser = Intent.createChooser(intent, "Open with")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this file type", Toast.LENGTH_LONG).show()
    } catch (t: Throwable) {
        Toast.makeText(context, t.message ?: "Could not open the file", Toast.LENGTH_LONG).show()
    }
}
