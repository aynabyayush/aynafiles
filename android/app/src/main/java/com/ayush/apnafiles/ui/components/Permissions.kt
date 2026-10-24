package com.ayush.apnafiles.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner

fun mediaPermissions(): Array<String> {
    val list = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        list += Manifest.permission.READ_MEDIA_IMAGES
        list += Manifest.permission.READ_MEDIA_VIDEO
        list += Manifest.permission.READ_MEDIA_AUDIO
        list += Manifest.permission.POST_NOTIFICATIONS
    } else {
        list += Manifest.permission.READ_EXTERNAL_STORAGE
    }
    return list.toTypedArray()
}

fun hasMediaAccess(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val a = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES)
        val b = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO)
        val c = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO)
        a == PackageManager.PERMISSION_GRANTED ||
            b == PackageManager.PERMISSION_GRANTED ||
            c == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
}

/** Re-checks permission state whenever the app comes back to the foreground. */
@Composable
fun rememberMediaAccess(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return remember(tick, context) { hasMediaAccess(context) }
}
