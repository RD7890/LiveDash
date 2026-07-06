package com.rohan.livedash.data

import org.json.JSONObject

enum class MessageType { TEXT, SCREENSHOT }

data class Message(
    val id: String,
    val type: MessageType,
    val text: String = "",
    val imageBase64: String? = null,
    val timestamp: Long,
    val senderId: String,
    val senderName: String,
    val outgoing: Boolean,
    val replyToId: String? = null,
    val replyToText: String? = null,
    val replyToType: MessageType? = null
)

data class SenderInfo(
    val id: String,
    val name: String,
    val connectedAt: Long
)

data class Session(
    val id: String,
    val hostIp: String,
    val port: Int,
    val token: String,
    val version: Int = 1
) {
    fun toQrJson(): String =
        JSONObject()
            .put("sid", id)
            .put("ip", hostIp)
            .put("port", port)
            .put("tok", token)
            .put("v", version)
            .toString()

    companion object {
        fun fromQrJson(json: String): Session? = try {
            val j = JSONObject(json)
            Session(
                id = j.getString("sid"),
                hostIp = j.getString("ip"),
                port = j.getInt("port"),
                token = j.getString("tok"),
                version = j.optInt("v", 1)
            )
        } catch (e: Exception) {
            null
        }
    }
}

data class ScreenshotEntry(
    val id: String,
    val dataBase64: String,
    val timestamp: Long,
    val senderId: String,
    val senderName: String
)
