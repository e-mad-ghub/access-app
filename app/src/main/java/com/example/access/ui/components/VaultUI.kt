package com.example.access.ui.components

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.access.util.SessionManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes

/**
 * Secret Role Elevation Logic
 * Provides a discreet gesture handler and management banner without a visible TopBar.
 */

@Composable
fun ManagementSecretHandler(
    sessionManager: SessionManager,
    onSessionChanged: () -> Unit
) {
    val context = LocalContext.current
    val activeRole by sessionManager.activeRole.collectAsState()

    // This component renders the management banner at the top when active
    Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
        if (activeRole != sessionManager.getBaseRole()) {
            ElevatedBanner(activeRole) {
                sessionManager.resetSession()
                onSessionChanged()
                Toast.makeText(context, "Session Ended", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
fun PasswordElevationDialog(
    sessionManager: SessionManager,
    onDismiss: () -> Unit,
    onSessionChanged: () -> Unit
) {
    val context = LocalContext.current
    val signInLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account != null) {
            val role = sessionManager.applyPendingRole()
            Toast.makeText(context, "Elevated to ${role?.uppercase()}", Toast.LENGTH_SHORT).show()
        } else {
            sessionManager.setPendingRole(null)
            Toast.makeText(context, "Identity Verification Failed", Toast.LENGTH_SHORT).show()
        }
        onSessionChanged()
    }

    PasswordDialog(
        onDismiss = onDismiss,
        onConfirm = { password ->
            val matchedRole = sessionManager.checkPassword(password)
            if (matchedRole != null) {
                Toast.makeText(context, "Key Accepted", Toast.LENGTH_SHORT).show()
                if (matchedRole == SessionManager.ROLE_ADMIN || matchedRole == SessionManager.ROLE_OWNER) {
                    val account = GoogleSignIn.getLastSignedInAccount(context)
                    if (account == null) {
                        sessionManager.setPendingRole(matchedRole)
                        Toast.makeText(context, "Login required for write access", Toast.LENGTH_LONG).show()
                        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                            .requestEmail()
                            .requestScopes(Scope(DriveScopes.DRIVE))
                            .build()
                        signInLauncher.launch(GoogleSignIn.getClient(context, gso).signInIntent)
                    } else {
                        sessionManager.setActiveRole(matchedRole)
                        Toast.makeText(context, "Elevated to ${matchedRole.uppercase()}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    sessionManager.setActiveRole(matchedRole)
                }
            } else {
                Toast.makeText(context, "Incorrect Security Key", Toast.LENGTH_SHORT).show()
            }
            onDismiss()
            onSessionChanged()
        }
    )
}

@Composable
fun ElevatedBanner(role: String, onEndSession: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).background(Color.White, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "MANAGEMENT: ${role.uppercase()}",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 10.sp,
                    letterSpacing = 1.2.sp
                )
            }
            Surface(
                onClick = onEndSession,
                color = Color.White.copy(alpha = 0.2f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    "EXIT SESSION",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}

@Composable
fun PasswordDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vault Unlock", fontWeight = FontWeight.Black) },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Security Key") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { 
            Button(
                onClick = { onConfirm(password) },
                shape = RoundedCornerShape(12.dp)
            ) { Text("Unlock") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Discreet tap modifier for secret elevation
 */
@Composable
fun Modifier.secretElevation(
    onTrigger: () -> Unit
): Modifier {
    var tapCount by remember { mutableStateOf(0) }
    var lastTapTime by remember { mutableStateOf(0L) }
    
    return this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null // No visual ripple to remain secret
    ) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastTapTime < 500) tapCount++ else tapCount = 1
        lastTapTime = currentTime
        if (tapCount >= 5) {
            onTrigger()
            tapCount = 0
        }
    }
}
