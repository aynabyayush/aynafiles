package com.ayush.apnafiles.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.ayush.apnafiles.ApnaFilesApp
import com.ayush.apnafiles.MainActivity
import com.ayush.apnafiles.R

/**
 * Keeps the process (and therefore every relay socket) alive while Apna Files is in
 * use, and tears the whole session down once the app has been left for a while:
 * relays are closed and transient caches are purged.
 */
class RelayService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val stopSelfTask = Runnable { stopSelf() }
    private lateinit var app: ApnaFilesApp

    private val observer = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            handler.removeCallbacks(stopSelfTask)
        }

        override fun onStop(owner: LifecycleOwner) {
            handler.postDelayed(stopSelfTask, BACKGROUND_GRACE_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        app = application as ApnaFilesApp
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )
        ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.removeCallbacks(stopSelfTask)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(stopSelfTask)
        ProcessLifecycleOwner.get().lifecycle.removeObserver(observer)
        app.sessions.stopAll()
        app.cache.purgeThumbs()
        // Keep files that were opened in the last few minutes: an external viewer may
        // still be reading them. Everything older is gone immediately.
        app.cache.purgeOpened(VIEWER_GRACE_MS)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.relay_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.relay_channel_description)
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_files)
            .setContentTitle("Apna Files")
            .setContentText("Your paired devices can reach this phone while the app is open.")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "relay_session"
        private const val NOTIFICATION_ID = 4711
        private const val BACKGROUND_GRACE_MS = 15_000L
        private const val VIEWER_GRACE_MS = 10 * 60 * 1000L

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, RelayService::class.java))
            } catch (_: Throwable) {
                // Background-start restrictions: the relays still work while foregrounded.
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, RelayService::class.java))
            } catch (_: Throwable) {
                // Nothing to stop.
            }
        }
    }
}
