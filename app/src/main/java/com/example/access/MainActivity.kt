package com.example.access

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.access.data.AppDatabase
import com.example.access.data.Config
import com.example.access.ui.MainAppNavigation
import com.example.access.ui.theme.AccessTheme
import com.example.access.util.FREE_TIER_MEMBER_LIMIT
import com.example.access.util.DriveSyncManager
import com.example.access.util.FeedbackManager
import com.example.access.util.SessionManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.access.BillingViewModel

enum class SyncStatus { SYNCING, HEALTHY, ERROR }

class MainActivity : ComponentActivity() {
    private lateinit var sessionManager: SessionManager
    private lateinit var feedbackManager: FeedbackManager
    private lateinit var billingViewModel: BillingViewModel
    private lateinit var scannerViewModel: MainScannerViewModel

    private var currentConfig by mutableStateOf(Config())
    private var syncStatus by mutableStateOf(SyncStatus.SYNCING)
    private var syncMessage by mutableStateOf<String?>(null)
    private var isInitializing by mutableStateOf(true)

    private var configFileId: String? = null
    private var isSyncingAnonymously = false

    // --- CAMERA PERMISSION HANDLER ---
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d("MainActivity", "Camera permission granted")
        } else {
            Toast.makeText(this, "Camera permission is required to scan passes.", Toast.LENGTH_LONG).show()
        }
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                sessionManager.resetSession()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionManager = SessionManager(this)
        feedbackManager = FeedbackManager(this)
        
        // CHECK CAMERA PERMISSION ON START
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        }
        
        val storedConfigFileId = getSharedPreferences("easypass_prefs", MODE_PRIVATE).getString("config_file_id", null)
        configFileId = storedConfigFileId

        val intentUri = intent.data
        if (storedConfigFileId == null || (intentUri != null && intentUri.host == "join")) {
            val setupIntent = Intent(this, SetupWizardActivity::class.java).apply { data = intentUri }
            startActivity(setupIntent)
            finish()
            return
        }

        enableEdgeToEdge()
        scannerViewModel = ViewModelProvider(this)[MainScannerViewModel::class.java]
        billingViewModel = ViewModelProvider(this)[BillingViewModel::class.java]
        loadInitialData(storedConfigFileId)

        setContent {
            val memberCount by AppDatabase.getDatabase(this).memberDao().getMemberCount().observeAsState(0)
            val recentScans by scannerViewModel.liveScanHistory.collectAsState()
            val usableMemberCount = if (currentConfig.isPro) memberCount else memberCount.coerceAtMost(FREE_TIER_MEMBER_LIMIT)

            AccessTheme(branding = currentConfig.branding) {
                if (isInitializing) {
                    com.example.access.ui.components.InitialSplashLoader()
                } else {
                    MainAppNavigation(
                        sessionManager = sessionManager,
                        currentConfig = currentConfig,
                        memberCount = usableMemberCount,
                        syncStatus = syncStatus,
                        syncMessage = syncMessage,
                        recentScans = recentScans,
                        onManualSync = { configFileId?.let { loadConfig(it) } },
                        onRepairCloud = { /* Logic for repair */ },
                        onConfigUpdated = { 
                            currentConfig = it
                            scannerViewModel.updateConfig(it)
                            sessionManager.updateConfig(it)
                        },
                        onLeaveOrganization = { signOutGoogle ->
                            leaveOrganization(signOutGoogle)
                        },
                        scannerViewModel = scannerViewModel,
                        billingViewModel = billingViewModel,
                        configFileId = configFileId ?: "",
                    )
                }
            }
        }
    }

    private fun loadInitialData(configId: String) {
        lifecycleScope.launch {
            loadConfig(configId)
            delay(1500)
            isInitializing = false
        }
    }

    override fun onResume() {
        super.onResume()
        val id = configFileId ?: return
        if (isSyncingAnonymously && GoogleSignIn.getLastSignedInAccount(this) != null) {
            loadConfig(id)
        }
    }

    private fun loadConfig(configId: String) {
        val account = GoogleSignIn.getLastSignedInAccount(this)
        val sync = if (account != null) {
            val cred = GoogleAccountCredential.usingOAuth2(this, listOf(DriveScopes.DRIVE)).apply { selectedAccount = account.account }
            DriveSyncManager.createWithCredential(this, cred)
        } else {
            DriveSyncManager.createAnonymous(this)
        }
        isSyncingAnonymously = account == null

        syncStatus = SyncStatus.SYNCING
        syncMessage = null
        lifecycleScope.launch {
            try {
                val config = sync.downloadConfig(configId)
                if (config != null) {
                    currentConfig = config
                    scannerViewModel.updateConfig(config)
                    sessionManager.updateConfig(config)
                    val result = sync.downloadAndParseExcel(config.activeDatabaseId, config.isPro)
                    if (result != null) {
                        if (result.skippedRows.isNotEmpty()) {
                            Log.w("MainActivity", "Sync completed with ${result.skippedRows.size} skipped rows: ${result.skippedRows}")
                        }
                        if (result.duplicateRows.isNotEmpty()) {
                            Log.w("MainActivity", "Sync completed with ${result.duplicateRows.size} duplicate rows: ${result.duplicateRows}")
                        }
                        syncMessage = if (result.hasFreeLimitWarning) {
                            "Free plan limit reached. Only the first $FREE_TIER_MEMBER_LIMIT members are active on this device. Upgrade to Pro to unlock the full database."
                        } else {
                            null
                        }
                        syncStatus = SyncStatus.HEALTHY
                    } else {
                        syncMessage = "Using last synced database. Changes may not upload until connection is restored."
                        syncStatus = SyncStatus.ERROR
                    }
                } else {
                    syncMessage = "Using last synced database. Changes may not upload until connection is restored."
                    syncStatus = SyncStatus.ERROR
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to load config", e)
                syncMessage = "Using last synced database. Changes may not upload until connection is restored."
                syncStatus = SyncStatus.ERROR
            }
        }
    }

    private fun leaveOrganization(signOutGoogle: Boolean) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(this@MainActivity).memberDao().deleteAllMembers()
                getSharedPreferences("easypass_prefs", MODE_PRIVATE)
                    .edit()
                    .remove("config_file_id")
                    .apply()
            }

            configFileId = null
            sessionManager.resetSession()

            fun openSetup() {
                startActivity(Intent(this@MainActivity, SetupWizardActivity::class.java))
                finish()
            }

            if (signOutGoogle) {
                val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
                GoogleSignIn.getClient(this@MainActivity, gso).signOut().addOnCompleteListener {
                    openSetup()
                }
            } else {
                openSetup()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(screenOffReceiver)
    }
}
