package com.layerbit.core.webrtc

/** Mirrors the status states the web sharer page shows (`setStatus('waiting'|'live'|'closed')`). */
sealed class SessionState {
    data object Idle : SessionState()
    data object Requesting : SessionState()
    data class Waiting(val viewerUrl: String, val sessionId: String) : SessionState()
    /**
     * Negotiation is underway but hasn't reached [Live] yet - either an answer just arrived and
     * ICE is still connecting for the first time, or a previously-[Live] connection just dropped
     * and might recover on its own (a brief Wi-Fi blip). [secondsRemaining] counts down to 0;
     * reaching [Live] before then cancels it silently, reaching 0 first means [Closed].
     */
    data class Reconnecting(val viewerUrl: String, val sessionId: String, val secondsRemaining: Int) : SessionState()
    data class Live(val viewerUrl: String, val sessionId: String) : SessionState()
    data class Closed(val viewerUrl: String, val sessionId: String) : SessionState()
    data class Error(val message: String) : SessionState()
}
