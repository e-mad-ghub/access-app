package com.example.access.data

sealed class ScanResult {
    data class Granted(val name: String) : ScanResult()
    data class Denied(val reason: String) : ScanResult()
}

data class RecentScan(val name: String, val time: String, val isGranted: Boolean)
