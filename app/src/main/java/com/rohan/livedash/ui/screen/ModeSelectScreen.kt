package com.rohan.livedash.ui.screen

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.rohan.livedash.ui.theme.*

@Composable
fun ModeSelectScreen(
    onViewerSelected: () -> Unit,
    onSenderSelected: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "bg")
    val animOffset by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(8000, easing = LinearEasing)), label = "off"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Accent.copy(alpha = 0.10f), Color.Transparent),
                        center = Offset(size.width * 0.15f, size.height * 0.18f + animOffset * 40f),
                        radius = size.width * 0.55f
                    )
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Emerald.copy(alpha = 0.06f), Color.Transparent),
                        center = Offset(size.width * 0.85f, size.height * 0.72f - animOffset * 30f),
                        radius = size.width * 0.45f
                    )
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(Modifier.height(60.dp))

            Box(
                modifier = Modifier
                    .size(76.dp)
                    .background(Accent, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.ScreenShare, null, tint = Color.White, modifier = Modifier.size(38.dp))
            }

            Spacer(Modifier.height(20.dp))

            Text(
                "LiveDash",
                style = MaterialTheme.typography.headlineLarge,
                color = TextPrimary,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Offline local communication\nover your hotspot",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(44.dp))

            ModeCard(
                icon = Icons.Default.Dashboard,
                title = "Viewer",
                description = "Host the session. Enable hotspot, show QR, receive live screens and chat.",
                accent = Accent,
                onClick = onViewerSelected
            )

            Spacer(Modifier.height(14.dp))

            ModeCard(
                icon = Icons.Default.PhoneAndroid,
                title = "Sender",
                description = "Join via QR. Floating overlay lets you screenshot and chat from any app.",
                accent = Emerald,
                onClick = onSenderSelected
            )

            Spacer(Modifier.height(36.dp))

            Surface(
                shape = RoundedCornerShape(50),
                color = SurfaceBorder,
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.WifiTethering, null, tint = Amber, modifier = Modifier.size(13.dp))
                    Text(
                        "No internet — works on local hotspot only",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeCard(
    icon: ImageVector,
    title: String,
    description: String,
    accent: Color,
    onClick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "scale")

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clickable(
                onClick = onClick,
                onClickLabel = title,
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            ),
        shape = RoundedCornerShape(18.dp),
        color = SurfaceCard,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f))
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                Modifier
                    .size(50.dp)
                    .background(accent.copy(alpha = 0.13f), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(24.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
            Icon(Icons.Default.ChevronRight, null, tint = accent.copy(alpha = 0.5f))
        }
    }
}
