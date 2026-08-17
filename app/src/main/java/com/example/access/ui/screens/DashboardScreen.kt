package com.example.access.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.access.SyncStatus
import com.example.access.data.Config
import com.example.access.data.RecentScan
import com.example.access.ui.components.secretElevation

@Composable
fun DashboardScreen(
    config: Config,
    memberCount: Int,
    syncStatus: SyncStatus,
    syncMessage: String?,
    recentScans: List<RecentScan>,
    activeRole: String,
    onManualSync: () -> Unit,
    onRepairCloud: () -> Unit,
    onTriggerSecret: () -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val databaseTitle = when (syncStatus) {
        SyncStatus.SYNCING -> "Updating organization database..."
        SyncStatus.HEALTHY -> "Organization database synced."
        SyncStatus.ERROR -> "Using last synced database"
    }
    val databaseDetail = syncMessage ?: when (syncStatus) {
        SyncStatus.SYNCING -> "Checking your shared Google Drive database."
        SyncStatus.HEALTHY -> "Your device is using the latest shared database."
        SyncStatus.ERROR -> "Changes may not upload until connection is restored."
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // --- PREMIUM HERO SECTION ---
        Box(modifier = Modifier.fillMaxWidth().height(220.dp)) {
            // Background Gradient
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(primaryColor, primaryColor.copy(alpha = 0.85f), Color(0xFF00363a))
                        )
                    )
            )
            
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .align(Alignment.TopStart)
            ) {
                Text(
                    text = "Welcome back,",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
                
                // SECRET GESTURE: Tap organization name 5 times to elevate
                Text(
                    text = config.branding.organizationName,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    letterSpacing = (-1).sp,
                    modifier = Modifier.secretElevation(onTriggerSecret)
                )
                
                Spacer(modifier = Modifier.height(20.dp))
                
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DashboardPill(
                        label = "$memberCount Members",
                        icon = Icons.Default.People,
                        containerColor = Color.White.copy(alpha = 0.15f)
                    )
                    DashboardPill(
                        label = if (syncStatus == SyncStatus.HEALTHY) "Synced" else if (syncStatus == SyncStatus.SYNCING) "Syncing" else "Offline",
                        icon = if (syncStatus == SyncStatus.HEALTHY) Icons.Default.CloudDone else Icons.Default.CloudOff,
                        containerColor = if (syncStatus == SyncStatus.ERROR) Color.Black.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.15f)
                    )
                }
            }

            // FLOATING BRAND CIRCLE
            Surface(
                modifier = Modifier
                    .size(86.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = (-24).dp, y = 32.dp),
                shape = CircleShape,
                color = Color.White,
                shadowElevation = 8.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (config.branding.logoFileId != null) {
                        AsyncImage(
                            model = "https://lh3.googleusercontent.com/u/0/d/${config.branding.logoFileId}",
                            contentDescription = null,
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(Icons.Default.Business, null, tint = primaryColor, modifier = Modifier.size(32.dp))
                    }
                }
            }
        }

        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Spacer(modifier = Modifier.height(60.dp))
            
            // Database Management - RESTORED FOR ALL ROLES
            Text("SYSTEM CONNECTION", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black, color = Color.Gray, letterSpacing = 1.sp)
            Spacer(Modifier.height(16.dp))
            OutlinedCard(
                onClick = onManualSync,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(color = primaryColor.copy(alpha = 0.1f), shape = CircleShape, modifier = Modifier.size(44.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Refresh, null, tint = primaryColor) }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(databaseTitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text(databaseDetail, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                    if (syncStatus == SyncStatus.SYNCING) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.LightGray)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))

            // Recent Activity Section
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("LIVE ACTIVITY", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black, color = Color.Gray, letterSpacing = 1.sp)
                Spacer(Modifier.weight(1f))
                Box(modifier = Modifier.size(8.dp).background(Color(0xFF4CAF50), CircleShape))
            }
            
            Spacer(modifier = Modifier.height(16.dp))

            if (recentScans.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                    Text("No scans recorded yet.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                recentScans.take(3).forEach { scan ->
                    ActivityItem(scan)
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
            
            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

@Composable
fun DashboardPill(label: String, icon: ImageVector, containerColor: Color) {
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ActivityItem(scan: com.example.access.data.RecentScan) {
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = if (scan.isGranted) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                shape = CircleShape,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (scan.isGranted) Icons.Default.CheckCircle else Icons.Default.Cancel,
                        null,
                        tint = if (scan.isGranted) Color(0xFF4CAF50) else Color(0xFFD32F2F),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(scan.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text(if (scan.isGranted) "Entry Verified" else "Access Refused", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
            Text(scan.time, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}
