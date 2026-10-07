package com.layerbit.core.webrtc

import android.content.Context
import org.webrtc.PeerConnectionFactory

/**
 * One-time process-wide WebRTC native initialization.
 *
 * There are now two places that build a [PeerConnectionFactory] - the live session and
 * [RelayProbe] - and `PeerConnectionFactory.initialize` loads native libraries and installs
 * process-global state, so it belongs to neither of them. Guarded rather than merely
 * idempotent, because the probe can run while a session is being started.
 */
object WebRtcInitializer {

    @Volatile
    private var initialized = false

    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .createInitializationOptions()
            )
            initialized = true
        }
    }
}
