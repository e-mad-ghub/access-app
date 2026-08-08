package com.example.access

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.access.data.AppDatabase
import com.example.access.data.RecentScan
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainScannerViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    
    private val _liveScanHistory = MutableStateFlow<List<RecentScan>>(emptyList())
    val liveScanHistory: StateFlow<List<RecentScan>> = _liveScanHistory

    private val _activeScanResult = MutableStateFlow<RecentScan?>(null)
    val activeScanResult: StateFlow<RecentScan?> = _activeScanResult

    private var lastScannedBarcode: String? = null
    private var lastScanTime: Long = 0

    fun processBarcode(barcode: String) {
        val currentTime = System.currentTimeMillis()
        
        // --- 1. FREQUENCY CONTROL (DEBOUNCE) ---
        // Ignore the same QR code if scanned within 3 seconds
        if (barcode == lastScannedBarcode && (currentTime - lastScanTime) < 3000) {
            return 
        }

        lastScannedBarcode = barcode
        lastScanTime = currentTime

        viewModelScope.launch {
            val member = db.memberDao().getMemberByHash(barcode)
            val isGranted = member != null && member.status == "Active"
            
            val scanName = member?.fullName ?: "Unknown User"
            val scanTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            
            val newScan = RecentScan(scanName, scanTime, isGranted)

            // --- LIVE ACTIVITY FIX: Prepend to top of list ---
            _liveScanHistory.value = (listOf(newScan) + _liveScanHistory.value).take(20)

            // --- LATCHING FIX: Vanishing Popup ---
            _activeScanResult.value = newScan
            delay(2000) // Show for 2 seconds
            if (_activeScanResult.value == newScan) {
                _activeScanResult.value = null
            }
        }
    }
}
