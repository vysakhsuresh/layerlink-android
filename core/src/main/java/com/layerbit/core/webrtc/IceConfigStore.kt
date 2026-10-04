package com.layerbit.core.webrtc

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Resolves the [IceConfig] a session should use, and remembers a relay entered on the device.
 *
 * Three sources, highest priority first:
 *
 *  1. **This device.** A relay the user entered under Relay server. Survives reinstalling
 *     nothing, but needs no app release - which is the whole point, because the previous relay
 *     outage was only fixable by shipping a new APK through Play review.
 *  2. **The hosted config**, [REMOTE_CONFIG_URL]. One JSON file on layerbit.co.in, read by this
 *     app *and* (once the pages are updated to fetch it too) by the web sharer and viewer, so a
 *     relay can be swapped for every client at once. Cached, so a session never blocks on it
 *     and a flaky network falls back to the last known-good list.
 *  3. **[IceConfig.builtIn]**, STUN only.
 *
 * [resolve] never throws and never returns an empty list: the worst case is STUN-only, which is
 * what the app effectively had anyway while its hardcoded relay was rejecting every credential.
 */
class IceConfigStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(REMOTE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(REMOTE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** The relay entered on this device, or null if none has been. */
    fun deviceRelay(): DeviceRelay? {
        val host = prefs.getString(KEY_HOST, null)?.takeIf { it.isNotBlank() } ?: return null
        val username = prefs.getString(KEY_USERNAME, null)?.takeIf { it.isNotBlank() } ?: return null
        val password = prefs.getString(KEY_PASSWORD, null)?.takeIf { it.isNotBlank() } ?: return null
        return DeviceRelay(host, username, password)
    }

    fun saveDeviceRelay(relay: DeviceRelay) {
        prefs.edit()
            .putString(KEY_HOST, relay.host.trim())
            .putString(KEY_USERNAME, relay.username.trim())
            .putString(KEY_PASSWORD, relay.password.trim())
            .apply()
    }

    fun clearDeviceRelay() {
        prefs.edit().remove(KEY_HOST).remove(KEY_USERNAME).remove(KEY_PASSWORD).apply()
    }

    /**
     * What [resolve] would return without touching the network: the device relay, else the last
     * cached hosted config, else the built-in floor. For UI that renders on every frame and must
     * not do I/O - the relay tile - where "we'll fetch it when you press Start" is good enough.
     */
    fun resolveCached(): IceConfig {
        deviceRelay()?.let { relay ->
            return IceConfig(
                stunUrls = IceConfig.builtIn.stunUrls,
                turnServers = listOf(TurnServer.forHost(relay.host, relay.username, relay.password)),
                origin = IceConfig.Origin.DEVICE
            )
        }
        prefs.getString(KEY_REMOTE_CACHE, null)?.let { cached ->
            IceConfig.fromJson(cached, IceConfig.Origin.REMOTE)?.let { return it }
        }
        return IceConfig.builtIn
    }

    /**
     * The config for the next session. Safe to call on the main thread - the network fetch is
     * moved to IO, and is skipped entirely when a device relay is set or the cache is still
     * fresh, so starting a broadcast normally costs no round trip at all.
     */
    suspend fun resolve(): IceConfig {
        deviceRelay()?.let { relay ->
            return IceConfig(
                stunUrls = IceConfig.builtIn.stunUrls,
                turnServers = listOf(TurnServer.forHost(relay.host, relay.username, relay.password)),
                origin = IceConfig.Origin.DEVICE
            )
        }

        val cached = prefs.getString(KEY_REMOTE_CACHE, null)
        val cachedAt = prefs.getLong(KEY_REMOTE_CACHED_AT, 0L)
        val fresh = System.currentTimeMillis() - cachedAt < CACHE_TTL_MILLIS
        if (cached != null && fresh) {
            IceConfig.fromJson(cached, IceConfig.Origin.REMOTE)?.let { return it }
        }

        val fetched = fetchRemote()
        if (fetched != null) {
            prefs.edit()
                .putString(KEY_REMOTE_CACHE, fetched)
                .putLong(KEY_REMOTE_CACHED_AT, System.currentTimeMillis())
                .apply()
            IceConfig.fromJson(fetched, IceConfig.Origin.REMOTE)?.let { return it }
        }

        // The fetch failed (offline, 404, server down) but a stale cache is still a real relay
        // list, and a stale relay beats no relay.
        if (cached != null) {
            IceConfig.fromJson(cached, IceConfig.Origin.REMOTE)?.let { return it }
        }
        return IceConfig.builtIn
    }

    private suspend fun fetchRemote(): String? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(REMOTE_CONFIG_URL).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    /** A relay as the user types it: a hostname plus one static credential pair. */
    data class DeviceRelay(val host: String, val username: String, val password: String)

    companion object {
        /**
         * Deliberately a plain static file rather than an endpoint: it can be dropped next to
         * the existing viewer/sharer pages with no backend, and the same file is what those
         * pages should read so all three clients share one relay list. A 404 here is not an
         * error - it just means the file hasn't been published yet, and the app falls through.
         */
        const val REMOTE_CONFIG_URL = "https://layerbit.co.in/tools/layerlink-ice.json"

        private const val PREFS_NAME = "layerlink_ice_config"
        private const val KEY_HOST = "relay_host"
        private const val KEY_USERNAME = "relay_username"
        private const val KEY_PASSWORD = "relay_password"
        private const val KEY_REMOTE_CACHE = "remote_cache"
        private const val KEY_REMOTE_CACHED_AT = "remote_cached_at"

        private const val REMOTE_TIMEOUT_SECONDS = 6L
        private val CACHE_TTL_MILLIS = TimeUnit.HOURS.toMillis(6)
    }
}
