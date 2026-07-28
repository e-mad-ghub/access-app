package com.example.access

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.key
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.access.data.*
import com.example.access.ui.MainAppNavigation
import com.example.access.ui.components.InitialSplashLoader
import com.example.access.ui.components.LoadingOverlay
import com.example.access.ui.components.ScanResultBottomSheet
import com.example.access.ui.theme.AccessTheme
import com.example.access.util.DriveSyncManager
import com.example.access.util.FeedbackManager
import com.example.access.util.SessionManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.drive.DriveScopes
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class SyncStatus { IDLE, SYNCING, HEALTHY, ERROR }

class MainActivity : ComponentActivity() {
    private lateinit var sessionManager: SessionManager
    private lateinit var feedbackManager: FeedbackManager
    private var currentConfig by mutableStateOf(Config())
    private var syncStatus by mutableStateOf(SyncStatus.IDLE)
    private var isInitializing by mutableStateOf(true)
    private var globalLoadingMessage by mutableStateOf<String?>(null)

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                if (sessionManager.getActiveRole() != SessionManager.ROLE_SCANNER) {
                    sessionManager.resetSession()
                }
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        setupContent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionManager = SessionManager(this)
        feedbackManager = FeedbackManager(this)
        
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        
        val configFileId = getSharedPreferences("easypass_prefs", MODE_PRIVATE)
            .getString("config_file_id", null)
        
        if (configFileId == null) {
            startActivity(Intent(this, SetupWizardActivity::class.java))
            finish()
            return
        }

        enableEdgeToEdge()
        loadConfig(configFileId)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            setupContent()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun loadConfig(configId: String) {
        val account = GoogleSignIn.getLastSignedInAccount(this) ?: return
        val cred = GoogleAccountCredential.usingOAuth2(this, listOf(DriveScopes.DRIVE)).apply { selectedAccount = account.account }
        val sync = DriveSyncManager(this, cred)
        
        syncStatus = SyncStatus.SYNCING
        lifecycleScope.launch {
            // Minimum 2s splash for brand awareness
            val startTime = System.currentTimeMillis()
            
            val config = sync.downloadConfig(configId)
            if (config != null) {
                currentConfig = config
                sessionManager.updateConfig(config)
                val success = sync.downloadAndParseExcel(config.activeDatabaseId)
                syncStatus = if (success) SyncStatus.HEALTHY else SyncStatus.ERROR
            } else {
                syncStatus = SyncStatus.ERROR
            }
            
            val duration = System.currentTimeMillis() - startTime
            if (duration < 2000) kotlinx.coroutines.delay(2000 - duration)

            isInitializing = false
        }
    }

    private fun setupContent() {
        setContent {
            AccessTheme(branding = currentConfig.branding) {
                if (isInitializing) {
                    InitialSplashLoader()
                } else {
                    val db = remember { AppDatabase.getDatabase(this) }
                    val members by db.memberDao().getAllMembers().observeAsState(emptyList())
                    val memberCount = members.size
                    
                    val scannerViewModel: MainScannerViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainScannerViewModel(db, feedbackManager) as T
                    })
                    val scanResult by scannerViewModel.scanResult.collectAsState()
                    val recentScans by scannerViewModel.recentScans.collectAsState()
                    
                    Box(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                        MainAppNavigation(
                            sessionManager = sessionManager,
                            currentConfig = currentConfig,
                            onConfigUpdated = { currentConfig = it },
                            scannerViewModel = scannerViewModel,
                            memberCount = memberCount,
                            syncStatus = syncStatus,
                            recentScans = recentScans,
                            onManualSync = { 
                                getSharedPreferences("easypass_prefs", MODE_PRIVATE).getString("config_file_id", null)?.let { loadConfig(it) }
                            },
                            onRepairCloud = { repairCloudStorage() }
                        )

                        scanResult?.let { result ->
                            ScanResultBottomSheet(result = result, onDismiss = { scannerViewModel.clearResult() })
                        }

                        LoadingOverlay(
                            isVisible = globalLoadingMessage != null,
                            message = globalLoadingMessage ?: ""
                        )
                    }
                }
            }
        }
    }

    private fun repairCloudStorage() {
        val account = GoogleSignIn.getLastSignedInAccount(this) ?: return
        val cred = GoogleAccountCredential.usingOAuth2(this, listOf(DriveScopes.DRIVE)).apply { selectedAccount = account.account }
        val sync = DriveSyncManager(this, cred)
        
        globalLoadingMessage = "Repairing Cloud Data..."
        lifecycleScope.launch {
            val lastFileId = getSharedPreferences("easypass_prefs", MODE_PRIVATE).getString("config_file_id", "") ?: ""
            val parentId = sync.getParentId(lastFileId) ?: "root"
            
            val newConfigId = sync.repairMissingCloudFiles(parentId, currentConfig)
            if (newConfigId != null) {
                getSharedPreferences("easypass_prefs", MODE_PRIVATE).edit().putString("config_file_id", newConfigId).apply()
                loadConfig(newConfigId)
                Toast.makeText(this@MainActivity, "Storage Fixed", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "Repair Failed", Toast.LENGTH_LONG).show()
            }
            globalLoadingMessage = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (e: Exception) { }
        feedbackManager.release()
    }
}

class MainScannerViewModel(private val db: AppDatabase, private val feedbackManager: FeedbackManager) : ViewModel() {
    private val _scanResult = MutableStateFlow<ScanResult?>(null)
    val scanResult: StateFlow<ScanResult?> = _scanResult
    private val _recentScans = MutableStateFlow<List<RecentScan>>(emptyList())
    val recentScans: StateFlow<List<RecentScan>> = _recentScans
    private var isProcessing = false

    fun processBarcode(hash: String) {
        if (isProcessing) return
        isProcessing = true
        viewModelScope.launch {
            val member = db.memberDao().getMemberByHash(hash)
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            if (member != null && member.status.lowercase() == "active") {
                feedbackManager.emitSuccess()
                _scanResult.value = ScanResult.Granted(member.fullName)
                addRecentScan(RecentScan(member.fullName, time, true))
            } else {
                feedbackManager.emitError()
                val reason = member?.let { if (it.status.lowercase() == "paused") "Paused" else "Expired" } ?: "Invalid Pass"
                _scanResult.value = ScanResult.Denied(reason)
                addRecentScan(RecentScan(member?.fullName ?: "Unknown", time, false))
            }
        }
    }
    private fun addRecentScan(scan: RecentScan) {
        val newList = _recentScans.value.toMutableList()
        newList.add(scan)
        if (newList.size > 5) newList.removeAt(0)
        _recentScans.value = newList
    }
    fun clearResult() { _scanResult.value = null; isProcessing = false }
}

@Composable
fun CameraPreview(onBarcodeScanned: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    AndroidView(factory = { ctx ->
        val previewView = PreviewView(ctx)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val imageAnalysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also {
                it.setAnalyzer(cameraExecutor, BarcodeAnalyzer { barcode -> onBarcodeScanned(barcode) })
            }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
            } catch (e: Exception) { }
        }, ContextCompat.getMainExecutor(ctx))
        previewView
    }, modifier = Modifier.fillMaxSize())
}

private class BarcodeAnalyzer(private val onBarcodeScanned: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val scanner = com.google.mlkit.vision.barcode.BarcodeScanning.getClient()
    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        imageProxy.image?.let { mediaImage ->
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) barcode.rawValue?.let { onBarcodeScanned(it) }
                }
                .addOnCompleteListener { imageProxy.close() }
        } ?: imageProxy.close()
    }
}
