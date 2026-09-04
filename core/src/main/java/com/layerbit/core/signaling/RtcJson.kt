package com.layerbit.core.signaling

import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

/**
 * Wire format helpers matching exactly what the LayerLink web sharer/viewer pages read and
 * write via `JSON.stringify(offer)` / `JSON.stringify(candidate)` (see
 * layerlink-sharer.body.html / layerlink-viewer.body.html in the layerbit-site repo), so
 * offers, answers and ICE candidates produced here interop with a browser peer unchanged.
 */
object RtcJson {

    fun sessionDescriptionToJson(description: SessionDescription): String {
        val json = JSONObject()
        json.put("type", description.type.canonicalForm())
        json.put("sdp", description.description)
        return json.toString()
    }

    fun sessionDescriptionFromJson(raw: String): SessionDescription {
        val json = JSONObject(raw)
        val type = SessionDescription.Type.fromCanonicalForm(json.getString("type"))
        return SessionDescription(type, json.getString("sdp"))
    }

    fun iceCandidateToJson(candidate: IceCandidate): String {
        val json = JSONObject()
        json.put("candidate", candidate.sdp)
        json.put("sdpMid", candidate.sdpMid)
        json.put("sdpMLineIndex", candidate.sdpMLineIndex)
        return json.toString()
    }

    fun iceCandidateFromJson(raw: String): IceCandidate {
        val json = JSONObject(raw)
        val sdpMid = json.optString("sdpMid", "0")
        val sdpMLineIndex = json.optInt("sdpMLineIndex", 0)
        return IceCandidate(sdpMid, sdpMLineIndex, json.getString("candidate"))
    }
}
