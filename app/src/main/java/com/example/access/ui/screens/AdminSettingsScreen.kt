package com.example.access.ui.screens

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.access.data.Config
import com.example.access.util.DriveSyncManager
import com.example.access.util.SecurityUtils
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.launch

@Composable
fun AdminSettingsScreen(config: Config) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isBusy by remember { mutableStateOf(false) }
    var showInviteDialog by remember { mutableStateOf(false) }
    
    var beepEnabled by remember { mutableStateOf(true) }
    var vibrationEnabled by remember { mutableStateOf(true) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            isBusy = true
            scope.launch {
                val account = GoogleSignIn.getLastSignedInAccount(context) ?: return@launch
                val cred = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE)).apply { selectedAccount = account.account }
                val sync = DriveSyncManager(context, cred)
                context.contentResolver.openInputStream(it)?.use { stream ->
                    sync.importLocalSheet(stream)
                    sync.exportRoomToExcelAndUpload(config.activeDatabaseId)
                }
                isBusy = false
                Toast.makeText(context, "Bulk Import Successful", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // SECURE STAFF INVITE
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Secure Staff Invite", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Generate an encrypted link with a 4-digit PIN to onboard staff safely.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                
                Spacer(Modifier.height(16.dp))
                
                Button(
                    onClick = { showInviteDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.EnhancedEncryption, null)
                    Spacer(Modifier.width(12.dp))
                    Text("Create Secure Invite")
                }
            }
        }

        // Data Management
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Data Management", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val account = GoogleSignIn.getLastSignedInAccount(context) ?: return@launch
                            val cred = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE)).apply { selectedAccount = account.account }
                            val sync = DriveSyncManager(context, cred)
                            val file = sync.exportLocalBackup()
                            file?.let {
                                val uri = androidx.core.content.FileProvider.getUriForFile(context, "com.example.access.fileprovider", it)
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "Share Backup"))
                            }
                        }
                    }, 
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Download, null)
                    Spacer(Modifier.width(12.dp))
                    Text("Export Local Backup")
                }

                OutlinedButton(
                    onClick = { importLauncher.launch("*/*") }, 
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Upload, null)
                    Spacer(Modifier.width(12.dp))
                    Text("Bulk Import (.xlsx)")
                }
            }
        }

        // Scanner Preferences
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Scanner Feedback", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Beep on Scan", modifier = Modifier.weight(1f))
                    Switch(checked = beepEnabled, onCheckedChange = { beepEnabled = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Vibrate on Scan", modifier = Modifier.weight(1f))
                    Switch(checked = vibrationEnabled, onCheckedChange = { vibrationEnabled = it })
                }
            }
        }
    }

    if (showInviteDialog) {
        var joinPin by remember { mutableStateOf("") }
        val configId = context.getSharedPreferences("easypass_prefs", Context.MODE_PRIVATE).getString("config_file_id", "") ?: ""

        AlertDialog(
            onDismissRequest = { showInviteDialog = false },
            title = { Text("Set Invitation PIN", fontWeight = FontWeight.Black) },
            text = {
                Column {
                    Text("Enter a 4-digit PIN for this invite. The recipient will need this to unlock the connection.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = joinPin,
                        onValueChange = { if (it.length <= 4) joinPin = it },
                        label = { Text("4-Digit PIN") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val encryptedKey = SecurityUtils.encryptInvite(configId, joinPin)
                        val inviteUrl = "easypass://join?key=$encryptedKey"
                        
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "Join our team on EasyPass. Tap the link and enter the PIN provided by your Admin.\n\nLink: $inviteUrl")
                        }
                        context.startActivity(Intent.createChooser(intent, "Share Invite"))
                        showInviteDialog = false
                    },
                    enabled = joinPin.length == 4
                ) { Text("Share Link") }
            },
            dismissButton = { TextButton(onClick = { showInviteDialog = false }) { Text("Cancel") } }
        )
    }

    if (isBusy) {
        com.example.access.ui.components.LoadingOverlay(isVisible = true, message = "Processing...")
    }
}
