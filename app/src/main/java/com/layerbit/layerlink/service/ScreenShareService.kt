package com.layerbit.layerlink.service

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.layerbit.core.overlay.FloatingStopController
import com.layerbit.core.util.getParcelableExtraCompat
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

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    val eglBaseContext: EglBase.Context?
        get() = hostSession?.eglBaseContext

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
                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    startForegroundNotification()
                    startSession(resultData)
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

    private fun startSession(resultData: Intent) {
        if (hostSession != null) return
        hostSession = ScreenShareHostSession(
            context = applicationContext,
            mediaProjectionResultData = resultData,
            scope = lifecycleScope,
            listener = object : ScreenShareHostSession.Listener {
                override fun onStateChanged(newState: SessionState) {
                    _state.value = newState
                    if (newState is SessionState.Closed) {
                        stopSession()
                    }
                }
            }
        ).also { it.start() }
        showFloatingStopControlIfPermitted()
    }

    private fun showFloatingStopControlIfPermitted() {
        if (!Settings.canDrawOverlays(this)) return
        if (floatingStopController != null) return
        floatingStopController = FloatingStopController(this) { stopSession() }.also { it.show() }
    }

    private fun stopSession() {
        hostSession?.close()
        hostSession = null
        floatingStopController?.hide()
        floatingStopController = null
        _state.value = SessionState.Idle
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_broadcast)
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
        hostSession?.close()
        hostSession = null
        floatingStopController?.hide()
        floatingStopController = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.layerbit.layerlink.action.START"
        const val ACTION_STOP = "com.layerbit.layerlink.action.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val CHANNEL_ID = "layerlink_broadcast_channel"
        private const val NOTIFICATION_ID = 42
    }
}
