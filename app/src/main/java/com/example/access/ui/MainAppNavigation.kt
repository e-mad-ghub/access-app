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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.access.MainScannerViewModel
import com.example.access.BillingViewModel
import com.example.access.SyncStatus
import com.example.access.data.Config
import com.example.access.data.RecentScan
import com.example.access.ui.components.ManagementSecretHandler
import com.example.access.ui.components.PasswordElevationDialog
import com.example.access.ui.components.AppOrientationDialog
import com.example.access.ui.components.TabGuideDialog
import com.example.access.ui.components.hasSeenAppOrientation
import com.example.access.ui.components.hasSeenTabGuide
import com.example.access.ui.components.markAppOrientationSeen
import com.example.access.ui.components.markTabGuideSeen
import com.example.access.ui.components.resetAppGuides
import com.example.access.ui.screens.*
import com.example.access.util.RevokeOrganizationAccessResult
import com.example.access.util.SessionManager
import kotlinx.coroutines.delay
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
    onRevokeAllUsersPermission: (RevokeOrganizationAccessResult) -> Unit,
    scannerViewModel: MainScannerViewModel,
    billingViewModel: BillingViewModel,
    configFileId: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }
    var sessionKey by remember { mutableIntStateOf(0) }
    var showSecretDialog by remember { mutableStateOf(false) }
    var visibleGuideTab by remember { mutableStateOf<String?>(null) }
    var showAppOrientation by remember { mutableStateOf(!hasSeenAppOrientation(context)) }
    val activeRole by sessionManager.activeRole.collectAsState()

    val tabs = remember(activeRole) {
        val list = mutableListOf(
            TabItem("dashboard", "Dashboard", Icons.Default.Dashboard),
            TabItem("scanner", "Scanner", Icons.Default.QrCodeScanner)
        )
        list.add(TabItem("settings", "Settings", Icons.Default.Settings))

        if (activeRole != SessionManager.ROLE_SCANNER) {
            list.add(TabItem("members", "Members", Icons.Default.Group))
        }
        if (activeRole == SessionManager.ROLE_OWNER) {
            list.add(TabItem("director", "Director", Icons.Default.VpnKey))
        }
        list
    }
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val currentTab = tabs.getOrNull(selectedTab) ?: tabs.firstOrNull()

    fun resetToDashboard() {
        sessionKey++
        selectedTab = 0
        scope.launch {
            if (tabs.isNotEmpty()) {
                pagerState.scrollToPage(0)
            }
        }
    }

    fun dismissTabGuide() {
        visibleGuideTab?.let { guideTab ->
            markTabGuideSeen(context, guideTab)
        }
        visibleGuideTab = null
    }

    fun finishAppOrientation() {
        markAppOrientationSeen(context)
        showAppOrientation = false
    }

    fun replayGuides() {
        resetAppGuides(context)
        visibleGuideTab = null
        showAppOrientation = true
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

    LaunchedEffect(currentTab?.id, showSecretDialog, showAppOrientation, pagerState.isScrollInProgress) {
        val tab = currentTab ?: return@LaunchedEffect
        if (showSecretDialog || showAppOrientation || visibleGuideTab != null || pagerState.isScrollInProgress) {
            return@LaunchedEffect
        }
        delay(400)
        if (!showSecretDialog && !showAppOrientation && !pagerState.isScrollInProgress && !hasSeenTabGuide(context, tab.id)) {
            visibleGuideTab = tab.id
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
            key(sessionKey, tab.id) {
                TabPageContent(
                    tabId = tab.id,
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
                    onRevokeAllUsersPermission = onRevokeAllUsersPermission,
                    scannerViewModel = scannerViewModel,
                    billingViewModel = billingViewModel,
                    configFileId = configFileId,
                    onTriggerSecret = { showSecretDialog = true },
                    onReplayGuides = { replayGuides() }
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

    if (showAppOrientation) {
        AppOrientationDialog(
            onFinished = { finishAppOrientation() }
        )
    }

    visibleGuideTab?.let { guideTab ->
        TabGuideDialog(
            tabId = guideTab,
            onDismiss = { dismissTabGuide() }
        )
    }
}

data class TabItem(val id: String, val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
private fun TabPageContent(
    tabId: String,
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
    onRevokeAllUsersPermission: (RevokeOrganizationAccessResult) -> Unit,
    scannerViewModel: MainScannerViewModel,
    billingViewModel: BillingViewModel,
    configFileId: String,
    onTriggerSecret: () -> Unit,
    onReplayGuides: () -> Unit
) {
    when (tabId) {
        "dashboard" -> {
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
        "scanner" -> {
            KioskScannerScreen(
                viewModel = scannerViewModel
            )
        }
        "members" -> {
            MemberManagementScreen(
                config = currentConfig,
                billingViewModel = billingViewModel,
                configFileId = configFileId,
                onConfigUpdated = onConfigUpdated
            )
        }
        "settings" -> {
            AdminSettingsScreen(
                config = currentConfig,
                activeRole = activeRole,
                onLeaveOrganization = onLeaveOrganization,
                onReplayGuides = onReplayGuides
            )
        }
        "director" -> {
            OwnerDashboardScreen(
                config = currentConfig,
                billingViewModel = billingViewModel,
                configFileId = configFileId,
                onConfigUpdated = onConfigUpdated,
                onRevokeAllUsersPermission = onRevokeAllUsersPermission
            )
        }
    }
}
