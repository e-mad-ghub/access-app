package com.example.access.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import com.example.access.BillingViewModel
import com.example.access.SyncStatus
import com.example.access.data.Config
import com.example.access.data.RecentScan
import com.example.access.ui.components.ManagementSecretHandler
import com.example.access.ui.components.PasswordElevationDialog
import com.example.access.ui.screens.*
import com.example.access.util.SessionManager
import kotlinx.coroutines.launch

@Composable
fun MainAppNavigation(
    sessionManager: SessionManager,
    currentConfig: Config,
    memberCount: Int,
    syncStatus: SyncStatus,
    syncMessage: String?,
    recentScans: List<RecentScan>,
    onManualSync: () -> Unit,
    onRepairCloud: () -> Unit,
    onConfigUpdated: (Config) -> Unit,
    onLeaveOrganization: (Boolean) -> Unit,
    scannerViewModel: MainScannerViewModel,
    billingViewModel: BillingViewModel,
    configFileId: String
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var sessionKey by remember { mutableIntStateOf(0) }
    var showSecretDialog by remember { mutableStateOf(false) }
    val activeRole by sessionManager.activeRole.collectAsState()
    val scope = rememberCoroutineScope()

    val tabs = remember(activeRole) {
        val list = mutableListOf(
            TabItem("Dashboard", Icons.Default.Dashboard),
            TabItem("Scanner", Icons.Default.QrCodeScanner)
        )
        list.add(TabItem("Settings", Icons.Default.Settings))

        if (activeRole != SessionManager.ROLE_SCANNER) {
            list.add(TabItem("Members", Icons.Default.Group))
        }
        if (activeRole == SessionManager.ROLE_OWNER) {
            list.add(TabItem("Owner", Icons.Default.VpnKey))
        }
        list
    }
    val pagerState = rememberPagerState(pageCount = { tabs.size })

    fun resetToDashboard() {
        sessionKey++
        selectedTab = 0
        scope.launch {
            if (tabs.isNotEmpty()) {
                pagerState.scrollToPage(0)
            }
        }
    }

    LaunchedEffect(activeRole, tabs.size) {
        selectedTab = 0
        if (pagerState.currentPage != 0) {
            pagerState.scrollToPage(0)
        }
    }

    LaunchedEffect(pagerState.currentPage, tabs.size) {
        if (tabs.isNotEmpty()) {
            selectedTab = pagerState.currentPage.coerceIn(0, tabs.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            // REDESIGN: No visible TopAppBar. Using ManagementSecretHandler for the banner.
            ManagementSecretHandler(
                sessionManager = sessionManager,
                onSessionChanged = { resetToDashboard() },
                onRequestElevation = { showSecretDialog = true }
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
                        onClick = {
                            selectedTab = index
                            scope.launch { pagerState.animateScrollToPage(index) }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.padding(innerPadding)
        ) { page ->
            val tab = tabs.getOrNull(page) ?: tabs.first()
            key(sessionKey, tab.title) {
                TabPageContent(
                    tabTitle = tab.title,
                    currentConfig = currentConfig,
                    memberCount = memberCount,
                    syncStatus = syncStatus,
                    syncMessage = syncMessage,
                    recentScans = recentScans,
                    activeRole = activeRole,
                    onManualSync = onManualSync,
                    onRepairCloud = onRepairCloud,
                    onConfigUpdated = onConfigUpdated,
                    onLeaveOrganization = onLeaveOrganization,
                    scannerViewModel = scannerViewModel,
                    billingViewModel = billingViewModel,
                    configFileId = configFileId,
                    onTriggerSecret = { showSecretDialog = true }
                )
            }
        }
    }

    if (showSecretDialog) {
        PasswordElevationDialog(
            sessionManager = sessionManager,
            onDismiss = { showSecretDialog = false },
            onSessionChanged = { resetToDashboard() }
        )
    }
}

data class TabItem(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
private fun TabPageContent(
    tabTitle: String,
    currentConfig: Config,
    memberCount: Int,
    syncStatus: SyncStatus,
    syncMessage: String?,
    recentScans: List<RecentScan>,
    activeRole: String,
    onManualSync: () -> Unit,
    onRepairCloud: () -> Unit,
    onConfigUpdated: (Config) -> Unit,
    onLeaveOrganization: (Boolean) -> Unit,
    scannerViewModel: MainScannerViewModel,
    billingViewModel: BillingViewModel,
    configFileId: String,
    onTriggerSecret: () -> Unit
) {
    when (tabTitle) {
        "Dashboard" -> {
            DashboardScreen(
                config = currentConfig,
                memberCount = memberCount,
                syncStatus = syncStatus,
                syncMessage = syncMessage,
                recentScans = recentScans,
                activeRole = activeRole,
                onManualSync = onManualSync,
                onRepairCloud = onRepairCloud,
                onTriggerSecret = onTriggerSecret
            )
        }
        "Scanner" -> {
            KioskScannerScreen(
                viewModel = scannerViewModel
            )
        }
        "Members" -> {
            MemberManagementScreen(
                config = currentConfig,
                billingViewModel = billingViewModel,
                configFileId = configFileId,
                onConfigUpdated = onConfigUpdated
            )
        }
        "Settings" -> {
            AdminSettingsScreen(
                config = currentConfig,
                activeRole = activeRole,
                onLeaveOrganization = onLeaveOrganization
            )
        }
        "Owner" -> {
            OwnerDashboardScreen(
                config = currentConfig,
                billingViewModel = billingViewModel,
                configFileId = configFileId,
                onConfigUpdated = onConfigUpdated
            )
        }
    }
}
