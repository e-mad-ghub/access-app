package com.example.access.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.access.MainScannerViewModel
import com.example.access.SyncStatus
import com.example.access.data.Config
import com.example.access.data.RecentScan
import com.example.access.ui.components.PassTopBar
import com.example.access.ui.screens.*
import com.example.access.util.SessionManager

@Composable
fun MainAppNavigation(
    sessionManager: SessionManager,
    currentConfig: Config,
    memberCount: Int,
    syncStatus: SyncStatus,
    recentScans: List<RecentScan>,
    onManualSync: () -> Unit,
    onRepairCloud: () -> Unit,
    onConfigUpdated: (Config) -> Unit,
    scannerViewModel: MainScannerViewModel
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var sessionKey by remember { mutableIntStateOf(0) }
    val activeRole by sessionManager.activeRole.collectAsState()

    val tabs = remember(activeRole) {
        val list = mutableListOf(
            TabItem("Dashboard", Icons.Default.Dashboard),
            TabItem("Scanner", Icons.Default.QrCodeScanner)
        )
        // EVERYONE gets Settings (for Switching Hubs)
        list.add(TabItem("Settings", Icons.Default.Settings))

        if (activeRole != SessionManager.ROLE_SCANNER) {
            list.add(TabItem("Members", Icons.Default.Group))
        }
        if (activeRole == SessionManager.ROLE_OWNER) {
            list.add(TabItem("Owner", Icons.Default.VpnKey))
        }
        list
    }

    Scaffold(
        topBar = {
            PassTopBar(
                orgName = currentConfig.branding.organizationName,
                sessionManager = sessionManager,
                onSessionChanged = { 
                    sessionKey++ 
                    selectedTab = 0
                }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = Color.White,
                tonalElevation = 8.dp
            ) {
                tabs.forEachIndexed { index, tab ->
                    val isSelected = selectedTab == index
                    NavigationBarItem(
                        icon = { 
                            Icon(
                                tab.icon, 
                                contentDescription = tab.title,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray
                            ) 
                        },
                        label = { 
                            Text(
                                tab.title,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Black else FontWeight.Medium,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray
                            ) 
                        },
                        selected = isSelected,
                        onClick = { selectedTab = index },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        val safeIndex = if (selectedTab >= tabs.size) 0 else selectedTab
        val currentTabTitle = tabs[safeIndex].title

        Box(modifier = Modifier.padding(innerPadding)) {
            key(sessionKey, currentTabTitle) {
                when (currentTabTitle) {
                    "Dashboard" -> {
                        DashboardScreen(
                            config = currentConfig,
                            memberCount = memberCount,
                            syncStatus = syncStatus,
                            recentScans = recentScans,
                            activeRole = activeRole,
                            onManualSync = onManualSync,
                            onRepairCloud = onRepairCloud
                        )
                    }
                    "Scanner" -> {
                        KioskScannerScreen(
                            viewModel = scannerViewModel
                        )
                    }
                    "Members" -> {
                        MemberManagementScreen(config = currentConfig)
                    }
                    "Settings" -> {
                        AdminSettingsScreen(config = currentConfig, activeRole = activeRole)
                    }
                    "Owner" -> {
                        OwnerDashboardScreen(config = currentConfig, onConfigUpdated = onConfigUpdated)
                    }
                }
            }
        }
    }
}

data class TabItem(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
