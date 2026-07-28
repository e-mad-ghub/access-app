package com.example.access.ui.components

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.access.util.SessionManager
import com.example.access.ui.theme.LocalBranding

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassTopBar(
    orgName: String,
    sessionManager: SessionManager,
    onSessionChanged: () -> Unit
) {
    var tapCount by remember { mutableStateOf(0) }
    var lastTapTime by remember { mutableStateOf(0L) }
    var showPasswordDialog by remember { mutableStateOf(false) }

    val activeRole by sessionManager.activeRole.collectAsState()
    val branding = LocalBranding.current

    Column {
        if (activeRole != sessionManager.getBaseRole()) {
            ElevatedBanner(activeRole) {
                sessionManager.resetSession()
                onSessionChanged()
            }
        }

        TopAppBar(
            windowInsets = WindowInsets(0, 0, 0, 0),
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastTapTime < 500) tapCount++ else tapCount = 1
                        lastTapTime = currentTime
                        if (tapCount >= 5) {
                            showPasswordDialog = true
                            tapCount = 0
                        }
                    }
                ) {
                    branding.logoFileId?.let { logoId ->
                        AsyncImage(
                            model = "https://lh3.googleusercontent.com/u/0/d/$logoId",
                            contentDescription = null,
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        text = orgName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.White,
                titleContentColor = MaterialTheme.colorScheme.onSurface
            )
        )
    }

    if (showPasswordDialog) {
        PasswordDialog(
            onDismiss = { showPasswordDialog = false },
            onConfirm = { password ->
                val matchedRole = sessionManager.checkPassword(password)
                if (matchedRole != null) {
                    sessionManager.setActiveRole(matchedRole)
                }
                showPasswordDialog = false
                onSessionChanged()
            }
        )
    }
}

@Composable
fun ElevatedBanner(role: String, onEndSession: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(6.dp).background(Color.White, CircleShape))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "MANAGEMENT: ${role.uppercase()}",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 9.sp,
                    letterSpacing = 1.sp
                )
            }
            TextButton(
                onClick = onEndSession,
                modifier = Modifier.height(24.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("EXIT", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
fun PasswordDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unlock Features", fontWeight = FontWeight.Black) },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Security Key") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
            )
        },
        confirmButton = { 
            Button(
                onClick = { onConfirm(password) },
                shape = RoundedCornerShape(10.dp)
            ) { Text("Unlock") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
