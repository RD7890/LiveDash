package com.rohan.livedash

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.rohan.livedash.data.Session
import com.rohan.livedash.service.DashboardService
import com.rohan.livedash.service.OverlayService
import com.rohan.livedash.ui.screen.ModeSelectScreen
import com.rohan.livedash.ui.screen.NameScreen
import com.rohan.livedash.ui.screen.SenderScreen
import com.rohan.livedash.ui.screen.ViewerScreen
import com.rohan.livedash.ui.theme.Accent
import com.rohan.livedash.ui.theme.LiveDashTheme
import com.rohan.livedash.ui.theme.SurfaceCard
import com.rohan.livedash.ui.theme.TextMuted
import com.rohan.livedash.ui.theme.TextPrimary
import com.rohan.livedash.ui.theme.TextSecondary

private sealed class Screen {
    object ModeSelect : Screen()
    object Viewer : Screen()
    data class NameEntry(val session: Session) : Screen()
    data class Sender(val session: Session?, val name: String) : Screen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LiveDashTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var screen by remember { mutableStateOf<Screen>(Screen.ModeSelect) }
                    var showExitDialog by remember { mutableStateOf(false) }

                    BackHandler(enabled = screen is Screen.ModeSelect) { showExitDialog = true }

                    if (showExitDialog) {
                        AlertDialog(
                            onDismissRequest = { showExitDialog = false },
                            containerColor = SurfaceCard,
                            title = { Text("Exit LiveDash?", color = TextPrimary, fontWeight = FontWeight.Bold) },
                            text = { Text("All services will be stopped and the app will close.", color = TextSecondary) },
                            confirmButton = {
                                TextButton(onClick = {
                                    startService(Intent(this@MainActivity, DashboardService::class.java).apply { action = DashboardService.ACTION_STOP })
                                    startService(Intent(this@MainActivity, OverlayService::class.java).apply { action = OverlayService.ACTION_STOP })
                                    finishAndRemoveTask()
                                }) { Text("Exit", color = Accent) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showExitDialog = false }) {
                                    Text("Cancel", color = TextMuted)
                                }
                            }
                        )
                    }

                    when (val s = screen) {
                        is Screen.ModeSelect -> ModeSelectScreen(
                            onViewerSelected = { screen = Screen.Viewer },
                            onSenderSelected = { screen = Screen.NameEntry(Session("", "", 0, "")) }
                        )
                        is Screen.Viewer -> ViewerScreen(onBack = { screen = Screen.ModeSelect })
                        is Screen.NameEntry -> NameScreen(
                            onContinue = { name, session -> screen = Screen.Sender(session, name) },
                            onBack = { screen = Screen.ModeSelect }
                        )
                        is Screen.Sender -> SenderScreen(
                            initialSession = s.session,
                            senderName = s.name,
                            onBack = { screen = Screen.ModeSelect }
                        )
                    }
                }
            }
        }
    }
}
