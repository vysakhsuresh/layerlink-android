package com.layerbit.core.signaling

import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
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

/** A live listener on a signaling node. [cancel] stops it for good, including any pending reconnect. */
interface SignalingSubscription {
    fun cancel()
}

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
 *
 * The Firebase JS SDK quietly reconnects and retries for the web pages; this client has to do
 * the same itself. Mobile networks drop idle connections and switch between Wi-Fi and cellular
 * all the time, and a broadcast typically sits waiting for its viewer for minutes - so streams
 * reconnect with backoff, and writes are retried a few times before being given up on.
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

    // Writes are short request/response calls, so unlike the streams they need finite timeouts:
    // on a half-dead network an unbounded write would hang forever instead of being retried.
    private val writeClient: OkHttpClient = client.newBuilder()
        .readTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(WRITE_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    fun setOffer(sessionId: String, offerJson: String) {
        val body = JSONObject.quote(offerJson).toRequestBody(JSON_MEDIA_TYPE)
        send(Request.Builder().url(nodeUrl(sessionId, "offer")).put(body).build())
    }

    fun pushOfferCandidate(sessionId: String, candidateJson: String) {
        val body = JSONObject.quote(candidateJson).toRequestBody(JSON_MEDIA_TYPE)
        send(Request.Builder().url(nodeUrl(sessionId, "offerCandidates")).post(body).build())
    }

    fun deleteSession(sessionId: String) {
        send(Request.Builder().url(nodeUrl(sessionId, null)).delete().build())
    }

    /** Mirrors `conn.child("answer").on("value", ...)`. Invokes [onValue] with `null` while unset. */
    fun observeAnswer(sessionId: String, onValue: (String?) -> Unit): SignalingSubscription {
        // A reconnect replays the node's current value; only pass on actual changes.
        var lastValue: String? = null
        var delivered = false
        return ResilientStream(nodeUrl(sessionId, "answer")) { payload ->
            if (payload.optString("path", "/") != "/") return@ResilientStream
            val value = if (payload.isNull("data")) null else payload.optString("data")
            if (delivered && value == lastValue) return@ResilientStream
            delivered = true
            lastValue = value
            onValue(value)
        }.also { it.start() }
    }

    /** Mirrors `conn.child("answerCandidates").on("child_added", ...)`. */
    fun observeAnswerCandidates(sessionId: String, onChildAdded: (String) -> Unit): SignalingSubscription {
        // Every (re)connect starts with a snapshot of all existing children, so remember which
        // push ids were already handed over and never deliver the same candidate twice.
        val seenKeys = HashSet<String>()
        fun deliver(key: String, value: Any?) {
            if (value !is String || !seenKeys.add(key)) return
            onChildAdded(value)
        }
        return ResilientStream(nodeUrl(sessionId, "answerCandidates")) { payload ->
            if (payload.isNull("data")) return@ResilientStream
            val path = payload.optString("path", "/").trim('/')
            if (path.isEmpty()) {
                // Snapshot (put) or multi-child update (patch): an object keyed by push id.
                val children = payload.getJSONObject("data")
                children.keys().forEach { key -> deliver(key, children.get(key)) }
            } else {
                // A single new child was pushed after we started listening.
                deliver(path.substringBefore('/'), payload.get("data"))
            }
        }.also { it.start() }
    }

    private fun nodeUrl(sessionId: String, child: String?): String {
        val suffix = if (child != null) "/$child" else ""
        return "$databaseUrl/sessions/$sessionId$suffix.json"
    }

    /** Fire-and-forget write, retried on network failure or a server error. */
    private fun send(request: Request, attempt: Int = 1) {
        writeClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = retry()

            override fun onResponse(call: Call, response: Response) {
                val serverError = response.code >= 500
                response.close()
                if (serverError) retry()
            }

            private fun retry() {
                if (attempt >= MAX_WRITE_ATTEMPTS) return
                scheduler.schedule({ send(request, attempt + 1) }, attempt * WRITE_RETRY_STEP_MILLIS, TimeUnit.MILLISECONDS)
            }
        })
    }

    /**
     * An SSE stream that reopens itself whenever Firebase or the network closes it, until
     * [cancel] is called. Each callback is a decoded `put`/`patch` payload (`{path, data}`).
     */
    private inner class ResilientStream(
        url: String,
        private val onPayload: (JSONObject) -> Unit
    ) : SignalingSubscription {
        private val request = Request.Builder().url(url).header("Accept", "text/event-stream").build()
        private val lock = Any()
        private var current: EventSource? = null
        private var cancelled = false
        private var failures = 0

        fun start() = connect()

        override fun cancel() {
            synchronized(lock) {
                cancelled = true
                current?.cancel()
                current = null
            }
        }

        private fun connect() {
            synchronized(lock) {
                if (cancelled) return
                current = EventSources.createFactory(client).newEventSource(request, listener)
            }
        }

        private fun scheduleReconnect(source: EventSource) {
            val delayMillis = synchronized(lock) {
                // A stale callback from a stream that was already replaced or cancelled.
                if (cancelled || current !== source) return
                current = null
                (RECONNECT_BASE_MILLIS shl failures.coerceAtMost(4)).coerceAtMost(RECONNECT_MAX_MILLIS)
                    .also { failures++ }
            }
            scheduler.schedule({ connect() }, delayMillis, TimeUnit.MILLISECONDS)
        }

        private val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                synchronized(lock) { failures = 0 }
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                when (type) {
                    "put", "patch" -> synchronized(lock) {
                        if (cancelled || current !== eventSource) return
                        runCatching { onPayload(JSONObject(data)) }
                    }
                    // Firebase revoked the listen (rules/auth change): reopen and resubscribe.
                    "cancel", "auth_revoked" -> {
                        eventSource.cancel()
                        scheduleReconnect(eventSource)
                    }
                }
            }

            override fun onClosed(eventSource: EventSource) = scheduleReconnect(eventSource)

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                response?.close()
                scheduleReconnect(eventSource)
            }
        }
    }

    companion object {
        private const val DEFAULT_DATABASE_URL = "https://layerlink-58948-default-rtdb.firebaseio.com"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        private const val WRITE_TIMEOUT_SECONDS = 15L
        private const val WRITE_CALL_TIMEOUT_SECONDS = 20L
        private const val MAX_WRITE_ATTEMPTS = 4
        private const val WRITE_RETRY_STEP_MILLIS = 1_000L
        private const val RECONNECT_BASE_MILLIS = 1_000L
        private const val RECONNECT_MAX_MILLIS = 10_000L

        // One tiny daemon thread shared by every session's retries and reconnects.
        private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "layerlink-signaling").apply { isDaemon = true }
        }
    }
}
