package com.example.access.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SyncStatusWidget(
    lastSyncTime: String,
    isSyncing: Boolean,
    onSyncClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "SyncRotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Rotation"
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = if (isSyncing) "Syncing with Google Drive..." else "Cloud Database Synced",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Text(
                    text = lastSyncTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }
            IconButton(onClick = onSyncClick, enabled = !isSyncing) {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = "Sync Now",
                    modifier = Modifier.rotate(if (isSyncing) rotation else 0f)
                )
            }
        }
    }
}
