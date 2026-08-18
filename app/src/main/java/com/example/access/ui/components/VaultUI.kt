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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.VpnKey
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
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes

/**
 * Secret Role Elevation Logic
 * Provides a discreet gesture handler and management banner without a visible TopBar.
 */

@Composable
fun ManagementSecretHandler(
    sessionManager: SessionManager,
    onSessionChanged: () -> Unit,
    onRequestElevation: () -> Unit
) {
    val context = LocalContext.current
    val activeRole by sessionManager.activeRole.collectAsState()

    // This component renders the management banner at the top when active
    Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
        when (activeRole) {
            SessionManager.ROLE_SCANNER -> {
                ScannerBanner(onChangeSession = onRequestElevation)
            }
            sessionManager.getBaseRole() -> {
                // Should not happen, but if base role is not scanner, no banner
            }
            else -> {
                ElevatedBanner(activeRole) {
                    sessionManager.resetSession()
                    onSessionChanged()
                    Toast.makeText(context, "Session Ended", Toast.LENGTH_SHORT).show()
                }
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
        val signInError = try {
            GoogleSignIn.getSignedInAccountFromIntent(it.data).getResult(ApiException::class.java)
            null
        } catch (e: ApiException) {
            "${e.statusCode}: ${CommonStatusCodes.getStatusCodeString(e.statusCode)}"
        }
        if (signInError == null && GoogleSignIn.getLastSignedInAccount(context) != null) {
            val role = sessionManager.applyPendingRole()
            Toast.makeText(context, "Elevated to ${role?.uppercase()}", Toast.LENGTH_SHORT).show()
        } else {
            sessionManager.setPendingRole(null)
            Toast.makeText(context, "Identity Verification Failed${signInError?.let { error -> " ($error)" } ?: ""}", Toast.LENGTH_LONG).show()
        }
        onSessionChanged()
    }

    fun proceedWithRole(role: String) {
        Toast.makeText(context, "Key Accepted", Toast.LENGTH_SHORT).show()
        if (role == SessionManager.ROLE_ADMIN || role == SessionManager.ROLE_OWNER) {
            val account = GoogleSignIn.getLastSignedInAccount(context)
            if (account == null) {
                sessionManager.setPendingRole(role)
                Toast.makeText(context, "Google sign-in is required to write organization changes to Drive.", Toast.LENGTH_LONG).show()
                val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestEmail()
                    .requestScopes(Scope(DriveScopes.DRIVE))
                    .build()
                signInLauncher.launch(GoogleSignIn.getClient(context, gso).signInIntent)
            } else {
                sessionManager.setActiveRole(role)
                Toast.makeText(context, "Elevated to ${role.uppercase()}", Toast.LENGTH_SHORT).show()
                onSessionChanged()
                onDismiss()
            }
        } else {
            sessionManager.setActiveRole(role)
            onSessionChanged()
            onDismiss()
        }
    }

    PasswordDialog(
        onDismiss = onDismiss,
        onConfirm = { selectedRole, password ->
            val matches = sessionManager.checkPasswordAll(password)
            if (matches.contains(selectedRole)) {
                proceedWithRole(selectedRole)
            } else {
                val roleLabel = if (selectedRole == SessionManager.ROLE_ADMIN) "Admin" else "Owner"
                Toast.makeText(context, "Incorrect $roleLabel Password", Toast.LENGTH_SHORT).show()
                onDismiss()
                onSessionChanged()
            }
        }
    )
}

@Composable
fun ScannerBanner(onChangeSession: () -> Unit) {
    val bannerColor = roleBannerColor(SessionManager.ROLE_SCANNER)
    Surface(
        color = bannerColor,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).background(Color.White.copy(alpha = 0.9f), CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "MODE: SCANNER",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp
                )
            }
            Surface(
                onClick = onChangeSession,
                color = Color.White.copy(alpha = 0.18f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    "CHANGE SESSION",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun ElevatedBanner(role: String, onEndSession: () -> Unit) {
    val bannerColor = roleBannerColor(role)
    Surface(
        color = bannerColor,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).background(Color.White.copy(alpha = 0.9f), CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "MANAGEMENT: ${role.uppercase()}",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp
                )
            }
            Surface(
                onClick = onEndSession,
                color = Color.White.copy(alpha = 0.18f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    "EXIT SESSION",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

private fun roleBannerColor(role: String): Color {
    return when (role) {
        SessionManager.ROLE_OWNER -> Color(0xFF1D4ED8)
        SessionManager.ROLE_ADMIN -> Color(0xFF047857)
        else -> Color(0xFF98A2B3)
    }
}

@Composable
fun PasswordDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var selectedRole by remember { mutableStateOf(SessionManager.ROLE_ADMIN) }
    var password by remember { mutableStateOf("") }
    val selectedRoleLabel = if (selectedRole == SessionManager.ROLE_ADMIN) "Admin" else "Owner"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Switch Role", fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Choose the management role for this session, then enter its password.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilterChip(
                        selected = selectedRole == SessionManager.ROLE_ADMIN,
                        onClick = { selectedRole = SessionManager.ROLE_ADMIN },
                        label = { Text("Admin") },
                        leadingIcon = {
                            Icon(Icons.Default.AdminPanelSettings, null, modifier = Modifier.size(18.dp))
                        },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = selectedRole == SessionManager.ROLE_OWNER,
                        onClick = { selectedRole = SessionManager.ROLE_OWNER },
                        label = { Text("Owner") },
                        leadingIcon = {
                            Icon(Icons.Default.VpnKey, null, modifier = Modifier.size(18.dp))
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("$selectedRoleLabel Password") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { 
            Button(
                onClick = { onConfirm(selectedRole, password) },
                enabled = password.isNotBlank(),
                shape = RoundedCornerShape(12.dp)
            ) { Text("Switch to $selectedRoleLabel") }
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
