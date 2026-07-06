package com.rohan.livedash.service

import com.rohan.livedash.data.Message
import com.rohan.livedash.data.ScreenshotEntry
import com.rohan.livedash.data.SenderInfo
import com.rohan.livedash.network.DashboardServer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.ConcurrentHashMap

data class VideoChunk(val data: ByteArray, val flags: Int, val ts: Long) {
    override fun equals(other: Any?) = false
    override fun hashCode() = System.identityHashCode(this)
}

object DashboardState {
    val serverRunning = MutableStateFlow(false)
    val screenshots = MutableStateFlow<List<ScreenshotEntry>>(emptyList())
    val messages = MutableStateFlow<List<Message>>(emptyList())
    val senders = MutableStateFlow<List<SenderInfo>>(emptyList())
    val perSenderMessages = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    var server: DashboardServer? = null

    private val videoStreams = ConcurrentHashMap<String, MutableSharedFlow<VideoChunk>>()

    fun getOrCreateVideoStream(senderId: String): SharedFlow<VideoChunk> =
        videoStreams.getOrPut(senderId) {
            MutableSharedFlow(replay = 30, extraBufferCapacity = 64)
        }

    fun emitVideoFrame(senderId: String, data: ByteArray, flags: Int) {
        videoStreams[senderId]?.tryEmit(VideoChunk(data, flags, System.currentTimeMillis()))
    }

    fun addScreenshot(entry: ScreenshotEntry) {
        val current = screenshots.value.toMutableList()
        current.add(0, entry)
        if (current.size > 40) current.removeAt(current.size - 1)
        screenshots.value = current
    }

    fun addMessage(msg: Message) {
        val current = messages.value.toMutableList()
        current.add(msg)
        if (current.size > 200) current.removeAt(0)
        messages.value = current

        if (msg.senderId.isNotEmpty()) {
            val map = perSenderMessages.value.toMutableMap()
            val list = (map[msg.senderId] ?: emptyList()).toMutableList()
            list.add(msg)
            if (list.size > 100) list.removeAt(0)
            map[msg.senderId] = list
            perSenderMessages.value = map
        }
    }

    fun reset() {
        serverRunning.value = false
        screenshots.value = emptyList()
        messages.value = emptyList()
        senders.value = emptyList()
        perSenderMessages.value = emptyMap()
        videoStreams.clear()
        server = null
    }
}
