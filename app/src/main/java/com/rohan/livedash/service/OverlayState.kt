package com.rohan.livedash.service

import com.rohan.livedash.data.Message
import com.rohan.livedash.network.SenderClient
import kotlinx.coroutines.flow.MutableStateFlow

object OverlayState {
    val connected = MutableStateFlow(false)
    val messages = MutableStateFlow<List<Message>>(emptyList())
    var client: SenderClient? = null

    fun addMessage(msg: Message) {
        val current = messages.value.toMutableList()
        current.add(msg)
        if (current.size > 100) current.removeAt(0)
        messages.value = current
    }

    fun reset() {
        connected.value = false
        messages.value = emptyList()
        client = null
    }
}
