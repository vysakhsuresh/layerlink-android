package com.layerbit.core.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import com.layerbit.core.signaling.FirebaseSignalingClient
import com.layerbit.core.signaling.RtcJson
import com.layerbit.core.util.SessionIdGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.sse.EventSource
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
    private val qualityProfile: QualityProfile = QualityProfile.HIGH
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

    private var answerEventSource: EventSource? = null
    private var answerCandidatesEventSource: EventSource? = null

    private var hasRemoteAnswer = false
    private val answerCandidateQueue = mutableListOf<IceCandidate>()

    private var closed = false

    /** Must be called only after the owning foreground service has called startForeground(). */
    fun start() {
        listener.onStateChanged(SessionState.Requesting)
        try {
            initPeerConnectionFactory()
            startScreenCapture()
            createPeerConnection()
        } catch (e: Exception) {
            listener.onStateChanged(SessionState.Error(e.message ?: "Failed to start capture"))
            return
        }

        scope.launch {
            try {
                createAndSendOffer()
                listener.onStateChanged(SessionState.Waiting(viewerUrl, sessionId))
                observeSignaling()
            } catch (e: Exception) {
                listener.onStateChanged(SessionState.Error(e.message ?: "Failed to start session"))
            }
        }
    }

    private fun initPeerConnectionFactory() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions()
        )
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
                    listener.onStateChanged(SessionState.Closed(viewerUrl, sessionId))
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
        // since ICE just never finds a working candidate pair. A TURN relay fallback (matching
        // the one added to layerlink-sharer.html/layerlink-viewer.html) fixes that by giving both
        // sides a relayed path when a direct one isn't possible.
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                signalingClient.pushOfferCandidate(sessionId, RtcJson.iceCandidateToJson(candidate))
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                android.util.Log.d(TAG, "onConnectionChange: $newState")
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED ->
                        listener.onStateChanged(SessionState.Live(viewerUrl, sessionId))
                    PeerConnection.PeerConnectionState.DISCONNECTED,
                    PeerConnection.PeerConnectionState.FAILED ->
                        listener.onStateChanged(SessionState.Closed(viewerUrl, sessionId))
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
                    hasRemoteAnswer = true
                    answerCandidateQueue.forEach { peerConnection?.addIceCandidate(it) }
                    answerCandidateQueue.clear()
                } catch (e: Exception) {
                    // Malformed/late signaling payload - safe to ignore and wait for the next one.
                    android.util.Log.e(TAG, "Failed to apply remote answer", e)
                }
            }
        }

        answerCandidatesEventSource = signalingClient.observeAnswerCandidates(sessionId) { raw ->
            try {
                val candidate = RtcJson.iceCandidateFromJson(raw)
                if (hasRemoteAnswer) {
                    peerConnection?.addIceCandidate(candidate)
                } else {
                    answerCandidateQueue.add(candidate)
                }
            } catch (_: Exception) {
                // Ignore a malformed candidate rather than tearing down the whole session.
            }
        }
    }

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
        const val DEFAULT_VIEWER_BASE_URL = "https://layerbit.co.in/tools/layerlink-viewer.html"
    }
}
