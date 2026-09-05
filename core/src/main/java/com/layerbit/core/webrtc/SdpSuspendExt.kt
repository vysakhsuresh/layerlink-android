package com.layerbit.core.webrtc

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

suspend fun PeerConnection.suspendCreateOffer(constraints: MediaConstraints): SessionDescription =
    suspendCancellableCoroutine { cont ->
        createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                cont.resume(sdp)
            }
            override fun onCreateFailure(error: String) {
                cont.resumeWithException(IllegalStateException("createOffer failed: $error"))
            }
            override fun onSetSuccess() = Unit
            override fun onSetFailure(error: String) = Unit
        }, constraints)
    }

suspend fun PeerConnection.suspendSetLocalDescription(sdp: SessionDescription): Unit =
    suspendCancellableCoroutine { cont ->
        setLocalDescription(object : SdpObserver {
            override fun onSetSuccess() {
                cont.resume(Unit)
            }
            override fun onSetFailure(error: String) {
                cont.resumeWithException(IllegalStateException("setLocalDescription failed: $error"))
            }
            override fun onCreateSuccess(sdp: SessionDescription) = Unit
            override fun onCreateFailure(error: String) = Unit
        }, sdp)
    }

suspend fun PeerConnection.suspendSetRemoteDescription(sdp: SessionDescription): Unit =
    suspendCancellableCoroutine { cont ->
        setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                cont.resume(Unit)
            }
            override fun onSetFailure(error: String) {
                cont.resumeWithException(IllegalStateException("setRemoteDescription failed: $error"))
            }
            override fun onCreateSuccess(sdp: SessionDescription) = Unit
            override fun onCreateFailure(error: String) = Unit
        }, sdp)
    }
