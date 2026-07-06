package com.rohan.livedash.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rohan.livedash.data.Message
import com.rohan.livedash.data.MessageType
import com.rohan.livedash.service.DashboardState
import com.rohan.livedash.service.OverlayState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val serverRunning = DashboardState.serverRunning
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val screenshots = DashboardState.screenshots
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val messages = DashboardState.messages
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val senders = DashboardState.senders
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val perSenderMessages = DashboardState.perSenderMessages
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val senderConnected = OverlayState.connected
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val senderMessages = OverlayState.messages
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun getLocalIp(): String {
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { iface ->
                if (!iface.isLoopback && iface.isUp) {
                    iface.inetAddresses.toList().forEach { addr ->
                        if (!addr.isLoopbackAddress && addr is Inet4Address)
                            return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (_: Exception) {}
        return "0.0.0.0"
    }

    fun sendViewerChat(
        text: String,
        replyToId: String? = null,
        replyToText: String? = null
    ) {
        val msgId = UUID.randomUUID().toString()
        val msg = Message(msgId, MessageType.TEXT, text, null,
            System.currentTimeMillis(), "", "You", true, replyToId, replyToText)
        DashboardState.addMessage(msg)
        DashboardState.server?.sendChatToSenders(text, msgId)
    }

    fun sendViewerChatToSender(
        senderId: String,
        text: String,
        replyToId: String? = null,
        replyToText: String? = null
    ) {
        val msgId = UUID.randomUUID().toString()
        val msg = Message(msgId, MessageType.TEXT, text, null,
            System.currentTimeMillis(), senderId, "You", true, replyToId, replyToText)
        DashboardState.addMessage(msg)
        DashboardState.server?.sendChatToSender(senderId, text, msgId)
    }

    fun sendSenderChat(
        text: String,
        replyToId: String? = null,
        replyToText: String? = null
    ) {
        val msgId = UUID.randomUUID().toString()
        val msg = Message(msgId, MessageType.TEXT, text, null,
            System.currentTimeMillis(), "", "You", true, replyToId, replyToText)
        OverlayState.addMessage(msg)
        OverlayState.client?.sendChat(text, msgId, replyToId, replyToText)
    }
}
