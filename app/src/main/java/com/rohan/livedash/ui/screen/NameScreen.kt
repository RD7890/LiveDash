package com.rohan.livedash.ui.screen

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.rohan.livedash.data.Session
import com.rohan.livedash.data.SessionStore
import com.rohan.livedash.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NameScreen(
    onContinue: (name: String, session: Session?) -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val savedName by SessionStore.senderName(ctx).collectAsState(initial = "")
    var name by remember { mutableStateOf("") }
    var scannedSession by remember { mutableStateOf<Session?>(null) }
    var errorMsg by remember { mutableStateOf("") }

    LaunchedEffect(savedName) {
        if (name.isBlank() && savedName.isNotBlank()) name = savedName
        else if (name.isBlank()) name = "Phone ${Build.MODEL}"
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val content = result.contents
        if (content != null) {
            val session = Session.fromQrJson(content)
            if (session != null) {
                scannedSession = session
                errorMsg = ""
            } else {
                errorMsg = "Invalid QR code — scan the LiveDash Viewer QR"
            }
        }
    }

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = { Text("Enter Your Name", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            Text(
                "How should the Viewer identify you?",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Your display name", color = TextMuted) },
                leadingIcon = { Icon(Icons.Default.PersonOutline, null, tint = TextMuted) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (name.isNotBlank()) {
                        scope.launch { SessionStore.saveSenderName(ctx, name.trim()) }
                        onContinue(name.trim(), scannedSession)
                    }
                }),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = OutlineColor,
                    focusedContainerColor = SurfaceElevated,
                    unfocusedContainerColor = SurfaceElevated,
                    cursorColor = Accent,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            // QR scan status
            if (scannedSession != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Emerald.copy(alpha = 0.10f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Emerald.copy(0.3f))
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.QrCodeScanner, null, tint = Emerald, modifier = Modifier.size(20.dp))
                        Column {
                            Text("QR scanned", style = MaterialTheme.typography.labelMedium, color = Emerald)
                            Text(
                                "${scannedSession!!.hostIp}:${scannedSession!!.port}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { scannedSession = null }) {
                            Text("Clear", color = TextMuted)
                        }
                    }
                }
            } else {
                OutlinedButton(
                    onClick = {
                        scanLauncher.launch(ScanOptions().apply {
                            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            setBeepEnabled(false)
                            setPrompt("Scan the Viewer QR code")
                        })
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, OutlineColor)
                ) {
                    Icon(Icons.Default.QrCodeScanner, null, tint = Accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Scan Viewer QR", color = TextSecondary)
                }
            }

            if (errorMsg.isNotEmpty()) {
                Text(errorMsg, style = MaterialTheme.typography.bodySmall, color = Accent)
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isBlank()) { errorMsg = "Enter a display name"; return@Button }
                    scope.launch { SessionStore.saveSenderName(ctx, trimmed) }
                    onContinue(trimmed, scannedSession)
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) {
                Text("Continue", style = MaterialTheme.typography.titleMedium, color = androidx.compose.ui.graphics.Color.White)
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
