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
    /**
     * [everConnected] distinguishes two situations that otherwise looked identical: a viewer
     * who really did connect and then left ("Viewer Disconnected" is accurate), versus a
     * session that never reached [Live] at all - almost always a NAT/firewall combination
     * neither STUN nor the free TURN relay could get through, which is exactly what makes
     * some cross-country pairings fail while same-network testing looks fine. Telling the
     * user the latter is a network issue worth retrying, rather than implying someone
     * connected and left, is the whole point of carrying this flag through.
     */
    data class Closed(val viewerUrl: String, val sessionId: String, val everConnected: Boolean) : SessionState()
    data class Error(val message: String) : SessionState()
}
