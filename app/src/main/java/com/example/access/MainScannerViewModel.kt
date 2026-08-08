package com.example.access

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.access.data.AppDatabase
import com.example.access.data.RecentScan
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainScannerViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)

    private val _liveScanHistory = MutableStateFlow<List<RecentScan>>(emptyList())
    val liveScanHistory: StateFlow<List<RecentScan>> = _liveScanHistory

    private val _activeScanResult = MutableStateFlow<RecentScan?>(null)
    val activeScanResult: StateFlow<RecentScan?> = _activeScanResult

    private val scanStateLock = Any()
    private var lastScannedBarcode: String? = null
    private var lastScanElapsedMs: Long = 0L
    private var scanGeneration: Long = 0L

    fun processBarcode(barcode: String) {
        val normalizedBarcode = barcode.trim()
        if (normalizedBarcode.isEmpty()) return

        val now = SystemClock.elapsedRealtime()
        val generation = synchronized(scanStateLock) {
            // ML Kit may decode the same visible QR on every camera frame. Use
            // monotonic time and a lock because callbacks run on a camera thread.
            if (
                normalizedBarcode == lastScannedBarcode &&
                now - lastScanElapsedMs < SCAN_COOLDOWN_MS
            ) {
                return
            }

            lastScannedBarcode = normalizedBarcode
            lastScanElapsedMs = now
            ++scanGeneration
        }

        viewModelScope.launch {
            val member = db.memberDao().getMemberByHash(normalizedBarcode)
            val isGranted = member != null && member.status.equals("Active", ignoreCase = true)
            val newScan = RecentScan(
                name = member?.fullName ?: "Unknown User",
                time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
                isGranted = isGranted
            )

            _liveScanHistory.update { history ->
                (listOf(newScan) + history).take(MAX_SCAN_HISTORY)
            }

            _activeScanResult.value = newScan
            delay(RESULT_DISPLAY_MS)

            // A previous popup must never clear a newer result that happens to
            // contain equal data (same name/status/minute).
            if (synchronized(scanStateLock) { generation == scanGeneration }) {
                _activeScanResult.value = null
            }
        }
    }

    companion object {
        private const val SCAN_COOLDOWN_MS = 3_000L
        private const val RESULT_DISPLAY_MS = 2_000L
        private const val MAX_SCAN_HISTORY = 100
    }
}