package com.example.access.ui.screens

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.access.data.Config
import com.example.access.util.DriveSyncManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.launch

@Composable
fun AdminSettingsScreen(config: Config) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isBusy by remember { mutableStateOf(false) }
    
    var beepEnabled by remember { mutableStateOf(true) }
    var vibrationEnabled by remember { mutableStateOf(true) }
    var resultTimer by remember { mutableFloatStateOf(2f) }

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

        // Invite Staff Section
        Card {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Invite Staff Member", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Allow others to access this Member Directory by sharing a secure setup link.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                
                val configId = context.getSharedPreferences("easypass_prefs", Context.MODE_PRIVATE).getString("config_file_id", "")
                
                Button(
                    onClick = {
                        val inviteUrl = "easypass://join?configId=$configId"
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "Join our team on EasyPass: $inviteUrl")
                        }
                        context.startActivity(Intent.createChooser(intent, "Invite Staff"))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Share Setup Link")
                }
            }
        }

        // Data Management
        Card {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Backup & Import Tools", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val account = GoogleSignIn.getLastSignedInAccount(context) ?: return@launch
                            val cred = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE)).apply { selectedAccount = account.account }
                            val sync = DriveSyncManager(context, cred)
                            val file = sync.exportLocalBackup()
                            file?.let {
                                val uri = FileProvider.getUriForFile(context, "com.example.access.fileprovider", it)
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "Share/Save Backup"))
                            }
                        }
                    }, 
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Create Local Backup (.xlsx)")
                }

                OutlinedButton(onClick = { importLauncher.launch("*/*") }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Upload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Import Member Spreadsheet")
                }
            }
        }

        // Scanner Preferences
        Card {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Scanner Feedback", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Beep on Scan", modifier = Modifier.weight(1f))
                    Switch(checked = beepEnabled, onCheckedChange = { beepEnabled = it })
                }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Vibrate on Scan", modifier = Modifier.weight(1f))
                    Switch(checked = vibrationEnabled, onCheckedChange = { vibrationEnabled = it })
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text("Result Display: ${resultTimer.toInt()}s", style = MaterialTheme.typography.labelMedium)
                Slider(value = resultTimer, onValueChange = { resultTimer = it }, valueRange = 1f..5f, steps = 3)
            }
        }
    }

    if (isBusy) {
        com.example.access.ui.components.LoadingOverlay(isVisible = true, message = "Importing Data...")
    }
}
