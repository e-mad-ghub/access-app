package com.example.access.ui.components

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
fun VaultTopBar(
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
                                .size(28.dp)
                                .clip(CircleShape)
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        text = orgName.uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            actions = {
                if (activeRole == SessionManager.ROLE_SCANNER) {
                    IconButton(onClick = { /* Tooltip or Info */ }) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
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
        color = Color(0xFF263238), // Dark sleek Slate
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(6.dp).background(Color.Red, CircleShape))
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "MANAGEMENT MODE: ${role.uppercase()}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp
                )
            }
            TextButton(
                onClick = onEndSession,
                modifier = Modifier.height(28.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("EXIT", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
fun PasswordDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Elevate Permissions", fontWeight = FontWeight.Black) },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Enter Management Key") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
            )
        },
        confirmButton = { 
            Button(
                onClick = { onConfirm(password) },
                shape = RoundedCornerShape(12.dp)
            ) { Text("Confirm") } 
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
