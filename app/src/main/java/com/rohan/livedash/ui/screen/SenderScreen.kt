package com.rohan.livedash.ui.screen

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rohan.livedash.data.Message
import com.rohan.livedash.data.Session
import com.rohan.livedash.service.OverlayService
import com.rohan.livedash.ui.theme.*
import com.rohan.livedash.viewmodel.AppViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SenderScreen(
    vm: AppViewModel = viewModel(),
    initialSession: Session? = null,
    senderName: String = "Sender",
    onBack: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val connected by vm.senderConnected.collectAsState()
    val chatMessages by vm.senderMessages.collectAsState()

    var serverIp by remember { mutableStateOf(initialSession?.hostIp ?: "192.168.43.1") }
    var port by remember { mutableStateOf(initialSession?.port?.toString() ?: "8765") }
    var chatInput by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<Message?>(null) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var showExitDialog by remember { mutableStateOf(false) }

    val projManager = remember { ctx.getSystemService(MediaProjectionManager::class.java) }

    BackHandler { showExitDialog = true }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            containerColor = SurfaceCard,
            title = { Text("Leave?", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = { Text(
                if (connected) "This will stop the overlay and disconnect."
                else "Go back to mode selection?",
                color = TextSecondary
            ) },
            confirmButton = {
                TextButton(onClick = {
                    if (connected) ctx.startService(Intent(ctx, OverlayService::class.java).apply { action = OverlayService.ACTION_STOP })
                    showExitDialog = false; onBack()
                }) { Text(if (connected) "Stop & Leave" else "Leave", color = Rose) }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) { Text("Cancel", color = TextMuted) }
            }
        )
    }

    val captureResultLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ctx.startForegroundService(
                Intent(ctx, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_START
                    putExtra(OverlayService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(OverlayService.EXTRA_DATA, result.data)
                    putExtra(OverlayService.EXTRA_SERVER_IP, serverIp)
                    putExtra(OverlayService.EXTRA_SERVER_PORT, port.toIntOrNull() ?: 8765)
                    putExtra(OverlayService.EXTRA_SENDER_NAME, senderName)
                }
            )
        }
    }

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = { Text("Sender — $senderName", style = MaterialTheme.typography.titleLarge, color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = { showExitDialog = true }) {
                        Icon(Icons.Default.ArrowBack, null, tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // Status
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (connected) Emerald.copy(0.10f) else SurfaceCard,
                border = BorderStroke(1.dp, if (connected) Emerald.copy(0.35f) else OutlineColor)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(Modifier.size(9.dp).background(if (connected) Emerald else TextMuted, CircleShape))
                    Text(
                        if (connected) "Connected to Dashboard" else "Not connected",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (connected) Emerald else TextSecondary
                    )
                }
            }

            // Connection config
            Surface(shape = RoundedCornerShape(14.dp), color = SurfaceCard, border = BorderStroke(1.dp, OutlineColor)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Connection", style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    if (initialSession != null) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.QrCode2, null, tint = Emerald, modifier = Modifier.size(18.dp))
                            Text("Paired via QR: ${initialSession.hostIp}:${initialSession.port}", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        }
                    } else {
                        LdField("Viewer IP") {
                            OutlinedTextField(
                                value = serverIp, onValueChange = { serverIp = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("192.168.43.1", color = TextMuted) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                colors = ldColors()
                            )
                        }
                        LdField("Port") {
                            OutlinedTextField(
                                value = port, onValueChange = { port = it },
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                colors = ldColors()
                            )
                        }
                    }
                }
            }

            // Permissions
            Surface(shape = RoundedCornerShape(14.dp), color = SurfaceCard, border = BorderStroke(1.dp, OutlineColor)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Permissions", style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            if (overlayGranted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            null,
                            tint = if (overlayGranted) Emerald else TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                        Text("Draw Over Other Apps", style = MaterialTheme.typography.bodyMedium, color = TextPrimary, modifier = Modifier.weight(1f))
                        if (!overlayGranted) {
                            TextButton(onClick = {
                                ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                            }) { Text("Grant", color = Accent) }
                        }
                    }
                }
            }

            // Start
            Button(
                onClick = {
                    overlayGranted = Settings.canDrawOverlays(ctx)
                    if (!overlayGranted) {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                        return@Button
                    }
                    captureResultLauncher.launch(projManager.createScreenCaptureIntent())
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) {
                Icon(Icons.Default.Launch, null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text("Start Overlay & Connect", style = MaterialTheme.typography.titleMedium, color = Color.White)
            }

            if (connected) {
                OutlinedButton(
                    onClick = {
                        ctx.startService(Intent(ctx, OverlayService::class.java).apply { action = OverlayService.ACTION_STOP })
                    },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Rose.copy(0.35f))
                ) {
                    Icon(Icons.Default.Stop, null, tint = Rose, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Stop Overlay", color = Rose)
                }
            }

            // In-app chat (when overlay active)
            AnimatedVisibility(visible = connected) {
                Surface(shape = RoundedCornerShape(14.dp), color = SurfaceCard, border = BorderStroke(1.dp, OutlineColor)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Chat", style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.SemiBold)

                        if (replyTo != null) {
                            Row(
                                Modifier.fillMaxWidth().background(SurfaceElevated, RoundedCornerShape(8.dp)).padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.Reply, null, tint = Accent, modifier = Modifier.size(14.dp))
                                Text(replyTo!!.text.take(60), style = MaterialTheme.typography.bodySmall, color = TextSecondary, modifier = Modifier.weight(1f))
                                IconButton(onClick = { replyTo = null }, modifier = Modifier.size(20.dp)) {
                                    Icon(Icons.Default.Close, null, tint = TextMuted, modifier = Modifier.size(12.dp))
                                }
                            }
                        }

                        Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            chatMessages.takeLast(30).forEach { msg ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (msg.outgoing) AccentContainer else SurfaceElevated,
                                    modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { replyTo = msg })
                                ) {
                                    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                        if (msg.replyToText != null)
                                            Text(
                                                msg.replyToText, style = MaterialTheme.typography.bodySmall,
                                                color = TextMuted, maxLines = 1,
                                                modifier = Modifier.padding(bottom = 2.dp)
                                            )
                                        Text(
                                            "${if (msg.outgoing) "You" else msg.senderName.ifEmpty { "Dashboard" }}: ${msg.text}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (msg.outgoing) Accent else TextSecondary
                                        )
                                    }
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = chatInput, onValueChange = { chatInput = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("Message...", color = TextMuted) },
                                shape = RoundedCornerShape(22.dp),
                                singleLine = true,
                                colors = ldColors()
                            )
                            IconButton(
                                onClick = {
                                    if (chatInput.isNotBlank()) {
                                        vm.sendSenderChat(chatInput, replyTo?.id, replyTo?.text)
                                        chatInput = ""; replyTo = null
                                    }
                                },
                                modifier = Modifier.size(44.dp).background(Accent, CircleShape)
                            ) { Icon(Icons.Default.Send, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LdField(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = TextSecondary)
        content()
    }
}

@Composable
private fun ldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Accent, unfocusedBorderColor = OutlineColor,
    focusedContainerColor = SurfaceElevated, unfocusedContainerColor = SurfaceElevated,
    cursorColor = Accent, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary
)

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickable(onClick: () -> Unit, onLongClick: () -> Unit) =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)
