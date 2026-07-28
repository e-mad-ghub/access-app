package com.example.access.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.access.data.ScanResult
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanResultBottomSheet(
    result: ScanResult,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    var progress by remember { mutableStateOf(1f) }
    
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 2000, easing = LinearEasing),
        label = "Progress"
    )

    LaunchedEffect(Unit) {
        progress = 0f
        delay(2000)
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val statusText: String
            val statusColor: Color
            val memberName: String
            val memberIdLabel: String

            when (result) {
                is ScanResult.Granted -> {
                    statusText = "ACCESS GRANTED"
                    statusColor = Color.Green
                    memberName = result.name
                    memberIdLabel = "Digital Pass: Valid"
                }
                is ScanResult.Denied -> {
                    memberName = if (result.reason == "Paused") "Access Denied" else "Unknown"
                    memberIdLabel = if (result.reason == "Paused") "Member Directory: Paused" else "Invalid Pass"
                    statusText = if (result.reason == "Paused") "PAUSED" else "ACCESS DENIED"
                    statusColor = if (result.reason == "Paused") Color(0xFFFBC02D) else Color.Red
                }
            }

            Surface(
                color = statusColor.copy(alpha = 0.1f),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = statusText,
                    color = statusColor,
                    fontWeight = FontWeight.Black,
                    fontSize = 24.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(text = memberName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(text = memberIdLabel, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)

            Spacer(modifier = Modifier.height(32.dp))
            
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = statusColor
            )
            Text(
                text = "Resetting scanner...",
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
