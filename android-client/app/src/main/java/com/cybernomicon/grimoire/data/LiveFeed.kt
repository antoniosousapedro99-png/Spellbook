package com.cybernomicon.grimoire.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** One rendered line in the live activity feed. */
data class LiveEvent(val type: String, val detail: String, val ts: Double)

/**
 * Streams the hub's `/ws/activity` WebSocket (spell.*, veil.*, familiar.*,
 * daemon.* events) into [onEvent]. Auth is the token as a query param, matching
 * the hub. Reconnection is intentionally simple: the ViewModel re-connects on
 * (re)link.
 */
class LiveFeedClient(
    baseUrl: String,
    token: String,
    private val onEvent: (LiveEvent) -> Unit,
    private val onState: (Boolean) -> Unit,
) {
    private val client = OkHttpClient()
    private var ws: WebSocket? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val wsUrl = baseUrl.trimEnd('/')
        .replaceFirst("http://", "ws://")
        .replaceFirst("https://", "wss://") + "/ws/activity?token=$token"

    fun connect() {
        close()
        ws = client.newWebSocket(Request.Builder().url(wsUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) =
                    onState(true)

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val o = json.parseToJsonElement(text).jsonObject
                        val type = o["type"]?.jsonPrimitive?.contentOrNull ?: "event"
                        val ts = o["ts"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                        onEvent(LiveEvent(type, summarize(type, o), ts))
                    } catch (_: Exception) { /* ignore malformed frames */ }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable,
                                       response: Response?) = onState(false)

                override fun onClosed(webSocket: WebSocket, code: Int,
                                      reason: String) = onState(false)
            })
    }

    fun close() {
        ws?.cancel(); ws = null
    }

    private fun summarize(type: String, o: JsonObject): String {
        fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
        val parts = when {
            type.startsWith("veil.") ->
                listOfNotNull(s("intensity"), s("visibility"), s("spell_id"),
                    o["noise_score"]?.jsonPrimitive?.contentOrNull?.let { "noise $it" })
            type.startsWith("spell.") -> listOfNotNull(s("spell_id"), s("message"))
            type.startsWith("familiar.") ->
                listOfNotNull(s("thought"), s("name"), s("text"))
            type.startsWith("daemon.") ->
                listOfNotNull(s("daemon"), s("summary"), s("objective"))
            else -> emptyList()
        }
        return parts.joinToString(" · ").take(120)
    }
}
