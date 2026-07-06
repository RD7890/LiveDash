package com.rohan.livedash.ui.screen

import android.content.Intent
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rohan.livedash.data.Message
import com.rohan.livedash.data.MessageType
import com.rohan.livedash.data.SenderInfo
import com.rohan.livedash.service.DashboardService
import com.rohan.livedash.service.DashboardState
import com.rohan.livedash.service.VideoChunk
import com.rohan.livedash.ui.components.QrCodeView
import com.rohan.livedash.ui.theme.*
import com.rohan.livedash.viewmodel.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(vm: AppViewModel = viewModel(), onBack: () -> Unit = {}) {
    val ctx = LocalContext.current
    val serverRunning by vm.serverRunning.collectAsState()
    val messages by vm.messages.collectAsState()
    val senders by vm.senders.collectAsState()
    val perSenderMessages by vm.perSenderMessages.collectAsState()
    val localIp = remember { vm.getLocalIp() }

    var chatText by remember { mutableStateOf("") }
    var activeTab by remember { mutableIntStateOf(0) }
    var selectedSender by remember { mutableStateOf<SenderInfo?>(null) }
    var replyTo by remember { mutableStateOf<Message?>(null) }

    BackHandler(enabled = selectedSender != null) { selectedSender = null }
    BackHandler(enabled = selectedSender == null) { onBack() }

    if (selectedSender != null) {
        val sender = selectedSender!!
        SenderDetailView(
            sender = sender,
            messages = perSenderMessages[sender.id] ?: emptyList(),
            onSendChat = { text, reply -> vm.sendViewerChatToSender(sender.id, text, reply?.id, reply?.text) },
            onBack = { selectedSender = null }
        )
        return
    }

    val qrContent = if (serverRunning) {
        com.rohan.livedash.data.Session(
            id = remember { UUID.randomUUID().toString() },
            hostIp = localIp,
            port = 8765,
            token = remember { (100000..999999).random().toString() }
        ).toQrJson()
    } else null

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(8.dp).background(if (serverRunning) Emerald else TextMuted, CircleShape))
                        Text("Dashboard", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = TextPrimary) }
                },
                actions = {
                    if (!serverRunning) {
                        Button(
                            onClick = {
                                ctx.startForegroundService(
                                    Intent(ctx, DashboardService::class.java).apply {
                                        putExtra(DashboardService.EXTRA_PORT, 8765)
                                    }
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.padding(end = 8.dp)
                        ) { Text("Start", style = MaterialTheme.typography.labelLarge) }
                    } else {
                        IconButton(onClick = {
                            ctx.startService(Intent(ctx, DashboardService::class.java).apply {
                                action = DashboardService.ACTION_STOP
                            })
                        }) { Icon(Icons.Default.Stop, null, tint = Rose) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = SurfaceCard, tonalElevation = 0.dp) {
                listOf("Live" to Icons.Default.Image, "Chat" to Icons.Default.Chat, "Pair" to Icons.Default.QrCode2, "Devices" to Icons.Default.Devices)
                    .forEachIndexed { i, (label, icon) ->
                        NavigationBarItem(
                            selected = activeTab == i, onClick = { activeTab = i },
                            icon = { Icon(icon, null) }, label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Accent, selectedTextColor = Accent,
                                indicatorColor = AccentContainer
                            )
                        )
                    }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (serverRunning) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    color = AccentContainer,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.WifiTethering, null, tint = Accent, modifier = Modifier.size(16.dp))
                        Column {
                            Text("Server IP", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            Text("$localIp:8765", style = MaterialTheme.typography.titleMedium, color = Accent, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.weight(1f))
                        Text("${senders.size} online", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    }
                }
            }

            when (activeTab) {
                0 -> LiveFeedsTab(senders) { selectedSender = it }
                1 -> ChatTab(messages, chatText, { chatText = it }, replyTo, { replyTo = null }) { text ->
                    if (text.isNotBlank()) {
                        vm.sendViewerChat(text, replyTo?.id, replyTo?.text)
                        chatText = ""; replyTo = null
                    }
                }
                2 -> PairTab(serverRunning, localIp, qrContent)
                3 -> DevicesTab(senders) { selectedSender = it }
            }
        }
    }
}

// ── Live Feeds ────────────────────────────────────────────────────────────────

@Composable
private fun LiveFeedsTab(senders: List<SenderInfo>, onSelect: (SenderInfo) -> Unit) {
    if (senders.isEmpty()) {
        EmptyState(Icons.Default.Screenshot, "No live feeds", "Start the server and connect senders")
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        items(senders) { sender ->
            Surface(
                shape = RoundedCornerShape(14.dp), color = SurfaceCard,
                modifier = Modifier.fillMaxWidth().clickable { onSelect(sender) }
            ) {
                Column {
                    Box(
                        Modifier.fillMaxWidth().height(190.dp)
                            .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                            .background(SurfaceElevated),
                        contentAlignment = Alignment.Center
                    ) {
                        VideoThumbnail(senderId = sender.id)
                    }
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(Modifier.size(7.dp).background(Emerald, CircleShape))
                        Text(sender.name, style = MaterialTheme.typography.titleSmall, color = TextPrimary, modifier = Modifier.weight(1f))
                        Text("LIVE", style = MaterialTheme.typography.labelSmall, color = Accent, fontWeight = FontWeight.Bold)
                        Icon(Icons.Default.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

// ── Pair (QR) Tab ─────────────────────────────────────────────────────────────

@Composable
private fun PairTab(serverRunning: Boolean, localIp: String, qrContent: String?) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (!serverRunning || qrContent == null) {
            Icon(Icons.Default.QrCode2, null, tint = TextMuted, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(12.dp))
            Text("Start the server to show the QR code", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
        } else {
            Text("Scan to connect", style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("Sender opens LiveDash and scans this", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Spacer(Modifier.height(24.dp))
            QrCodeView(content = qrContent, size = 220.dp)
            Spacer(Modifier.height(20.dp))
            Surface(shape = RoundedCornerShape(10.dp), color = SurfaceCard) {
                Text(
                    "$localIp : 8765",
                    style = MaterialTheme.typography.titleMedium,
                    color = Accent,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }
        }
    }
}

// ── MediaCodec decoder ─────────────────────────────────────────────────────────

private class DecoderHolder {
    @Volatile private var codec: MediaCodec? = null
    @Volatile private var started = false

    fun init(outputSurface: Surface) {
        release()
        try {
            val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080)
            dec.configure(fmt, outputSurface, null, 0)
            dec.start(); codec = dec; started = true
        } catch (e: Exception) { Log.e("DecoderHolder", "Init error", e) }
    }

    fun feed(data: ByteArray, flags: Int) {
        val dec = codec ?: return; if (!started) return
        try {
            val idx = dec.dequeueInputBuffer(8_000)
            if (idx >= 0) {
                val buf = dec.getInputBuffer(idx)!!
                buf.clear(); buf.put(data)
                dec.queueInputBuffer(idx, 0, data.size, System.nanoTime() / 1000, flags)
            }
            val info = MediaCodec.BufferInfo()
            var out = dec.dequeueOutputBuffer(info, 0)
            while (out >= 0) { dec.releaseOutputBuffer(out, true); out = dec.dequeueOutputBuffer(info, 0) }
        } catch (e: Exception) { Log.e("DecoderHolder", "Feed error", e) }
    }

    fun release() {
        started = false
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
    }
}

@Composable
private fun LiveVideoPlayer(senderId: String, modifier: Modifier = Modifier) {
    val frameFlow: SharedFlow<VideoChunk> = remember(senderId) {
        DashboardState.getOrCreateVideoStream(senderId) as SharedFlow<VideoChunk>
    }
    val decoderHolder = remember(senderId) { DecoderHolder() }
    var surfaceReady by remember { mutableStateOf(false) }

    DisposableEffect(senderId) { onDispose { decoderHolder.release() } }

    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            decoderHolder.init(Surface(st)); surfaceReady = true
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            surfaceReady = false; decoderHolder.release(); return true
                        }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        if (!surfaceReady) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(color = Accent, modifier = Modifier.size(28.dp))
                Text("Connecting", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
        }
    }

    LaunchedEffect(senderId, surfaceReady) {
        if (!surfaceReady) return@LaunchedEffect
        withContext(Dispatchers.IO) { frameFlow.collect { decoderHolder.feed(it.data, it.flags) } }
    }
}

@Composable
private fun VideoThumbnail(senderId: String) {
    LiveVideoPlayer(senderId = senderId, modifier = Modifier.fillMaxSize())
}

// ── Sender detail ─────────────────────────────────────────────────────────────

@Composable
private fun SenderDetailView(
    sender: SenderInfo,
    messages: List<Message>,
    onSendChat: (String, Message?) -> Unit,
    onBack: () -> Unit
) {
    var chatText by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<Message?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }

    Column(Modifier.fillMaxSize().background(Background)) {
        Surface(color = Background) {
            Row(
                Modifier.fillMaxWidth().padding(8.dp).statusBarsPadding(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = TextPrimary) }
                Box(Modifier.size(7.dp).background(Emerald, CircleShape))
                Text(sender.name, style = MaterialTheme.typography.titleLarge, color = TextPrimary, modifier = Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(6.dp), color = Color.Black.copy(0.5f)) {
                    Text("LIVE", style = MaterialTheme.typography.labelSmall, color = Accent,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
        }

        Box(
            Modifier.fillMaxWidth().weight(0.52f)
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
        ) {
            LiveVideoPlayer(senderId = sender.id, modifier = Modifier.fillMaxSize())
        }

        Text("Chat", style = MaterialTheme.typography.labelMedium, color = TextMuted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(0.48f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Box(Modifier.fillParentMaxSize(), Alignment.Center) {
                        Text("No messages yet", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                    }
                }
            }
            items(messages) { msg -> MessageBubble(msg, onLongPress = { replyTo = msg }) }
        }

        ChatInputBar(
            text = chatText,
            onTextChange = { chatText = it },
            replyTo = replyTo,
            onClearReply = { replyTo = null },
            onSend = { if (chatText.isNotBlank()) { onSendChat(chatText, replyTo); chatText = ""; replyTo = null } }
        )
    }
}

// ── Chat Tab ──────────────────────────────────────────────────────────────────

@Composable
private fun ChatTab(
    messages: List<Message>,
    input: String,
    onInput: (String) -> Unit,
    replyTo: Message?,
    onClearReply: () -> Unit,
    onSend: (String) -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Box(Modifier.fillParentMaxSize(), Alignment.Center) {
                        Text("No messages yet", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                    }
                }
            }
            items(messages) { msg -> MessageBubble(msg) }
        }
        ChatInputBar(input, onInput, replyTo, onClearReply, onSend = { onSend(input) })
    }
}

// ── Devices Tab ───────────────────────────────────────────────────────────────

@Composable
private fun DevicesTab(senders: List<SenderInfo>, onSelect: (SenderInfo) -> Unit) {
    if (senders.isEmpty()) {
        EmptyState(Icons.Default.DevicesOther, "No devices connected", "Senders appear here when connected")
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(senders) { sender ->
            Surface(
                shape = RoundedCornerShape(12.dp), color = SurfaceCard,
                border = BorderStroke(1.dp, OutlineColor),
                modifier = Modifier.fillMaxWidth().clickable { onSelect(sender) }
            ) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(Modifier.size(40.dp).background(AccentContainer, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.PhoneAndroid, null, tint = Accent, modifier = Modifier.size(20.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(sender.name, style = MaterialTheme.typography.titleSmall, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Connected ${formatTime(sender.connectedAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                    Box(Modifier.size(8.dp).background(Emerald, CircleShape))
                }
            }
        }
    }
}

// ── Shared UI components ──────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(msg: Message, onLongPress: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.outgoing) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 14.dp, topEnd = 14.dp,
                bottomStart = if (msg.outgoing) 14.dp else 4.dp,
                bottomEnd = if (msg.outgoing) 4.dp else 14.dp
            ),
            color = if (msg.outgoing) Accent else SurfaceElevated,
            modifier = Modifier.widthIn(max = 260.dp).combinedClickable(
                onClick = {}, onLongClick = { onLongPress?.invoke() }
            )
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (!msg.outgoing && msg.senderName.isNotEmpty())
                    Text(msg.senderName, style = MaterialTheme.typography.labelSmall, color = Emerald)
                if (msg.replyToText != null) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Black.copy(alpha = 0.20f),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                    ) {
                        Text(
                            msg.replyToText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (msg.outgoing) Color.White.copy(0.75f) else TextMuted,
                            maxLines = 2,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                if (msg.type == MessageType.TEXT) {
                    Text(msg.text, style = MaterialTheme.typography.bodyMedium,
                        color = if (msg.outgoing) Color.White else TextPrimary)
                }
                Text(formatTime(msg.timestamp), style = MaterialTheme.typography.labelSmall,
                    color = if (msg.outgoing) Color.White.copy(0.55f) else TextMuted,
                    modifier = Modifier.align(Alignment.End))
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    replyTo: Message?,
    onClearReply: () -> Unit,
    onSend: () -> Unit
) {
    Surface(color = SurfaceCard, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding()) {
            if (replyTo != null) {
                Row(
                    Modifier.fillMaxWidth().background(SurfaceElevated).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Reply, null, tint = Accent, modifier = Modifier.size(16.dp))
                    Text(
                        replyTo.text.take(60),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onClearReply, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, null, tint = TextMuted, modifier = Modifier.size(14.dp))
                    }
                }
            }
            Row(
                Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = text, onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message all senders...", color = TextMuted) },
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent, unfocusedBorderColor = OutlineColor,
                        focusedContainerColor = SurfaceElevated, unfocusedContainerColor = SurfaceElevated,
                        cursorColor = Accent, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary
                    ),
                    maxLines = 3
                )
                IconButton(
                    onClick = onSend,
                    modifier = Modifier.size(46.dp).background(Accent, CircleShape)
                ) {
                    Icon(Icons.Default.Send, null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = TextMuted, modifier = Modifier.size(52.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = TextSecondary)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextMuted)
        }
    }
}

private fun formatTime(ts: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
