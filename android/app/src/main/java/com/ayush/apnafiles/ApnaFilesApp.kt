package com.ayush.apnafiles

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.ayush.apnafiles.core.Identity
import com.ayush.apnafiles.core.IdentityVault
import com.ayush.apnafiles.data.Prefs
import com.ayush.apnafiles.files.CacheStore
import com.ayush.apnafiles.files.MediaRepo
import com.ayush.apnafiles.files.Saver
import com.ayush.apnafiles.net.Api
import com.ayush.apnafiles.service.RelayService
import com.ayush.apnafiles.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual service locator. Single-process app, so plain singletons are enough and
 * keep the wiring obvious.
 */
class ApnaFilesApp : Application() {

    lateinit var prefs: Prefs
        private set
    lateinit var identity: Identity
        private set
    lateinit var api: Api
        private set
    lateinit var repo: MediaRepo
        private set
    lateinit var cache: CacheStore
        private set
    lateinit var saver: Saver
        private set
    lateinit var sessions: SessionManager
        private set

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Ends the transfer session: stop the foreground service and, independently of it,
     * drop every relay and purge transient data. Doing the teardown here as well keeps
     * the guarantee even if the system refused to start the service.
     */
    private val endSession = Runnable {
        RelayService.stop(this)
        sessions.stopAll()
        cache.purgeThumbs()
        cache.purgeOpened(VIEWER_GRACE_MS)
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        identity = IdentityVault.loadOrCreate(this)
        api = Api(identity, prefs)
        repo = MediaRepo(this, prefs)
        cache = CacheStore(this)
        saver = Saver(this)
        sessions = SessionManager(api, repo, appScope)

        // A cold start means the previous session is over: nothing transient survives.
        cache.purgeAll()

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                handler.removeCallbacks(endSession)
            }

            override fun onStop(owner: LifecycleOwner) {
                // Give an external viewer a moment to pick up the file it was handed,
                // then tear the transfer session down and clear transient data.
                handler.postDelayed(endSession, SESSION_GRACE_MS)
            }
        })
    }

    private companion object {
        const val SESSION_GRACE_MS = 15_000L
        const val VIEWER_GRACE_MS = 10 * 60 * 1000L
    }
}
