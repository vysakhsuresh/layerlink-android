package com.layerbit.core.webrtc

/** Mirrors the status states the web sharer page shows (`setStatus('waiting'|'live'|'closed')`). */
sealed class SessionState {
    data object Idle : SessionState()
    data object Requesting : SessionState()
    data class Waiting(val viewerUrl: String, val sessionId: String) : SessionState()
    data class Live(val viewerUrl: String, val sessionId: String) : SessionState()
    data class Closed(val viewerUrl: String, val sessionId: String) : SessionState()
    data class Error(val message: String) : SessionState()
}
