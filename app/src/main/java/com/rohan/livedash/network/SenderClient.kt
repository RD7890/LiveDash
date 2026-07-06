package com.rohan.livedash.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class SenderClient(
    private val serverIp: String,
    private val port: Int,
    private val senderName: String,
    var onConnected: ((senderId: String) -> Unit)? = null,
    var onDisconnected: (() -> Unit)? = null,
    var onChatReceived: ((text: String, msgId: String, replyToId: String?) -> Unit)? = null,
    var onError: ((String) -> Unit)? = null
) : WebSocketClient(buildUri(serverIp, port, senderName)) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reconnectJob: Job? = null
    private val shouldReconnect = AtomicBoolean(true)
    private var mySenderId: String = ""

    override fun onOpen(handshake: ServerHandshake) {
        Log.d("SenderClient", "Connected to $uri")
        reconnectJob?.cancel()
    }

    override fun onMessage(message: String) {
        try {
            val json = JSONObject(message)
            when (json.optString("type")) {
                "ack" -> {
                    mySenderId = json.optString("id")
                    onConnected?.invoke(mySenderId)
                }
                "chat" -> {
                    val text = json.optString("text")
                    val msgId = json.optString("id", UUID.randomUUID().toString())
                    val replyTo = json.optString("replyToId").ifBlank { null }
                    onChatReceived?.invoke(text, msgId, replyTo)
                }
            }
        } catch (e: Exception) {
            Log.e("SenderClient", "Parse error", e)
        }
    }

    override fun onClose(code: Int, reason: String, remote: Boolean) {
        Log.d("SenderClient", "Disconnected: $reason (remote=$remote)")
        onDisconnected?.invoke()
        if (shouldReconnect.get() && remote) scheduleReconnect()
    }

    override fun onError(ex: Exception) {
        Log.e("SenderClient", "Error", ex)
        onError?.invoke(ex.message ?: "Unknown error")
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var delay = 3000L
            repeat(10) { attempt ->
                delay(delay)
                if (!shouldReconnect.get()) return@launch
                Log.d("SenderClient", "Reconnect attempt ${attempt + 1}")
                try {
                    val newClient = SenderClient(serverIp, port, senderName,
                        onConnected, onDisconnected, onChatReceived, onError)
                    newClient.connect()
                    return@launch
                } catch (e: Exception) {
                    Log.w("SenderClient", "Reconnect failed: ${e.message}")
                    delay = minOf(delay * 2, 30_000L)
                }
            }
        }
    }

    fun sendVideoFrame(b64: String, flags: Int) {
        if (!isOpen) return
        try {
            val json = JSONObject()
                .put("type", "video_frame")
                .put("data", b64)
                .put("flags", flags)
                .put("ts", System.currentTimeMillis())
            send(json.toString())
        } catch (e: Exception) {
            Log.e("SenderClient", "sendVideoFrame error", e)
        }
    }

    fun sendScreenshot(b64: String) {
        if (!isOpen) return
        try {
            val json = JSONObject()
                .put("type", "screenshot")
                .put("id", UUID.randomUUID().toString())
                .put("data", b64)
                .put("ts", System.currentTimeMillis())
            send(json.toString())
        } catch (e: Exception) {
            Log.e("SenderClient", "sendScreenshot error", e)
        }
    }

    fun sendChat(
        text: String,
        msgId: String = UUID.randomUUID().toString(),
        replyToId: String? = null,
        replyToText: String? = null
    ) {
        if (!isOpen) return
        try {
            val json = JSONObject()
                .put("type", "chat")
                .put("id", msgId)
                .put("text", text)
                .put("ts", System.currentTimeMillis())
            replyToId?.let { json.put("replyToId", it) }
            replyToText?.let { json.put("replyToText", it) }
            send(json.toString())
        } catch (e: Exception) {
            Log.e("SenderClient", "sendChat error", e)
        }
    }

    fun stopReconnect() {
        shouldReconnect.set(false)
        reconnectJob?.cancel()
    }

    companion object {
        fun buildUri(ip: String, port: Int, name: String): URI =
            URI("ws://$ip:$port/?role=sender&name=${name.encodeUrl()}")
    }
}

private fun String.encodeUrl(): String = java.net.URLEncoder.encode(this, "UTF-8")
