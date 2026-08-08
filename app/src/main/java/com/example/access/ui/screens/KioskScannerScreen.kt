package com.example.access.ui.screens

import android.annotation.SuppressLint
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

@Composable
fun KioskScannerScreen(
    viewModel: com.example.access.MainScannerViewModel
) {
    val activeScan by viewModel.activeScanResult.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        CameraPreview { viewModel.processBarcode(it) }
        ViewfinderOverlay()
        
        // POPUP THAT VANISHES (Latching Fix)
        activeScan?.let { scan ->
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp),
                color = if (scan.isGranted) Color(0xFF00BFA5) else Color(0xFFD32F2F),
                shape = RoundedCornerShape(20.dp),
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(scan.name, color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Text(if (scan.isGranted) "ACCESS GRANTED" else "ACCESS DENIED", color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
fun CameraPreview(onBarcodeScanned: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    AndroidView(factory = { ctx ->
        val previewView = PreviewView(ctx)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
        
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                
                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processImageProxy(imageProxy, onBarcodeScanned)
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner, 
                    cameraSelector, 
                    preview, 
                    imageAnalysis
                )
            } catch (e: Exception) {
                android.util.Log.e("CameraPreview", "Binding failed", e)
            }
        }, ContextCompat.getMainExecutor(ctx))
        previewView
    }, modifier = Modifier.fillMaxSize())

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }
}

@ExperimentalGetImage
@SuppressLint("UnsafeOptInUsageError")
private fun processImageProxy(imageProxy: ImageProxy, onBarcodeScanned: (String) -> Unit) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        val scanner = BarcodeScanning.getClient()
        
        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                barcodes.firstOrNull()?.rawValue?.let { 
                    onBarcodeScanned(it)
                }
            }
            .addOnCompleteListener { 
                imageProxy.close() 
            }
    } else {
        imageProxy.close()
    }
}

@Composable
fun ViewfinderOverlay() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(250.dp)
                .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(32.dp))
                .padding(2.dp)
        )
        Icon(
            Icons.Default.QrCodeScanner,
            null,
            modifier = Modifier.size(40.dp),
            tint = Color.White.copy(alpha = 0.3f)
        )
    }
}
