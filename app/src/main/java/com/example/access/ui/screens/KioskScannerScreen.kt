package com.example.access.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.access.CameraPreview
import com.example.access.data.RecentScan

@Composable
fun KioskScannerScreen(
    onBarcodeScanned: (String) -> Unit,
    recentScans: List<RecentScan>
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8F9FA))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .background(Color.White, MaterialTheme.shapes.extraLarge)
                .padding(4.dp)
        ) {
            CameraPreview(onBarcodeScanned = onBarcodeScanned)
            ViewfinderOverlay()
        }

        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Recent Activity", style = MaterialTheme.typography.labelLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.heightIn(max = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(recentScans.reversed().take(3)) { scan ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(if (scan.isGranted) Color.Green else Color.Red, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (scan.isGranted) "Welcome back, ${scan.name}!" else "Denied: ${scan.name}",
                                    fontSize = 13.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                )
                            }
                            Text(text = scan.time, color = Color.Gray, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ViewfinderOverlay() {
    val infiniteTransition = rememberInfiniteTransition(label = "ScanLine")
    val scanLinePos by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Pos"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val rectSize = 250.dp.toPx()
        val left = (width - rectSize) / 2
        val top = (height - rectSize) / 2

        drawRect(
            color = Color.Black.copy(alpha = 0.5f),
            size = size
        )

        drawRoundRect(
            color = Color.Transparent,
            topLeft = Offset(left, top),
            size = Size(rectSize, rectSize),
            cornerRadius = CornerRadius(40f),
            blendMode = BlendMode.Clear
        )

        drawRoundRect(
            color = Color.White,
            topLeft = Offset(left, top),
            size = Size(rectSize, rectSize),
            cornerRadius = CornerRadius(40f),
            style = Stroke(width = 6f)
        )

        val lineY = top + (rectSize * scanLinePos)
        drawLine(
            color = Color.White.copy(alpha = 0.8f),
            start = Offset(left + 20f, lineY),
            end = Offset(left + rectSize - 20f, lineY),
            strokeWidth = 4f
        )
    }
}
