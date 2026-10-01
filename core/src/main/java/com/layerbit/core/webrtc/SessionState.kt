package com.layerbit.core.webrtc

/** Mirrors the status states the web sharer page shows (`setStatus('waiting'|'live'|'closed')`). */
sealed class SessionState {
    data object Idle : SessionState()
    data object Requesting : SessionState()
    data class Waiting(val viewerUrl: String, val sessionId: String) : SessionState()
    /**
     * Negotiation is underway but hasn't reached [Live] yet - either an answer just arrived and
     * ICE is still connecting for the first time ([connectedAtMillis] is null), or a
     * previously-[Live] connection just dropped and might recover on its own (a brief Wi-Fi
     * blip; [connectedAtMillis] is when it first went live, so an on-air clock keeps running
     * through the blip). [secondsRemaining] counts down to 0; reaching [Live] before then
     * cancels it silently, reaching 0 first means [Closed].
     */
    data class Reconnecting(
        val viewerUrl: String,
        val sessionId: String,
        val secondsRemaining: Int,
        val connectedAtMillis: Long?
    ) : SessionState()

    /** [connectedAtMillis] is an `SystemClock.elapsedRealtime()` timestamp of the first connection. */
    data class Live(val viewerUrl: String, val sessionId: String, val connectedAtMillis: Long) : SessionState()

    /**
     * [reason] distinguishes situations that otherwise looked identical: a viewer who really did
     * connect and then left, versus a session that never reached [Live] at all - almost always
     * a NAT/firewall combination neither STUN nor the free TURN relay could get through, which
     * is exactly what makes some cross-country pairings fail while same-network testing looks
     * fine - versus the user stopping capture from Android's own system UI.
     * [liveDurationMillis] is how long the session was on air (0 if it never connected).
     */
    data class Closed(
        val viewerUrl: String,
        val sessionId: String,
        val reason: CloseReason,
        val liveDurationMillis: Long
    ) : SessionState()

    data class Error(val message: String) : SessionState()
}

enum class CloseReason {
    /** Was live; the viewer left or the connection dropped and didn't recover. */
    CONNECTION_ENDED,

    /** Never reached Live - the connection never got through. */
    NEVER_CONNECTED,

    /** The user (or Android) stopped screen capture from the system UI. */
    CAPTURE_STOPPED
}
