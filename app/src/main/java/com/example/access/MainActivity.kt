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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.access.data.AppDatabase
import com.example.access.data.Config
import com.example.access.ui.MainAppNavigation
import com.example.access.ui.theme.AccessTheme
import com.example.access.ui.components.InitialSplashLoader
import com.example.access.util.FREE_TIER_MEMBER_LIMIT
import com.example.access.util.ConfigLoadResult
import com.example.access.util.DatabaseLoadResult
import com.example.access.util.DriveSyncManager
import com.example.access.util.FeedbackManager
import com.example.access.util.RevokeOrganizationAccessResult
import com.example.access.util.SessionManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.access.BillingViewModel

enum class SyncStatus { SYNCING, HEALTHY, ERROR }

private const val PREFS_NAME = "easypass_prefs"
private const val CONFIG_FILE_ID_KEY = "config_file_id"
private const val PENDING_REVOCATION_NEW_CONFIG_KEY = "pending_revocation_new_config_id"
private const val PENDING_REVOCATION_OLD_CONFIG_KEY = "pending_revocation_old_config_id"
private const val PENDING_REVOCATION_OLD_DATABASE_KEY = "pending_revocation_old_database_id"

sealed interface ConnectionUiState {
    data object Validating : ConnectionUiState
    data object Connected : ConnectionUiState
    data class Unavailable(
        val title: String,
        val message: String,
        val allowRetry: Boolean = true,
        val allowInvitationLink: Boolean = true
    ) : ConnectionUiState
    data class ConnectionRemoved(
        val title: String,
        val message: String
    ) : ConnectionUiState
}

class MainActivity : ComponentActivity() {
    private lateinit var sessionManager: SessionManager
    private lateinit var feedbackManager: FeedbackManager
    private lateinit var billingViewModel: BillingViewModel
    private lateinit var scannerViewModel: MainScannerViewModel

    private var currentConfig by mutableStateOf(Config())
    private var syncStatus by mutableStateOf(SyncStatus.SYNCING)
    private var syncMessage by mutableStateOf<String?>(null)
    private var connectionUiState by mutableStateOf<ConnectionUiState>(ConnectionUiState.Validating)

    private var configFileId: String? = null
    private var isSyncingAnonymously = false
    private var wasStopped = false
    private var hasStartedOnce = false
    private var isValidatingConnection = false

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
        
        val storedConfigFileId = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(CONFIG_FILE_ID_KEY, null)
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

        setContent {
            val memberCount by AppDatabase.getDatabase(this).memberDao().getMemberCount().observeAsState(0)
            val recentScans by scannerViewModel.liveScanHistory.collectAsState()
            val usableMemberCount = if (currentConfig.isPro) memberCount else memberCount.coerceAtMost(FREE_TIER_MEMBER_LIMIT)

            AccessTheme(branding = currentConfig.branding) {
                when (val state = connectionUiState) {
                    ConnectionUiState.Validating -> InitialSplashLoader()
                    ConnectionUiState.Connected -> {
                        MainAppNavigation(
                            sessionManager = sessionManager,
                            currentConfig = currentConfig,
                            memberCount = usableMemberCount,
                            syncStatus = syncStatus,
                            syncMessage = syncMessage,
                            recentScans = recentScans,
                            onManualSync = { configFileId?.let { validateConnection(it) } },
                            onRepairCloud = { /* Logic for repair */ },
                            onConfigUpdated = {
                                currentConfig = it
                                scannerViewModel.updateConfig(it)
                                sessionManager.updateConfig(it)
                            },
                            onLeaveOrganization = { signOutGoogle ->
                                leaveOrganization(signOutGoogle)
                            },
                            onRevokeAllUsersPermission = { result ->
                                handleRevocationResult(result)
                            },
                            scannerViewModel = scannerViewModel,
                            billingViewModel = billingViewModel,
                            configFileId = configFileId ?: "",
                        )
                    }
                    is ConnectionUiState.Unavailable -> {
                        ConnectionStateScreen(
                            title = state.title,
                            message = state.message,
                            primaryActionLabel = if (state.allowRetry) "Try again" else null,
                            onPrimaryAction = { configFileId?.let { validateConnection(it) } },
                            secondaryActionLabel = if (state.allowInvitationLink) "Use invitation link" else null,
                            onSecondaryAction = { openSetupForInvitation() }
                        )
                    }
                    is ConnectionUiState.ConnectionRemoved -> {
                        ConnectionStateScreen(
                            title = state.title,
                            message = state.message,
                            primaryActionLabel = null,
                            onPrimaryAction = null,
                            secondaryActionLabel = "Use invitation link",
                            onSecondaryAction = { openSetupForInvitation() }
                        )
                    }
                }
            }
        }

        validateConnection(storedConfigFileId)
    }

    override fun onStart() {
        super.onStart()
        if (hasStartedOnce && wasStopped) {
            configFileId?.let { validateConnection(it) }
        }
        hasStartedOnce = true
        wasStopped = false
    }

    override fun onStop() {
        super.onStop()
        wasStopped = true
    }

    override fun onResume() {
        super.onResume()
        val id = configFileId ?: return
        if (isSyncingAnonymously && GoogleSignIn.getLastSignedInAccount(this) != null) {
            validateConnection(id)
        }
    }

    private fun createSyncManager(): DriveSyncManager {
        val account = GoogleSignIn.getLastSignedInAccount(this)
        val sync = if (account != null) {
            val cred = GoogleAccountCredential.usingOAuth2(this, listOf(DriveScopes.DRIVE)).apply { selectedAccount = account.account }
            DriveSyncManager.createWithCredential(this, cred)
        } else {
            DriveSyncManager.createAnonymous(this)
        }
        isSyncingAnonymously = account == null
        return sync
    }

    private fun validateConnection(configId: String) {
        if (isValidatingConnection) return
        val sync = createSyncManager()
        scannerViewModel.setConnectionVerified(false)
        connectionUiState = ConnectionUiState.Validating
        syncStatus = SyncStatus.SYNCING
        syncMessage = null
        lifecycleScope.launch {
            isValidatingConnection = true
            try {
                if (!completePendingRevocationCleanup(sync)) {
                    connectionUiState = ConnectionUiState.Unavailable(
                        title = "Connection unavailable",
                        message = "easyPass is finishing a previous organization permission update. Keep the device online and try again."
                    )
                    syncStatus = SyncStatus.ERROR
                    return@launch
                }

                when (val configResult = sync.downloadConfigResult(configId)) {
                    is ConfigLoadResult.Success -> {
                        val config = configResult.config
                        if (config.activeDatabaseId.isBlank()) {
                            connectionUiState = ConnectionUiState.Unavailable(
                                title = "Organization database unavailable",
                                message = "easyPass could not load the shared organization database. Try again or use a current invitation link if the connection was replaced."
                            )
                            syncStatus = SyncStatus.ERROR
                            return@launch
                        }
                        currentConfig = config
                        scannerViewModel.updateConfig(config)
                        sessionManager.updateConfig(config)

                        when (val databaseResult = sync.downloadAndParseExcelResult(config.activeDatabaseId, config.isPro)) {
                            is DatabaseLoadResult.Success -> {
                                val result = databaseResult.result
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
                                scannerViewModel.setConnectionVerified(true)
                                connectionUiState = ConnectionUiState.Connected
                            }
                            is DatabaseLoadResult.DownloadFailure -> {
                                syncStatus = SyncStatus.ERROR
                                connectionUiState = mapDownloadFailureToUiState(
                                    error = databaseResult.error,
                                    missingTitle = "Organization database unavailable",
                                    missingMessage = "easyPass could not load the shared organization database. Try again or use a current invitation link if the connection was replaced.",
                                    treatMissingAsRemoved = false
                                )
                            }
                            is DatabaseLoadResult.ParseFailure -> {
                                syncStatus = SyncStatus.ERROR
                                connectionUiState = ConnectionUiState.Unavailable(
                                    title = "Organization database unavailable",
                                    message = "easyPass could not load the shared organization database. Try again or use a current invitation link if the connection was replaced."
                                )
                            }
                        }
                    }
                    is ConfigLoadResult.DownloadFailure -> {
                        syncStatus = SyncStatus.ERROR
                        val state = mapDownloadFailureToUiState(
                            error = configResult.error,
                            missingTitle = "Organization connection unavailable",
                            missingMessage = "This device is no longer connected to the organization. Ask an organization Manager or Director for a current invitation link.",
                            treatMissingAsRemoved = true
                        )
                        if (state is ConnectionUiState.ConnectionRemoved) {
                            clearDisconnectedConnection()
                        }
                        connectionUiState = state
                    }
                    is ConfigLoadResult.ParseFailure -> {
                        syncStatus = SyncStatus.ERROR
                        connectionUiState = ConnectionUiState.Unavailable(
                            title = "Organization connection unavailable",
                            message = "easyPass could not verify your organization connection. Check your internet connection and try again."
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to load config", e)
                syncStatus = SyncStatus.ERROR
                connectionUiState = ConnectionUiState.Unavailable(
                    title = "Connection unavailable",
                    message = "easyPass could not verify your organization connection. Check your internet connection and try again."
                )
            } finally {
                isValidatingConnection = false
            }
        }
    }

    private suspend fun completePendingRevocationCleanup(sync: DriveSyncManager): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val oldConfigId = prefs.getString(PENDING_REVOCATION_OLD_CONFIG_KEY, null)
        val oldDatabaseId = prefs.getString(PENDING_REVOCATION_OLD_DATABASE_KEY, null)
        if (oldConfigId.isNullOrBlank() || oldDatabaseId.isNullOrBlank()) {
            clearPendingRevocationState()
            return true
        }

        val configDeleted = sync.permanentlyDeleteFile(oldConfigId)
        val databaseDeleted = sync.permanentlyDeleteFile(oldDatabaseId)
        if (configDeleted && databaseDeleted) {
            clearPendingRevocationState()
            return true
        }
        return false
    }

    private fun handleRevocationResult(result: RevokeOrganizationAccessResult) {
        when (result) {
            is RevokeOrganizationAccessResult.Failure -> {
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
            }
            is RevokeOrganizationAccessResult.Success -> {
                clearPendingRevocationState()
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(CONFIG_FILE_ID_KEY, result.newConfigId)
                    .apply()
                configFileId = result.newConfigId
                currentConfig = result.newConfig
                scannerViewModel.updateConfig(result.newConfig)
                sessionManager.updateConfig(result.newConfig)
                sessionManager.resetSession()
                validateConnection(result.newConfigId)
                Toast.makeText(this, "Organization access revoked. Other devices now need a new invitation link.", Toast.LENGTH_LONG).show()
            }
            is RevokeOrganizationAccessResult.CleanupPending -> {
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                prefs.edit()
                    .putString(CONFIG_FILE_ID_KEY, result.newConfigId)
                    .putString(PENDING_REVOCATION_NEW_CONFIG_KEY, result.newConfigId)
                    .putString(PENDING_REVOCATION_OLD_CONFIG_KEY, result.oldConfigId)
                    .putString(PENDING_REVOCATION_OLD_DATABASE_KEY, result.oldDatabaseId)
                    .apply()
                configFileId = result.newConfigId
                currentConfig = result.newConfig
                scannerViewModel.updateConfig(result.newConfig)
                sessionManager.updateConfig(result.newConfig)
                sessionManager.resetSession()
                connectionUiState = ConnectionUiState.Unavailable(
                    title = "Connection unavailable",
                    message = "easyPass is finishing the organization permission reset. Keep this device online and try again."
                )
            }
        }
    }

    private fun clearPendingRevocationState() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .remove(PENDING_REVOCATION_NEW_CONFIG_KEY)
            .remove(PENDING_REVOCATION_OLD_CONFIG_KEY)
            .remove(PENDING_REVOCATION_OLD_DATABASE_KEY)
            .apply()
    }

    private fun mapDownloadFailureToUiState(
        error: com.example.access.util.FileDownloadResult,
        missingTitle: String,
        missingMessage: String,
        treatMissingAsRemoved: Boolean
    ): ConnectionUiState {
        return when (error) {
            is com.example.access.util.FileDownloadResult.HttpError -> {
                when (error.statusCode) {
                    403 -> ConnectionUiState.Unavailable(
                        title = "Organization access unavailable",
                        message = "easyPass cannot access this organization through Google Drive. Check sharing access or use a current invitation link."
                    )
                    404, 410 -> if (treatMissingAsRemoved) {
                        ConnectionUiState.ConnectionRemoved(
                            title = missingTitle,
                            message = missingMessage
                        )
                    } else {
                        ConnectionUiState.Unavailable(
                            title = missingTitle,
                            message = missingMessage
                        )
                    }
                    else -> ConnectionUiState.Unavailable(
                        title = "Connection unavailable",
                        message = "easyPass could not verify your organization connection. Check your internet connection and try again."
                    )
                }
            }
            is com.example.access.util.FileDownloadResult.NetworkError,
            is com.example.access.util.FileDownloadResult.UnexpectedError -> ConnectionUiState.Unavailable(
                title = "Connection unavailable",
                message = "easyPass could not verify your organization connection. Check your internet connection and try again."
            )
            is com.example.access.util.FileDownloadResult.Success -> ConnectionUiState.Unavailable(
                title = "Connection unavailable",
                message = "easyPass could not verify your organization connection. Check your internet connection and try again."
            )
        }
    }

    private suspend fun clearDisconnectedConnection() {
        withContext(Dispatchers.IO) {
            AppDatabase.getDatabase(this@MainActivity).memberDao().deleteAllMembers()
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .remove(CONFIG_FILE_ID_KEY)
                .remove(PENDING_REVOCATION_NEW_CONFIG_KEY)
                .remove(PENDING_REVOCATION_OLD_CONFIG_KEY)
                .remove(PENDING_REVOCATION_OLD_DATABASE_KEY)
                .apply()
        }
        configFileId = null
        sessionManager.resetSession()
        scannerViewModel.setConnectionVerified(false)
    }

    private fun openSetupForInvitation() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(this@MainActivity).memberDao().deleteAllMembers()
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .remove(CONFIG_FILE_ID_KEY)
                    .remove(PENDING_REVOCATION_NEW_CONFIG_KEY)
                    .remove(PENDING_REVOCATION_OLD_CONFIG_KEY)
                    .remove(PENDING_REVOCATION_OLD_DATABASE_KEY)
                    .apply()
            }
            configFileId = null
            sessionManager.resetSession()
            startActivity(Intent(this@MainActivity, SetupWizardActivity::class.java))
            finish()
        }
    }

    private fun leaveOrganization(signOutGoogle: Boolean) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(this@MainActivity).memberDao().deleteAllMembers()
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .remove(CONFIG_FILE_ID_KEY)
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

@Composable
private fun ConnectionStateScreen(
    title: String,
    message: String,
    primaryActionLabel: String?,
    onPrimaryAction: (() -> Unit)?,
    secondaryActionLabel: String?,
    onSecondaryAction: (() -> Unit)?
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                primaryActionLabel?.let { label ->
                    Button(
                        onClick = { onPrimaryAction?.invoke() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(label)
                    }
                }
                secondaryActionLabel?.let { label ->
                    TextButton(
                        onClick = { onSecondaryAction?.invoke() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(label)
                    }
                }
            }
        }
    }
}
