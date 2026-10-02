package com.layerbit.layerlink.service

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.layerbit.core.overlay.FloatingStopController
import com.layerbit.core.util.getParcelableExtraCompat
import com.layerbit.core.webrtc.CloseReason
import com.layerbit.core.webrtc.QualityProfile
import com.layerbit.core.webrtc.ScreenShareHostSession
import com.layerbit.core.webrtc.SessionState
import com.layerbit.layerlink.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer

/**
 * Owns the whole broadcast lifecycle: it must be started in the foreground (with the
 * `mediaProjection` type) before touching [android.media.projection.MediaProjectionManager],
 * per Android 10+/14+ requirements, and keeps running independently of [MainActivity] so the
 * share survives the host app going to the background - the activity only binds to it to
 * mirror state and render a local preview.
 */
class ScreenShareService : LifecycleService() {

    private val binder = LocalBinder()
    private var hostSession: ScreenShareHostSession? = null
    private var floatingStopController: FloatingStopController? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    val eglBaseContext: EglBase.Context?
        get() = hostSession?.eglBaseContext

    /** Quality of the running session, for the activity's session facts. Null when idle. */
    val currentQuality: QualityProfile?
        get() = hostSession?.quality

    inner class LocalBinder : Binder() {
        val service: ScreenShareService get() = this@ScreenShareService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = intent.getParcelableExtraCompat<Intent>(EXTRA_RESULT_DATA)
                val qualityProfile = intent.getStringExtra(EXTRA_QUALITY_PROFILE)
                    ?.let { runCatching { QualityProfile.valueOf(it) }.getOrNull() }
                    ?: QualityProfile.HIGH
                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    startForegroundNotification()
                    startSession(resultData, qualityProfile)
                } else {
                    stopSelf()
                }
            }
            ACTION_STOP -> stopSession()
        }
        return START_NOT_STICKY
    }

    fun attachRenderer(renderer: SurfaceViewRenderer) {
        hostSession?.localVideoTrack?.addSink(renderer)
    }

    fun detachRenderer(renderer: SurfaceViewRenderer) {
        hostSession?.localVideoTrack?.removeSink(renderer)
    }

    fun stopSharing() {
        stopSession()
    }

    private fun startSession(resultData: Intent, qualityProfile: QualityProfile) {
        if (hostSession != null) return
        lateinit var session: ScreenShareHostSession
        session = ScreenShareHostSession(
            context = applicationContext,
            mediaProjectionResultData = resultData,
            scope = lifecycleScope,
            qualityProfile = qualityProfile,
            listener = object : ScreenShareHostSession.Listener {
                // Connection-state changes arrive on WebRTC's signaling thread, but tearing down
                // (the floating Stop overlay especially) must happen on the main thread.
                override fun onStateChanged(newState: SessionState) {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        handle(newState)
                    } else {
                        mainHandler.post { handle(newState) }
                    }
                }

                private fun handle(newState: SessionState) {
                    // A late event from a session that has already been stopped or replaced
                    // must not overwrite the current state.
                    if (hostSession !== session) return
                    when {
                        // Capture stopped from Android's own UI: nothing went wrong, so the
                        // screen simply returns to Ready.
                        newState is SessionState.Closed && newState.reason == CloseReason.CAPTURE_STOPPED ->
                            stopSession()
                        // A session that ended or failed tears down *and keeps* that outcome as
                        // the final state. Previously stopSession() always reset to Idle right
                        // after, so the activity never got to show "Couldn't connect" at all -
                        // and an Error never tore down at all, leaving the dead session in
                        // place so startSession()'s guard silently blocked the next attempt.
                        newState is SessionState.Closed || newState is SessionState.Error ->
                            stopSession(finalState = newState)
                        else -> _state.value = newState
                    }
                }
            }
        )
        // Assigned before start(), not after: start() can fail synchronously and report Error,
        // and stopSession() has to find this session to tear it down.
        hostSession = session
        session.start()
        if (hostSession === session) {
            showFloatingStopControlIfPermitted()
        }
    }

    private fun showFloatingStopControlIfPermitted() {
        if (!Settings.canDrawOverlays(this)) return
        if (floatingStopController != null) return
        floatingStopController = FloatingStopController(this) { stopSession() }.also { it.show() }
    }

    private fun stopSession(finalState: SessionState = SessionState.Idle) {
        // Clear the reference before closing it, not after: close() is defensive about its own
        // native teardown calls, but if anything upstream of it ever threw, hostSession would be
        // left permanently non-null, and startSession()'s "already running" guard would silently
        // block every future broadcast attempt until the process was fully killed.
        val session = hostSession
        hostSession = null
        floatingStopController?.hide()
        floatingStopController = null
        _state.value = finalState
        session?.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        val session = hostSession
        hostSession = null
        floatingStopController?.hide()
        floatingStopController = null
        session?.close()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.layerbit.layerlink.action.START"
        const val ACTION_STOP = "com.layerbit.layerlink.action.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_QUALITY_PROFILE = "extra_quality_profile"

        private const val CHANNEL_ID = "layerlink_broadcast_channel"
        private const val NOTIFICATION_ID = 42
    }
}
