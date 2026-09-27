package hub.core.android.terminal

import hub.core.android.realtime.SOCKET_PATH
import hub.core.client.infrastructure.Serializer
import hub.core.client.model.TerminalSession
import io.socket.client.Ack
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.transports.Polling
import io.socket.engineio.client.transports.WebSocket
import java.net.URI
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONObject

/*
 * The owner's terminal socket (`/rt/terminal`, events/README.md): open a shell in a profile's
 * folder, attach to a live one (its recent output repaints the screen), type into it, tell it its
 * size, close it; hear its output and its end. Its own socket, opened only while the Terminal page
 * is on screen — the hub refuses the handshake to anyone but the owner.
 */

const val TERMINAL_NAMESPACE = "/rt/terminal"

/** An ack: ok with what it carried, or the hub's refusal (`code`, `details.reason`). */
data class TerminalAck(val ok: Boolean, val session: TerminalSession? = null, val backlog: String? = null, val code: String? = null, val reason: String? = null, val error: String? = null) {
    companion object {
        fun parse(raw: Any?): TerminalAck {
            val json = raw as? JSONObject ?: (raw?.toString()?.let { runCatching { JSONObject(it) }.getOrNull() })
                ?: return TerminalAck(false, code = "no_answer")
            val session = json.optJSONObject("session")?.let {
                runCatching { Serializer.kotlinxSerializationJson.decodeFromString(TerminalSession.serializer(), it.toString()) }.getOrNull()
            }
            return TerminalAck(
                ok = json.optBoolean("ok", false), session = session,
                backlog = json.optString("backlog").takeIf { json.has("backlog") && !json.isNull("backlog") },
                code = json.optString("code").takeIf { it.isNotEmpty() },
                reason = json.optJSONObject("details")?.optString("reason")?.takeIf { it.isNotEmpty() },
                error = json.optString("error").takeIf { it.isNotEmpty() },
            )
        }
    }
}

/** `terminal.output` / `terminal.exited`, read from an envelope. */
sealed interface TerminalEvent {
    val id: String
    data class Output(override val id: String, val data: String) : TerminalEvent
    data class Exited(override val id: String, val reason: String) : TerminalEvent

    companion object {
        fun parse(event: String, raw: Any?): TerminalEvent? {
            val json = raw as? JSONObject ?: return null
            val payload = json.optJSONObject("payload") ?: return null
            val id = payload.optString("terminal_id").takeIf { it.isNotEmpty() } ?: return null
            return when (event) {
                "terminal.output" -> Output(id, payload.optString("data"))
                "terminal.exited" -> Exited(id, payload.optString("reason", "exited"))
                else -> null
            }
        }
    }
}

class TerminalLink(private val hub: String, private val token: String, private val profile: String, private val http: OkHttpClient) {
    private var socket: Socket? = null
    private val _events = MutableSharedFlow<TerminalEvent>(extraBufferCapacity = 1024)
    val events: SharedFlow<TerminalEvent> = _events.asSharedFlow()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    /** A refused handshake (`forbidden` when the terminal is off or the caller is not the owner). */
    private val _refused = MutableStateFlow<String?>(null)
    val refused: StateFlow<String?> = _refused.asStateFlow()

    fun connect() {
        if (socket != null) return
        val options = IO.Options.builder()
            .setPath(SOCKET_PATH)
            .setTransports(arrayOf(WebSocket.NAME, Polling.NAME))
            .setReconnection(true)
            .setReconnectionDelay(1_000)
            .setReconnectionDelayMax(15_000)
            .setTimeout(10_000)
            .setAuth(mapOf("token" to token, "profile" to profile))
            .build()
        options.callFactory = http
        options.webSocketFactory = http
        val s = IO.socket(URI.create(hub + TERMINAL_NAMESPACE), options)
        s.on(Socket.EVENT_CONNECT) { _connected.value = true; _refused.value = null }
        s.on(Socket.EVENT_DISCONNECT) { _connected.value = false }
        s.on(Socket.EVENT_CONNECT_ERROR) { args ->
            _connected.value = false
            _refused.value = (args.firstOrNull() as? Exception)?.message ?: (args.firstOrNull() as? JSONObject)?.optString("message")
        }
        for (name in listOf("terminal.output", "terminal.exited")) {
            s.on(name) { args -> TerminalEvent.parse(name, args.firstOrNull())?.let { _events.tryEmit(it) } }
        }
        socket = s
        s.connect()
    }

    private suspend fun ask(event: String, body: JSONObject): TerminalAck {
        val s = socket ?: return TerminalAck(false, code = "offline")
        if (!s.connected()) return TerminalAck(false, code = "offline")
        return withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { cont ->
                s.emit(event, arrayOf<Any>(body), Ack { args -> if (cont.isActive) cont.resume(TerminalAck.parse(args.firstOrNull())) })
            }
        } ?: TerminalAck(false, code = "timeout")
    }

    suspend fun open(cols: Int, rows: Int): TerminalAck =
        ask("open", JSONObject().put("profile", profile).put("cols", cols).put("rows", rows))

    suspend fun attach(id: String): TerminalAck = ask("attach", JSONObject().put("terminal_id", id))

    fun input(id: String, data: String) {
        socket?.emit("input", JSONObject().put("terminal_id", id).put("data", data))
    }

    fun resize(id: String, cols: Int, rows: Int) {
        socket?.emit("resize", JSONObject().put("terminal_id", id).put("cols", cols).put("rows", rows))
    }

    fun close(id: String) {
        socket?.emit("close", JSONObject().put("terminal_id", id))
    }

    fun disconnect() {
        socket?.off()
        socket?.disconnect()
        socket = null
        _connected.value = false
    }
}
