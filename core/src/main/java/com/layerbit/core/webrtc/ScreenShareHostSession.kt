package com.layerbit.core.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.SystemClock
import com.layerbit.core.signaling.FirebaseSignalingClient
import com.layerbit.core.signaling.RtcJson
import com.layerbit.core.signaling.SignalingSubscription
import com.layerbit.core.util.SessionIdGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.DataChannel

/**
 * Hosts one screen-share session: captures the device screen via MediaProjection, publishes it
 * over a WebRTC [PeerConnection], and negotiates that connection through a Firebase Realtime
 * Database signaling protocol (`sessions/{id}/offer`, `offerCandidates`, `answer`,
 * `answerCandidates`) compatible with a plain web viewer page - no app-specific server needed.
 * Reusable across apps in the same family: [signalingClient] and [viewerBaseUrl] default to
 * LayerLink's own Firebase project and viewer page, but either can be swapped per app. See
 * layerlink-sharer.body.html in the layerbit-site repo for the reference web implementation
 * this class mirrors, including the v1.1.0 fix of queueing remote ICE candidates until the
 * remote description is set.
 */
class ScreenShareHostSession(
    private val context: Context,
    private val mediaProjectionResultData: Intent,
    private val scope: CoroutineScope,
    private val listener: Listener,
    private val signalingClient: FirebaseSignalingClient = FirebaseSignalingClient(),
    private val viewerBaseUrl: String = DEFAULT_VIEWER_BASE_URL,
    private val qualityProfile: QualityProfile = QualityProfile.HIGH,
    // Resolved by the owner (ScreenShareService) through IceConfigStore, not built here, so a
    // relay can be changed on the device or on the website without an app release. The default
    // is the STUN-only floor, which keeps this class usable standalone.
    private val iceConfig: IceConfig = IceConfig.builtIn
) {
    interface Listener {
        fun onStateChanged(state: SessionState)
    }

    val sessionId: String = SessionIdGenerator.generate()
    val viewerUrl: String = "$viewerBaseUrl?session=$sessionId"

    private val eglBase: EglBase = EglBase.create()
    val eglBaseContext: EglBase.Context get() = eglBase.eglBaseContext

    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    var localVideoTrack: VideoTrack? = null
        private set

    private var answerEventSource: SignalingSubscription? = null
    private var answerCandidatesEventSource: SignalingSubscription? = null

    @Volatile private var hasRemoteAnswer = false
    private val answerCandidateQueue = mutableListOf<IceCandidate>()

    // Volatile: WebRTC observer callbacks arrive on its own signaling thread, not main.
    @Volatile
    private var closed = false

    // elapsedRealtime() of the first real CONNECTED transition, never set speculatively. Drives
    // the on-air clock, and distinguishes "the connection ended" from "it never got through"
    // when the session closes - see SessionState.Closed.
    @Volatile
    private var connectedAtMillis: Long? = null

    // Updated from WebRTC's signaling thread as candidates arrive, read when emitting state.
    @Volatile
    private var relayStatus: RelayStatus =
        if (iceConfig.hasRelay) RelayStatus.PENDING else RelayStatus.NOT_CONFIGURED

    val quality: QualityProfile get() = qualityProfile

    /** What this session knows about its relay right now. */
    val relay: RelayStatus get() = relayStatus

    // Every state change goes through here. Once close() has run, nothing more is reported:
    // tearing down capture fires MediaProjection.Callback.onStop(), and without this guard that
    // late callback would overwrite whatever final state the owner had already settled on.
    private fun emit(state: SessionState) {
        if (!closed) listener.onStateChanged(state)
    }

    private fun endedState(reason: CloseReason? = null): SessionState.Closed {
        val connectedAt = connectedAtMillis
        val duration = if (connectedAt != null) SystemClock.elapsedRealtime() - connectedAt else 0L
        val resolved = reason ?: if (connectedAt != null) CloseReason.CONNECTION_ENDED else CloseReason.NEVER_CONNECTED
        return SessionState.Closed(viewerUrl, sessionId, resolved, duration, relayStatus)
    }

    // Covers two distinct not-yet-Live moments with one mechanism: (1) an answer just arrived
    // and ICE is connecting for the first time, (2) a previously-Live connection just dropped
    // and might recover on its own (a brief Wi-Fi blip). Either way: count down, and only give
    // up for real - closing the session - if CONNECTED is never reached before it hits zero.
    private var reconnectJob: Job? = null

    // Gathering runs continually (see createPeerConnection), and under GATHER_CONTINUALLY
    // WebRTC never reports gathering COMPLETE - so the COMPLETE check below can never mark a
    // dead relay UNAVAILABLE on its own, and the share screen would say "checking the relay"
    // for the life of the session. This deadline is what actually reaches a verdict: a relay
    // that hasn't produced a candidate in this long isn't going to.
    private var relayDeadlineJob: Job? = null

    // True between the offer going out and an answer arriving - the only window in which a
    // relay-status change is worth re-reporting, because that is the screen showing the link.
    @Volatile
    private var waitingForViewer = false

    /**
     * Relay status only ever moves forward: once a relay candidate has been allocated, a later
     * "gathering complete" must not walk it back to [RelayStatus.UNAVAILABLE].
     */
    private fun updateRelayStatus(next: RelayStatus) {
        if (relayStatus == next || relayStatus == RelayStatus.AVAILABLE) return
        relayStatus = next
        if (waitingForViewer) emit(SessionState.Waiting(viewerUrl, sessionId, next))
    }

    private fun startReconnectCountdown() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            for (secondsLeft in RECONNECT_GRACE_SECONDS downTo 1) {
                emit(SessionState.Reconnecting(viewerUrl, sessionId, secondsLeft, connectedAtMillis))
                delay(1000)
            }
            emit(endedState())
        }
    }

    /** Must be called only after the owning foreground service has called startForeground(). */
    fun start() {
        emit(SessionState.Requesting)
        try {
            initPeerConnectionFactory()
            startScreenCapture()
            createPeerConnection()
        } catch (e: Exception) {
            emit(SessionState.Error(e.message ?: "Failed to start capture"))
            return
        }

        scope.launch {
            try {
                createAndSendOffer()
                waitingForViewer = true
                if (iceConfig.hasRelay) {
                    relayDeadlineJob = scope.launch {
                        delay(RELAY_DEADLINE_MILLIS)
                        if (relayStatus == RelayStatus.PENDING) updateRelayStatus(RelayStatus.UNAVAILABLE)
                    }
                }
                emit(SessionState.Waiting(viewerUrl, sessionId, relayStatus))
                observeSignaling()
            } catch (e: Exception) {
                emit(SessionState.Error(e.message ?: "Failed to start session"))
            }
        }
    }

    private fun initPeerConnectionFactory() {
        WebRtcInitializer.ensureInitialized(context)
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private fun startScreenCapture() {
        val metrics = context.resources.displayMetrics
        val width: Int
        val height: Int
        val fps: Int
        when (qualityProfile) {
            QualityProfile.HIGH -> {
                width = metrics.widthPixels
                height = metrics.heightPixels
                fps = CAPTURE_FPS
            }
            QualityProfile.DATA_SAVER -> {
                width = roundToEven(metrics.widthPixels / 2)
                height = roundToEven(metrics.heightPixels / 2)
                fps = DATA_SAVER_FPS
            }
        }

        val capturer = ScreenCapturerAndroid(
            mediaProjectionResultData,
            object : MediaProjection.Callback() {
                override fun onStop() {
                    emit(endedState(CloseReason.CAPTURE_STOPPED))
                }
            }
        )

        val source = peerConnectionFactory.createVideoSource(true /* isScreencast */)
        val helper = SurfaceTextureHelper.create("LayerLinkCapture", eglBase.eglBaseContext)
        capturer.initialize(helper, context.applicationContext, source.capturerObserver)
        capturer.startCapture(width, height, fps)

        screenCapturer = capturer
        videoSource = source
        surfaceTextureHelper = helper
        localVideoTrack = peerConnectionFactory.createVideoTrack(VIDEO_TRACK_ID, source)
    }

    // Some video encoders require even capture dimensions; halving an already-even screen
    // dimension stays even in practice, but this guards the rare odd case rather than assuming it.
    private fun roundToEven(value: Int): Int = if (value % 2 == 0) value else value - 1

    private fun createPeerConnection() {
        // STUN alone only resolves each side's public address; it cannot establish a path when
        // either peer sits behind a NAT that blocks direct/hole-punched traffic (symmetric NAT,
        // CGNAT on mobile data, restrictive Wi-Fi router ACLs). That combination is common enough
        // that the host and viewer can each report "waiting"/"searching" forever with no error,
        // since ICE just never finds a working candidate pair. Only a TURN relay fixes it - see
        // IceConfig for why that relay is now resolved at runtime instead of hardcoded here, and
        // for the specific way the hardcoded one had stopped working.
        val rtcConfig = PeerConnection.RTCConfiguration(iceConfig.toIceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // Explicit rather than relying on the default: on a network that drops UDP, the
            // TCP and TLS relay transports are the only ones left, and they are worthless if
            // the agent won't gather TCP candidates for them.
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            // A phone roaming from Wi-Fi to mobile data mid-broadcast gets new local addresses.
            // Gathering once, at offer time, means those are never offered and the session dies
            // at the end of the reconnect grace window; gathering continually surfaces them as
            // trickled candidates, which the viewer page already consumes (it listens on
            // offerCandidates' child_added for the life of the session).
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // When every candidate in a pair is relayed, the relay has already proved both legs
            // are reachable, so waiting for a STUN binding response before sending media only
            // adds a round trip to the slowest paths - exactly the cross-continent ones.
            presumeWritableWhenFullyRelayed = true
        }
        android.util.Log.d(
            TAG,
            "ICE config from ${iceConfig.origin}: ${iceConfig.stunUrls.size} STUN, " +
                "${iceConfig.turnServers.sumOf { it.urls.size }} relay transports"
        )

        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                // Logged by type (host/srflx/relay), not just "a candidate arrived" - this is
                // the one line of evidence that actually tells us, after the fact, whether a
                // failed cross-country session had a relay candidate to try at all, versus the
                // TURN server never answering. Without it, "didn't connect" and "the relay was
                // dead" look identical in the logs.
                val type = candidateTypeOf(candidate)
                android.util.Log.d(TAG, "Local ICE candidate: $type (via ${candidate.serverUrl})")
                if (type == "relay") updateRelayStatus(RelayStatus.AVAILABLE)
                signalingClient.pushOfferCandidate(sessionId, RtcJson.iceCandidateToJson(candidate))
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                android.util.Log.d(TAG, "onConnectionChange: $newState")
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        val connectedAt = connectedAtMillis ?: SystemClock.elapsedRealtime().also {
                            connectedAtMillis = it
                        }
                        reconnectJob?.cancel()
                        reconnectJob = null
                        emit(SessionState.Live(viewerUrl, sessionId, connectedAt))
                    }
                    // DISCONNECTED only ever follows an established connection (per the WebRTC
                    // spec), so this is specifically the "was Live, just blipped" case - give it
                    // a grace window instead of killing the session on the first hiccup.
                    PeerConnection.PeerConnectionState.DISCONNECTED -> startReconnectCountdown()
                    // FAILED means the ICE agent itself already gave up after its own internal
                    // retries - no additional grace period on top of that.
                    PeerConnection.PeerConnectionState.FAILED -> {
                        reconnectJob?.cancel()
                        reconnectJob = null
                        emit(endedState())
                    }
                    else -> Unit
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                android.util.Log.d(TAG, "onIceConnectionChange: $state")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                android.util.Log.d(TAG, "onIceGatheringChange: $state")
                // Gathering finished without a relay candidate, even though one was configured:
                // the relay rejected us, is down, or every transport it offers is blocked here.
                // Say so now, while the link is still on screen and unsent, instead of letting
                // the viewer discover it.
                if (state == PeerConnection.IceGatheringState.COMPLETE) {
                    updateRelayStatus(
                        if (iceConfig.hasRelay) RelayStatus.UNAVAILABLE else RelayStatus.NOT_CONFIGURED
                    )
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(channel: DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
        }

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, observer)
        val sender = localVideoTrack?.let { track -> peerConnection?.addTrack(track, listOf(STREAM_ID)) }
        if (qualityProfile == QualityProfile.DATA_SAVER) {
            sender?.let { applyDataSaverBitrateCap(it) }
        }
    }

    // HIGH leaves RtpSender parameters untouched, preserving WebRTC's existing default/adaptive
    // bitrate behavior exactly as it was before this profile existed.
    private fun applyDataSaverBitrateCap(sender: RtpSender) {
        val params = sender.parameters
        params.encodings.forEach { it.maxBitrateBps = DATA_SAVER_MAX_BITRATE_BPS }
        sender.parameters = params
    }

    private suspend fun createAndSendOffer() {
        val pc = peerConnection ?: error("Peer connection not initialized")
        val offer = pc.suspendCreateOffer(MediaConstraints())
        pc.suspendSetLocalDescription(offer)
        signalingClient.setOffer(sessionId, RtcJson.sessionDescriptionToJson(offer))
        android.util.Log.d(TAG, "Offer sent for session $sessionId")
    }

    private fun observeSignaling() {
        answerEventSource = signalingClient.observeAnswer(sessionId) { raw ->
            if (raw == null || hasRemoteAnswer) return@observeAnswer
            android.util.Log.d(TAG, "Answer received for session $sessionId")
            scope.launch {
                try {
                    val answer = RtcJson.sessionDescriptionFromJson(raw)
                    peerConnection?.suspendSetRemoteDescription(answer)
                    // Flip the flag and drain the queue as one step: candidates arrive on the
                    // signaling stream's thread, and one landing mid-drain must not be lost.
                    val queued = synchronized(answerCandidateQueue) {
                        hasRemoteAnswer = true
                        answerCandidateQueue.toList().also { answerCandidateQueue.clear() }
                    }
                    // Past the share screen now; relay changes are no longer worth re-reporting.
                    waitingForViewer = false
                    // Negotiation is starting for the first time now - start the same countdown
                    // used for a post-Live blip, so a connection that never completes at all
                    // (stuck negotiating forever) doesn't wait on ICE's own, less predictable
                    // internal timeout to eventually declare FAILED.
                    startReconnectCountdown()
                    queued.forEach { peerConnection?.addIceCandidate(it) }
                } catch (e: Exception) {
                    // Malformed/late signaling payload - safe to ignore and wait for the next one.
                    android.util.Log.e(TAG, "Failed to apply remote answer", e)
                }
            }
        }

        answerCandidatesEventSource = signalingClient.observeAnswerCandidates(sessionId) { raw ->
            try {
                val candidate = RtcJson.iceCandidateFromJson(raw)
                android.util.Log.d(TAG, "Remote ICE candidate: ${candidateTypeOf(candidate)}")
                val applyNow = synchronized(answerCandidateQueue) {
                    if (!hasRemoteAnswer) answerCandidateQueue.add(candidate)
                    hasRemoteAnswer
                }
                if (applyNow) peerConnection?.addIceCandidate(candidate)
            } catch (_: Exception) {
                // Ignore a malformed candidate rather than tearing down the whole session.
            }
        }
    }

    // ICE candidate SDP lines look like "candidate:<foundation> 1 udp <priority> <ip> <port>
    // typ host ..." (or "typ srflx" / "typ relay") - pulling that one word out turns a wall of
    // opaque candidate dumps into the single fact that actually matters for diagnosing a failed
    // connection: whether a relay candidate was ever offered at all.
    private fun candidateTypeOf(candidate: IceCandidate): String =
        Regex("""\styp\s+(\w+)""").find(candidate.sdp)?.groupValues?.get(1) ?: "unknown"

    /**
     * Every step here is independently guarded: if any one native WebRTC teardown call throws,
     * the rest of cleanup still runs, and - critically - the caller (ScreenShareService) still
     * sees this call return normally so it can clear its own reference and allow a new session
     * to start. An unguarded throw here previously left a broken session wedged in place,
     * silently blocking every subsequent broadcast attempt until the process was fully killed.
     */
    fun close() {
        if (closed) return
        closed = true
        waitingForViewer = false

        runCatching { reconnectJob?.cancel() }
        runCatching { relayDeadlineJob?.cancel() }
        runCatching { answerEventSource?.cancel() }
        runCatching { answerCandidatesEventSource?.cancel() }
        runCatching { signalingClient.deleteSession(sessionId) }

        runCatching { peerConnection?.close() }
        peerConnection = null

        screenCapturer?.let { capturer ->
            runCatching { capturer.stopCapture() }
            runCatching { capturer.dispose() }
        }
        runCatching { videoSource?.dispose() }
        runCatching { surfaceTextureHelper?.dispose() }
        runCatching { localVideoTrack?.dispose() }
        if (::peerConnectionFactory.isInitialized) {
            runCatching { peerConnectionFactory.dispose() }
        }
        runCatching { eglBase.release() }
    }

    companion object {
        private const val TAG = "ScreenShareHostSession"
        private const val VIDEO_TRACK_ID = "screen_share_track"
        private const val STREAM_ID = "screen_share_stream"
        private const val CAPTURE_FPS = 15
        private const val DATA_SAVER_FPS = 8
        private const val DATA_SAVER_MAX_BITRATE_BPS = 400_000
        private const val RECONNECT_GRACE_SECONDS = 35
        // Same budget RelayProbe gives the Test button: TLS relays across a continent can take
        // several seconds to allocate, but not this long.
        private const val RELAY_DEADLINE_MILLIS = 12_000L
        const val DEFAULT_VIEWER_BASE_URL = "https://layerbit.co.in/tools/layerlink-viewer.html"
    }
}
