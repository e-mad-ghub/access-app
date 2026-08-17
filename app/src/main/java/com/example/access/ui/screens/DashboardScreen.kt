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
import com.example.access.ui.components.ProfessionalActionRow
import com.example.access.ui.components.ProfessionalPageHeader
import com.example.access.ui.components.ProfessionalScreen
import com.example.access.ui.components.ProfessionalSectionCard
import com.example.access.ui.components.ProfessionalStatusChip
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

    ProfessionalScreen(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp)) {
        ProfessionalPageHeader(
            title = config.branding.organizationName,
            subtitle = "Welcome back. Review access status and recent scanner activity.",
            modifier = Modifier.secretElevation(onTriggerSecret),
            action = {
                Surface(
                    modifier = Modifier.size(54.dp),
                    shape = CircleShape,
                    color = Color.White,
                    shadowElevation = 1.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (config.branding.logoFileId != null) {
                            AsyncImage(
                                model = "https://lh3.googleusercontent.com/u/0/d/${config.branding.logoFileId}",
                                contentDescription = null,
                                modifier = Modifier.size(34.dp).clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(Icons.Default.Business, null, tint = primaryColor, modifier = Modifier.size(26.dp))
                        }
                    }
                }
            }
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DashboardMetricCard(
                label = "Members",
                value = memberCount.toString(),
                icon = Icons.Default.People,
                modifier = Modifier.weight(1f)
            )
            DashboardMetricCard(
                label = "Database",
                value = when (syncStatus) {
                    SyncStatus.SYNCING -> "Syncing"
                    SyncStatus.HEALTHY -> "Synced"
                    SyncStatus.ERROR -> "Offline"
                },
                icon = if (syncStatus == SyncStatus.HEALTHY) Icons.Default.CloudDone else Icons.Default.CloudOff,
                modifier = Modifier.weight(1f)
            )
        }

        ProfessionalSectionCard(
            title = "System connection",
            subtitle = databaseDetail,
            icon = Icons.Default.Cloud
        ) {
            ProfessionalActionRow(
                icon = Icons.Default.Refresh,
                title = databaseTitle,
                body = "Tap to manually sync with the shared Google Drive database.",
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (syncStatus == SyncStatus.SYNCING) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            ProfessionalStatusChip(
                                text = when (syncStatus) {
                                    SyncStatus.SYNCING -> "SYNCING"
                                    SyncStatus.HEALTHY -> "HEALTHY"
                                    SyncStatus.ERROR -> "ERROR"
                                },
                                color = when (syncStatus) {
                                    SyncStatus.SYNCING -> MaterialTheme.colorScheme.primary
                                    SyncStatus.HEALTHY -> Color(0xFF039855)
                                    SyncStatus.ERROR -> MaterialTheme.colorScheme.error
                                }
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                },
                onClick = onManualSync
            )
        }

        ProfessionalSectionCard(
            title = "Live activity",
            subtitle = "Latest scanner results on this device.",
            icon = Icons.Default.Timeline
        ) {
            if (recentScans.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                    Text("No scans recorded yet.", color = Color(0xFF98A2B3), style = MaterialTheme.typography.bodySmall)
                }
            } else {
                recentScans.take(3).forEach { scan ->
                    ActivityItem(scan)
                }
            }
        }

        Spacer(modifier = Modifier.height(84.dp))
    }
}

@Composable
private fun DashboardMetricCard(label: String, value: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color.White,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFD0D5DD)),
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Color(0xFF111827))
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF667085))
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
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE4E7EC))
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
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
