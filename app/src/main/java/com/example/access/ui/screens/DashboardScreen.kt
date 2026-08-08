package com.example.access.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.example.access.util.SessionManager

@Composable
fun DashboardScreen(
    config: Config,
    memberCount: Int,
    syncStatus: SyncStatus,
    recentScans: List<RecentScan>,
    activeRole: String,
    onManualSync: () -> Unit,
    onRepairCloud: () -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary

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
                Text(
                    text = config.branding.organizationName,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    letterSpacing = (-1).sp
                )
                
                Spacer(modifier = Modifier.height(20.dp))
                
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DashboardPill(
                        label = "$memberCount Members",
                        icon = Icons.Default.People,
                        containerColor = Color.White.copy(alpha = 0.15f)
                    )
                    DashboardPill(
                        label = if (syncStatus == SyncStatus.HEALTHY) "Synced" else "Offline",
                        icon = if (syncStatus == SyncStatus.HEALTHY) Icons.Default.CloudDone else Icons.Default.CloudOff,
                        containerColor = if (syncStatus == SyncStatus.ERROR) Color.Black.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.15f)
                    )
                }
            }

            // FLOATING BRAND CIRCLE (The Production Touch)
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
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(54.dp)
                        )
                    } else {
                        Icon(Icons.Default.Business, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(32.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(40.dp))

        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            
            // ROLE-SPECIFIC CAPABILITY GUIDE
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, primaryColor.copy(alpha = 0.1f))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, null, tint = primaryColor, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "${activeRole.uppercase()} HUB",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Black,
                            color = primaryColor
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    val guideText = when(activeRole) {
                        SessionManager.ROLE_OWNER -> "Full system control active. Manage branding and global keys."
                        SessionManager.ROLE_ADMIN -> "Staff management active. Issue passes and bulk import data."
                        else -> "Scanning station active. Verify incoming digital passes."
                    }
                    Text(guideText, style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action Card
            Surface(
                onClick = onManualSync,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = Color.White,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEEEEEE)),
                shadowElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(40.dp).background(primaryColor.copy(alpha = 0.1f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null, tint = primaryColor, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Cloud Database", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text("Last check: moments ago", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
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
        shape = CircleShape
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
fun ActivityItem(scan: RecentScan) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(if (scan.isGranted) Color(0xFFE8F5E9) else Color(0xFFFFEBEE), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (scan.isGranted) Icons.Default.Check else Icons.Default.Close,
                contentDescription = null,
                tint = if (scan.isGranted) Color(0xFF4CAF50) else Color(0xFFF44336),
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(scan.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(if (scan.isGranted) "Authorized Entry" else "Access Denied", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }
        Text(scan.time, style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
    }
}
