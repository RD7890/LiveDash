package com.rohan.livedash.network

import android.util.Base64
import android.util.Log
import com.rohan.livedash.data.Message
import com.rohan.livedash.data.MessageType
import com.rohan.livedash.data.ScreenshotEntry
import com.rohan.livedash.data.SenderInfo
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DashboardServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {

    private val viewers = ConcurrentHashMap<WebSocket, String>()
    private val senders = ConcurrentHashMap<WebSocket, SenderInfo>()

    var onMessage: ((Message) -> Unit)? = null
    var onScreenshot: ((ScreenshotEntry) -> Unit)? = null
    var onVideoFrame: ((senderId: String, data: ByteArray, flags: Int) -> Unit)? = null
    var onSendersChanged: ((List<SenderInfo>) -> Unit)? = null

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val uri = handshake.resourceDescriptor ?: ""
        val role = parseParam(uri, "role") ?: "viewer"
        val name = parseParam(uri, "name") ?: "Unknown"
        if (role == "sender") {
            val info = SenderInfo(UUID.randomUUID().toString(), name, System.currentTimeMillis())
            senders[conn] = info
            broadcastSenderList()
            conn.send(JSONObject().put("type", "ack").put("id", info.id).toString())
            Log.d("DashboardServer", "Sender connected: $name id=${info.id}")
        } else {
            viewers[conn] = UUID.randomUUID().toString()
            conn.send(buildSendersJson())
            Log.d("DashboardServer", "Viewer connected")
        }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        viewers.remove(conn)
        if (senders.remove(conn) != null) broadcastSenderList()
    }

    override fun onMessage(conn: WebSocket, raw: String) {
        try {
            val json = JSONObject(raw)
            when (json.optString("type")) {
                "video_frame" -> {
                    val info = senders[conn] ?: return
                    val b64 = json.optString("data")
                    val flags = json.optInt("flags", 0)
                    if (b64.isNotEmpty()) {
                        val bytes = Base64.decode(b64, Base64.NO_WRAP)
                        onVideoFrame?.invoke(info.id, bytes, flags)
                    }
                }
                "screenshot" -> {
                    val info = senders[conn] ?: return
                    val entry = ScreenshotEntry(
                        id = UUID.randomUUID().toString(),
                        dataBase64 = json.optString("data"),
                        timestamp = json.optLong("ts", System.currentTimeMillis()),
                        senderId = info.id,
                        senderName = info.name
                    )
                    onScreenshot?.invoke(entry)
                    broadcastToViewers(raw)
                }
                "chat" -> {
                    val info = senders[conn]
                    val isSender = info != null
                    val targetSenderId = json.optString("targetSenderId", "")
                    val msg = Message(
                        id = json.optString("id", UUID.randomUUID().toString()),
                        type = MessageType.TEXT,
                        text = json.optString("text"),
                        timestamp = json.optLong("ts", System.currentTimeMillis()),
                        senderId = info?.id ?: "",
                        senderName = if (isSender) info!!.name else "Dashboard",
                        outgoing = false,
                        replyToId = json.optString("replyToId").ifBlank { null },
                        replyToText = json.optString("replyToText").ifBlank { null }
                    )
                    onMessage?.invoke(msg)
                    if (isSender) {
                        broadcastToViewers(raw)
                    } else if (targetSenderId.isNotEmpty()) {
                        val target = senders.entries.firstOrNull { it.value.id == targetSenderId }?.key
                        target?.takeIf { it.isOpen }?.send(raw)
                    } else {
                        broadcastToSenders(raw)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DashboardServer", "Parse error", e)
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e("DashboardServer", "WS error", ex)
    }

    override fun onStart() {
        Log.d("DashboardServer", "Server started on port $port")
        connectionLostTimeout = 30
    }

    fun sendChatToSenders(text: String, msgId: String = UUID.randomUUID().toString()) {
        val json = JSONObject()
            .put("type", "chat")
            .put("id", msgId)
            .put("text", text)
            .put("ts", System.currentTimeMillis())
            .toString()
        broadcastToSenders(json)
    }

    fun sendChatToSender(senderId: String, text: String, msgId: String = UUID.randomUUID().toString()) {
        val conn = senders.entries.firstOrNull { it.value.id == senderId }?.key ?: return
        if (!conn.isOpen) return
        val json = JSONObject()
            .put("type", "chat")
            .put("id", msgId)
            .put("text", text)
            .put("ts", System.currentTimeMillis())
            .toString()
        conn.send(json)
    }

    private fun broadcastToViewers(msg: String) =
        viewers.keys.filter { it.isOpen }.forEach { it.send(msg) }

    private fun broadcastToSenders(msg: String) =
        senders.keys.filter { it.isOpen }.forEach { it.send(msg) }

    private fun broadcastSenderList() {
        val json = buildSendersJson()
        viewers.keys.filter { it.isOpen }.forEach { it.send(json) }
        onSendersChanged?.invoke(senders.values.toList())
    }

    private fun buildSendersJson(): String {
        val arr = org.json.JSONArray()
        senders.values.forEach { s ->
            arr.put(JSONObject().put("id", s.id).put("name", s.name).put("ts", s.connectedAt))
        }
        return JSONObject().put("type", "senders").put("list", arr).toString()
    }

    private fun parseParam(uri: String, key: String): String? {
        val query = uri.substringAfter("?", "")
        return query.split("&").firstOrNull { it.startsWith("$key=") }
            ?.substringAfter("=")?.ifBlank { null }
    }
}
