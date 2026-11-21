package app.genkan.reader

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One message from a texthooker: hookbridge's JSON (`{sentence, thread, hookcode, ctx, ctx2}`)
 * or a plain-text frame (Textractor's WebSocket plugin, LunaTranslator). [thread] is empty when
 * the source doesn't say; such lines skip the thread picker.
 */
data class HookMessage(
    val text: String,
    val thread: String = "",
    val hookCode: String = "",
    /** A complete line, as the game holds it (a Yomikata layout's): not a piece to carry on. */
    val whole: Boolean = false,
    /** For a whole line: the line it grew from, already in the backlog, which it replaces. */
    val takesBack: String? = null,
) {
    companion object {
        fun parse(frame: String): HookMessage? {
            val fields = if (frame.trim().startsWith('{')) Lines.jsonStringFields(frame.trim()) else null
            val text = Lines.messageText(frame)
            if (text.isBlank()) return null
            return HookMessage(text, fields?.get("thread").orEmpty(), fields?.get("hookcode").orEmpty())
        }
    }
}

sealed interface HookStatus {
    val address: String
    data class Waiting(override val address: String) : HookStatus
    data class Connected(override val address: String) : HookStatus
}

/**
 * A WebSocket client for a texthooker on this device (hookbridge serves 127.0.0.1:6677). It
 * keeps trying while running: the game, and so the server, comes and goes.
 */
@Singleton
class TextHookClient @Inject constructor(okHttp: OkHttpClient) {

    private val client = okHttp.newBuilder().build()
    private var job: Job? = null

    private val _messages = MutableSharedFlow<HookMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<HookMessage> = _messages.asSharedFlow()

    private val _status = MutableStateFlow<HookStatus>(HookStatus.Waiting(DEFAULT_ADDRESS))
    val status: StateFlow<HookStatus> = _status.asStateFlow()

    fun start(scope: CoroutineScope, address: String = DEFAULT_ADDRESS) {
        if (job?.isActive == true) return
        job = scope.launch {
            var wait = RETRY_MIN_MS
            while (isActive) {
                _status.value = HookStatus.Waiting(address)
                var connected = false
                frames(address).collect { frame ->
                    if (frame == null) {
                        connected = true
                        _status.value = HookStatus.Connected(address)
                        Log.i(TAG, "Connected to $address")
                    } else {
                        HookMessage.parse(frame)?.let { _messages.emit(it) }
                    }
                }
                if (connected) Log.i(TAG, "Disconnected from $address")
                wait = if (connected) RETRY_MIN_MS else (wait * 2).coerceAtMost(RETRY_MAX_MS)
                delay(wait)
            }
        }
    }

    /** One connection's frames, null first when it opens; completes when it closes or fails. */
    private fun frames(address: String) = callbackFlow<String?> {
        val socket = client.newWebSocket(
            Request.Builder().url(address).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    trySendBlocking(null)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    trySendBlocking(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    channel.close()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    // Refused connections are the normal state while no game runs; log anything else.
                    if (t !is java.net.ConnectException) Log.w(TAG, "WebSocket to $address failed: $t")
                    channel.close()
                }
            },
        )
        awaitClose { socket.cancel() }
    }

    private companion object {
        const val TAG = "TextHookClient"
        const val DEFAULT_ADDRESS = "ws://127.0.0.1:6677"
        const val RETRY_MIN_MS = 1_000L
        const val RETRY_MAX_MS = 5_000L
    }
}
