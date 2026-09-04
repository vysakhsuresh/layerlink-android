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
    private val viewerBaseUrl: String = DEFAULT_VIEWER_BASE_URL
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
        val width = metrics.widthPixels
        val height = metrics.heightPixels

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
        capturer.startCapture(width, height, CAPTURE_FPS)

        screenCapturer = capturer
        videoSource = source
        surfaceTextureHelper = helper
        localVideoTrack = peerConnectionFactory.createVideoTrack(VIDEO_TRACK_ID, source)
    }

    private fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                signalingClient.pushOfferCandidate(sessionId, RtcJson.iceCandidateToJson(candidate))
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
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
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(channel: DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
        }

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, observer)
        localVideoTrack?.let { track -> peerConnection?.addTrack(track, listOf(STREAM_ID)) }
    }

    private suspend fun createAndSendOffer() {
        val pc = peerConnection ?: error("Peer connection not initialized")
        val offer = pc.suspendCreateOffer(MediaConstraints())
        pc.suspendSetLocalDescription(offer)
        signalingClient.setOffer(sessionId, RtcJson.sessionDescriptionToJson(offer))
    }

    private fun observeSignaling() {
        answerEventSource = signalingClient.observeAnswer(sessionId) { raw ->
            if (raw == null || hasRemoteAnswer) return@observeAnswer
            scope.launch {
                try {
                    val answer = RtcJson.sessionDescriptionFromJson(raw)
                    peerConnection?.suspendSetRemoteDescription(answer)
                    hasRemoteAnswer = true
                    answerCandidateQueue.forEach { peerConnection?.addIceCandidate(it) }
                    answerCandidateQueue.clear()
                } catch (_: Exception) {
                    // Malformed/late signaling payload - safe to ignore and wait for the next one.
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

    fun close() {
        if (closed) return
        closed = true

        answerEventSource?.cancel()
        answerCandidatesEventSource?.cancel()
        signalingClient.deleteSession(sessionId)

        peerConnection?.close()
        peerConnection = null

        screenCapturer?.let { capturer ->
            runCatching { capturer.stopCapture() }
            capturer.dispose()
        }
        videoSource?.dispose()
        surfaceTextureHelper?.dispose()
        localVideoTrack?.dispose()
        if (::peerConnectionFactory.isInitialized) {
            peerConnectionFactory.dispose()
        }
        eglBase.release()
    }

    companion object {
        private const val VIDEO_TRACK_ID = "screen_share_track"
        private const val STREAM_ID = "screen_share_stream"
        private const val CAPTURE_FPS = 15
        const val DEFAULT_VIEWER_BASE_URL = "https://layerbit.co.in/tools/layerlink-viewer.html"
    }
}
