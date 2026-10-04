package com.layerbit.core.webrtc

import org.json.JSONObject
import org.webrtc.PeerConnection

/**
 * The ICE servers a session negotiates through, and where they came from.
 *
 * This exists because the thing that actually broke cross-country sharing was a *dead TURN
 * relay*, hardcoded in two places (this app and the web sharer/viewer pages).
 * `openrelay.metered.ca`'s long-standing public credentials (`openrelayproject` /
 * `openrelayproject`) are no longer accepted: its server answers the authenticated TURN
 * Allocate with `400 Bad Request` for *every* username, including deliberately wrong ones,
 * while a working coturn answers a bad password with the correct `401 Unauthorized`. So the
 * "TURN fallback" was inert - no relay candidate was ever allocated, and two peers behind
 * carrier NAT (a phone on mobile data in the UAE, a viewer in India) had no candidate pair that
 * could possibly work. Both ends simply sat at "waiting" forever with no error, because ICE
 * never fails loudly; it just never succeeds.
 *
 * Free TURN credentials rot, and re-hardcoding a different free relay would only reset the
 * clock on the same outage - with a Play Store review in the way of the fix. So the server list
 * is resolved at runtime instead, from the first source that has one (see [IceConfigStore]): a
 * relay entered on the device, then a JSON file hosted on layerbit.co.in that can fix the app
 * and both web pages at once, then [builtIn] as the floor.
 */
data class IceConfig(
    val stunUrls: List<String>,
    val turnServers: List<TurnServer>,
    val origin: Origin
) {
    /** Where the relay part of this config came from - shown in-app so an outage is diagnosable. */
    enum class Origin {
        /** Entered on this device, under Relay server. */
        DEVICE,

        /** Fetched from (or last cached from) the hosted JSON config. */
        REMOTE,

        /** Compiled in: STUN only. No relay, so cross-network viewers may not connect. */
        BUILT_IN
    }

    val hasRelay: Boolean get() = turnServers.isNotEmpty()

    fun toIceServers(): List<PeerConnection.IceServer> {
        val servers = mutableListOf<PeerConnection.IceServer>()
        stunUrls.forEach { servers += PeerConnection.IceServer.builder(it).createIceServer() }
        turnServers.forEach { turn ->
            servers += PeerConnection.IceServer.builder(turn.urls)
                .setUsername(turn.username)
                .setPassword(turn.password)
                .createIceServer()
        }
        return servers
    }

    companion object {
        /**
         * STUN only. Enough for two peers on friendly networks - which is why same-city testing
         * always looked fine - and deliberately *without* a TURN entry: a relay that rejects
         * every credential costs seconds of ICE gathering and, worse, makes a missing relay
         * indistinguishable from a working one. With no entry at all, the app can say so.
         */
        val builtIn = IceConfig(
            stunUrls = listOf(
                "stun:stun.l.google.com:19302",
                "stun:stun1.l.google.com:19302",
                "stun:stun.cloudflare.com:3478"
            ),
            turnServers = emptyList(),
            origin = Origin.BUILT_IN
        )

        /**
         * Parses the hosted config. Unknown keys are ignored and a malformed `turn` entry is
         * skipped rather than failing the whole document, so one typo never leaves the app with
         * no ICE servers at all.
         *
         * ```json
         * { "stun": ["stun:stun.l.google.com:19302"],
         *   "turn": [ { "host": "relay.example.com", "username": "u", "credential": "p" } ] }
         * ```
         */
        fun fromJson(raw: String, origin: Origin): IceConfig? {
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null

            val stun = mutableListOf<String>()
            json.optJSONArray("stun")?.let { array ->
                for (i in 0 until array.length()) {
                    array.optString(i).takeIf { it.isNotBlank() }?.let(stun::add)
                }
            }

            val turn = mutableListOf<TurnServer>()
            json.optJSONArray("turn")?.let { array ->
                for (i in 0 until array.length()) {
                    val entry = array.optJSONObject(i) ?: continue
                    val username = entry.optString("username")
                    val credential = entry.optString("credential")
                        .ifEmpty { entry.optString("password") }
                    if (username.isBlank() || credential.isBlank()) continue

                    val urls = mutableListOf<String>()
                    entry.optJSONArray("urls")?.let { rawUrls ->
                        for (u in 0 until rawUrls.length()) {
                            rawUrls.optString(u).takeIf { it.isNotBlank() }?.let(urls::add)
                        }
                    }
                    val server = if (urls.isNotEmpty()) {
                        TurnServer(urls, username, credential)
                    } else {
                        entry.optString("host").takeIf { it.isNotBlank() }
                            ?.let { TurnServer.forHost(it, username, credential) }
                    }
                    if (server != null) turn += server
                }
            }

            if (stun.isEmpty() && turn.isEmpty()) return null
            return IceConfig(
                stunUrls = stun.ifEmpty { builtIn.stunUrls },
                turnServers = turn,
                origin = origin
            )
        }
    }
}

/**
 * One TURN relay, as the full set of ways to reach it. [urls] share one credential, which is
 * exactly what WebRTC's own `IceServer` models: it tries each in parallel and keeps whichever
 * gets through.
 */
data class TurnServer(
    val urls: List<String>,
    val username: String,
    val password: String
) {
    companion object {
        /**
         * Expands a bare relay hostname into every transport worth trying, which matters far
         * more than it looks:
         *
         *  - `udp:3478` is the fast path, and the first thing a restrictive network drops.
         *  - `tcp:80` and `tcp:443` survive networks that block UDP outright.
         *  - **`turns:` on 443 is the one that gets through deep packet inspection**, because it
         *    is a real TLS handshake on the HTTPS port, indistinguishable from web traffic. The
         *    old hardcoded config had no `turns:` entry at all - only `turn:...:443?transport=tcp`,
         *    which is *plain* TURN on 443. On the relay this app shipped with, that port answers
         *    only TLS: a plain-TCP Allocate there draws no reply whatsoever, so the single entry
         *    meant to be the last-ditch fallback could not have worked even with valid
         *    credentials. Networks that filter by protocol rather than by port block it for the
         *    same reason.
         *  - `turns:5349` is the registered TURN-over-TLS port, for relays that don't serve 443.
         */
        fun forHost(host: String, username: String, password: String): TurnServer {
            val bare = host.trim()
                .removePrefix("turns:")
                .removePrefix("turn:")
                .substringBefore(':')
                .substringBefore('?')
                .trim('/')
            return TurnServer(
                urls = listOf(
                    "turn:$bare:3478?transport=udp",
                    "turn:$bare:3478?transport=tcp",
                    "turn:$bare:80?transport=tcp",
                    "turn:$bare:443?transport=tcp",
                    "turns:$bare:443?transport=tcp",
                    "turns:$bare:5349?transport=tcp"
                ),
                username = username,
                password = password
            )
        }
    }
}
