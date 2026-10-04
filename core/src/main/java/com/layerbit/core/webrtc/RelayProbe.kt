package com.layerbit.core.webrtc

import android.content.Context
import android.util.Log
import java.util.Collections
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver

/**
 * Answers one question, before a broadcast rather than after it: *can this phone, on this
 * network, actually obtain a TURN relay candidate?*
 *
 * It is deliberately not a hand-written STUN/TURN client. It drives WebRTC's own ICE agent with
 * `iceTransportsType = RELAY`, which makes the agent gather *nothing but* relay candidates - so
 * a single candidate arriving is proof that the real session will get one too, over the same
 * code path, the same credentials and the same network. That is the check whose absence let a
 * relay with dead credentials sit in this app shipping release after release: WebRTC never
 * complains about a TURN server that rejects it, it just quietly gathers one candidate fewer.
 */
object RelayProbe {

    sealed class Result {
        /** At least one relay candidate was allocated. [via] lists the TURN URLs that produced them. */
        data class Working(val via: List<String>) : Result()

        /**
         * The relay is configured but allocated nothing before [RELAY_TIMEOUT_MILLIS]: wrong
         * credentials, a dead host, or a network blocking every transport it offers.
         */
        data object Rejected : Result()

        /** No relay in the resolved config at all - nothing to test. */
        data object NotConfigured : Result()

        /** WebRTC itself couldn't be set up to run the check. */
        data class Unavailable(val message: String) : Result()
    }

    suspend fun run(context: Context, config: IceConfig): Result {
        if (!config.hasRelay) return Result.NotConfigured

        var factory: PeerConnectionFactory? = null
        var peerConnection: PeerConnection? = null
        var dataChannel: DataChannel? = null
        try {
            WebRtcInitializer.ensureInitialized(context)
            val created = PeerConnectionFactory.builder().createPeerConnectionFactory()
                ?: return Result.Unavailable("Could not create a WebRTC factory")
            factory = created

            // Relay-only, so every candidate gathered is by definition a relay candidate.
            val rtcConfig = PeerConnection.RTCConfiguration(config.toIceServers()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                iceTransportsType = PeerConnection.IceTransportsType.RELAY
                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            }

            val relayUrls = Collections.synchronizedList(mutableListOf<String>())
            val gathered = withTimeoutOrNull(RELAY_TIMEOUT_MILLIS) {
                suspendCancellableCoroutine<List<String>> { cont ->
                    // Resuming a continuation twice throws. Both of the paths below can fire,
                    // and a timeout can cancel between the isActive check and the resume, so
                    // the one-shot is a latch rather than a liveness check.
                    val resumed = java.util.concurrent.atomic.AtomicBoolean(false)
                    fun finish() {
                        if (resumed.compareAndSet(false, true) && cont.isActive) {
                            cont.resume(relayUrls.toList())
                        }
                    }

                    val observer = object : PeerConnection.Observer {
                        override fun onIceCandidate(candidate: IceCandidate) {
                            // serverUrl names which of the relay's transports answered, which is
                            // the genuinely useful part: "turns:...:443?transport=tcp" means the
                            // TLS path works, and that is the one that survives a restrictive ISP.
                            val url = candidate.serverUrl?.takeIf { it.isNotBlank() } ?: "relay"
                            if (!relayUrls.contains(url)) relayUrls.add(url)
                            Log.d(TAG, "Relay candidate via $url")
                            // One candidate already answers the question; waiting for gathering
                            // to complete would just sit through every transport's timeout.
                            finish()
                        }

                        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                            if (state == PeerConnection.IceGatheringState.COMPLETE) finish()
                        }

                        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
                        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
                        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
                        override fun onAddStream(stream: MediaStream) = Unit
                        override fun onRemoveStream(stream: MediaStream) = Unit
                        override fun onDataChannel(channel: DataChannel) = Unit
                        override fun onRenegotiationNeeded() = Unit
                        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
                    }

                    val pc = created.createPeerConnection(rtcConfig, observer)
                    if (pc == null) {
                        finish()
                        return@suspendCancellableCoroutine
                    }
                    peerConnection = pc

                    // Gathering only starts once there is something to negotiate. A data channel
                    // is the cheapest such thing - no camera, no screen capture, no permission.
                    dataChannel = pc.createDataChannel("relay-probe", DataChannel.Init())
                    pc.createOffer(
                        object : org.webrtc.SdpObserver {
                            override fun onCreateSuccess(sdp: org.webrtc.SessionDescription) {
                                pc.setLocalDescription(NoopSdpObserver, sdp)
                            }
                            override fun onCreateFailure(error: String) {
                                finish()
                            }
                            override fun onSetSuccess() = Unit
                            override fun onSetFailure(error: String) = Unit
                        },
                        MediaConstraints()
                    )
                }
            }

            return when {
                gathered == null -> Result.Rejected
                gathered.isEmpty() -> Result.Rejected
                else -> Result.Working(gathered)
            }
        } catch (e: Exception) {
            return Result.Unavailable(e.message ?: "Relay check failed")
        } finally {
            runCatching { dataChannel?.close() }
            runCatching { dataChannel?.dispose() }
            runCatching { peerConnection?.close() }
            runCatching { peerConnection?.dispose() }
            runCatching { factory?.dispose() }
        }
    }

    private object NoopSdpObserver : org.webrtc.SdpObserver {
        override fun onCreateSuccess(sdp: org.webrtc.SessionDescription) = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetSuccess() = Unit
        override fun onSetFailure(error: String) = Unit
    }

    private const val TAG = "RelayProbe"

    /**
     * Long enough for the TLS transports to complete a handshake and an Allocate across a
     * continent, short enough that a dead relay doesn't look like a hung app.
     */
    private const val RELAY_TIMEOUT_MILLIS = 12_000L
}
