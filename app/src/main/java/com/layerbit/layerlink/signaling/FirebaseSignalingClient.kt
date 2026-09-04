package com.layerbit.layerlink.signaling

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject

/**
 * Talks to the LayerLink Firebase Realtime Database directly over its REST + Server-Sent
 * Events API, replicating the exact operations the web app performs through the Firebase JS
 * SDK against `sessions/{sessionId}`:
 *
 *  - `offer` / `answer`            -> a single string node holding `JSON.stringify(description)`
 *  - `offerCandidates` / `answerCandidates` -> a list of pushed string nodes, one per ICE candidate
 *
 * The web app never authenticates (no `firebase.auth()` call anywhere in the sharer/viewer
 * pages), so the database rules are open read/write for this project - this client relies on
 * that same, already-in-place access model rather than adding one of its own. No Firebase
 * Android SDK / `google-services.json` is required for this REST-only approach.
 */
class FirebaseSignalingClient(
    private val databaseUrl: String = DEFAULT_DATABASE_URL,
    // `answer`/`answerCandidates` are long-lived Server-Sent-Events streams: Firebase only
    // pushes a new event when the value actually changes (e.g. once the web viewer finishes
    // loading and posts its answer, which can easily take longer than OkHttp's 10s default
    // read timeout). A finite read timeout kills the stream mid-wait, so the host silently
    // stops listening even though the signaling data eventually does arrive in the database.
    private val client: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
) {

    fun setOffer(sessionId: String, offerJson: String) {
        putString(nodeUrl(sessionId, "offer"), offerJson)
    }

    fun pushOfferCandidate(sessionId: String, candidateJson: String) {
        postString(nodeUrl(sessionId, "offerCandidates"), candidateJson)
    }

    fun deleteSession(sessionId: String) {
        val request = Request.Builder().url(nodeUrl(sessionId, null)).delete().build()
        client.newCall(request).enqueue(NoopCallback)
    }

    /** Mirrors `conn.child("answer").on("value", ...)`. Invokes [onValue] with `null` while unset. */
    fun observeAnswer(sessionId: String, onValue: (String?) -> Unit): EventSource {
        val request = streamRequest(nodeUrl(sessionId, "answer"))
        return EventSources.createFactory(client).newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (type != "put" && type != "patch") return
                runCatching {
                    val payload = JSONObject(data)
                    if (payload.optString("path", "/") != "/") return
                    onValue(if (payload.isNull("data")) null else payload.optString("data"))
                }
            }
        })
    }

    /** Mirrors `conn.child("answerCandidates").on("child_added", ...)`. */
    fun observeAnswerCandidates(sessionId: String, onChildAdded: (String) -> Unit): EventSource {
        val request = streamRequest(nodeUrl(sessionId, "answerCandidates"))
        return EventSources.createFactory(client).newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (type != "put" && type != "patch") return
                runCatching {
                    val payload = JSONObject(data)
                    if (payload.isNull("data")) return@runCatching
                    val path = payload.optString("path", "/")
                    if (path == "/") {
                        // Initial snapshot: an object of every existing child keyed by push id.
                        val children = payload.getJSONObject("data")
                        children.keys().forEach { key -> onChildAdded(children.getString(key)) }
                    } else {
                        // A single new child was pushed after we started listening.
                        onChildAdded(payload.getString("data"))
                    }
                }
            }
        })
    }

    private fun nodeUrl(sessionId: String, child: String?): String {
        val suffix = if (child != null) "/$child" else ""
        return "$databaseUrl/sessions/$sessionId$suffix.json"
    }

    private fun streamRequest(url: String): Request =
        Request.Builder().url(url).header("Accept", "text/event-stream").build()

    private fun putString(url: String, value: String) {
        val body = JSONObject.quote(value).toRequestBody(JSON_MEDIA_TYPE)
        client.newCall(Request.Builder().url(url).put(body).build()).enqueue(NoopCallback)
    }

    private fun postString(url: String, value: String) {
        val body = JSONObject.quote(value).toRequestBody(JSON_MEDIA_TYPE)
        client.newCall(Request.Builder().url(url).post(body).build()).enqueue(NoopCallback)
    }

    private object NoopCallback : Callback {
        override fun onFailure(call: Call, e: IOException) = Unit
        override fun onResponse(call: Call, response: Response) = response.close()
    }

    companion object {
        private const val DEFAULT_DATABASE_URL = "https://layerlink-58948-default-rtdb.firebaseio.com"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
