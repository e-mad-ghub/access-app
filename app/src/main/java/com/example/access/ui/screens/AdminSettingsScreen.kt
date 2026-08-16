package com.example.access.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.FileProvider
import com.example.access.data.Config
import com.example.access.util.DriveSyncManager
import com.example.access.util.ImportResult
import com.example.access.util.SecurityUtils
import com.example.access.util.SessionManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.launch

@Composable
fun AdminSettingsScreen(
    config: Config,
    activeRole: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isBusy by remember { mutableStateOf(false) }
    
    var showInviteDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            isBusy = true
            scope.launch {
                val account = GoogleSignIn.getLastSignedInAccount(context) ?: return@launch
                val cred = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE)).apply { selectedAccount = account.account }
                val sync = DriveSyncManager(context, syncManagerFromCred(context, cred).drive)
                context.contentResolver.openInputStream(it)?.use { stream ->
                    val result = sync.importLocalSheet(stream)
                    if (result != null) {
                        sync.exportRoomToExcelAndUpload(config.activeDatabaseId)
                        val msg = if (result.skippedRows.isEmpty()) "Bulk Import Successful" else "Import completed with ${result.skippedRows.size} skipped rows: ${result.skippedRows}"
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Import failed", Toast.LENGTH_SHORT).show()
                    }
                }
                isBusy = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Connections", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (activeRole != SessionManager.ROLE_SCANNER) {
                        Button(
                            onClick = { showInviteDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Invite", fontSize = 12.sp)
                        }
                    }
                    
                    OutlinedButton(
                        onClick = { showJoinDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.AddLink, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Switch Organization", fontSize = 12.sp)
                    }
                }
            }
        }

        if (activeRole != SessionManager.ROLE_SCANNER) {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Data Management", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val account = GoogleSignIn.getLastSignedInAccount(context) ?: return@launch
                                val cred = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE)).apply { selectedAccount = account.account }
                                val sync = DriveSyncManager(context, syncManagerFromCred(context, cred).drive)
                                val file = sync.exportLocalBackup()
                                file?.let {
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
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
                        Text("Bulk Import Spreadsheet")
                    }
                }
            }
        }
        
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("EasyPass v1.0 Production", style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
        }
        
        Spacer(modifier = Modifier.height(100.dp))
    }

    if (showInviteDialog) {
        var joinPin by remember { mutableStateOf("") }
        val configId = context.getSharedPreferences("easypass_prefs", Context.MODE_PRIVATE).getString("config_file_id", "") ?: ""
        AlertDialog(
            onDismissRequest = { showInviteDialog = false },
            title = { Text("Secure Staff Invite", fontWeight = FontWeight.Black) },
            text = {
                Column {
                    Text("Recipients will need this 4-digit PIN to join.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(value = joinPin, onValueChange = { if (it.length <= 4) joinPin = it }, label = { Text("4-Digit PIN") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val encryptedKey = SecurityUtils.encryptInvite(configId, joinPin)
                        val inviteUrl = "https://easyapps-solutions.com/join?key=$encryptedKey"
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "Join our team on EasyPass. Paste this link into the app and enter PIN: $joinPin\n\nLink: $inviteUrl")
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

    if (showJoinDialog) {
        var linkText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showJoinDialog = false },
            title = { Text("Switch Organization", fontWeight = FontWeight.Black) },
            text = {
                Column {
                    Text("Paste a new invitation link below to connect to a different hub.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(value = linkText, onValueChange = { linkText = it }, label = { Text("Paste Link Here") }, placeholder = { Text("https://easyapps-solutions.com/join?key=...") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val uri = Uri.parse(linkText)
                        val key = uri.getQueryParameter("key")
                        if (key != null) {
                            val intent = Intent(context, com.example.access.SetupWizardActivity::class.java).apply { data = uri }
                            context.startActivity(intent)
                            showJoinDialog = false
                        } else { Toast.makeText(context, "Invalid link format", Toast.LENGTH_SHORT).show() }
                    },
                    enabled = linkText.contains("key=")
                ) { Text("Switch Now") }
            },
            dismissButton = { TextButton(onClick = { showJoinDialog = false }) { Text("Cancel") } }
        )
    }

    if (isBusy) {
        com.example.access.ui.components.LoadingOverlay(isVisible = true, message = "Processing...")
    }
}

private fun syncManagerFromCred(context: Context, cred: GoogleAccountCredential): DriveSyncManager {
    return DriveSyncManager.createWithCredential(context, cred)
}
