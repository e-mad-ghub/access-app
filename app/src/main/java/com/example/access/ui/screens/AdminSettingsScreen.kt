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
import androidx.compose.runtime.livedata.observeAsState
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
import com.easyapps.easypass.BuildConfig
import com.example.access.data.AppDatabase
import com.example.access.data.Config
import com.example.access.util.DriveSyncManager
import com.example.access.util.FREE_TIER_MEMBER_LIMIT
import com.example.access.util.ImportMode
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
    var pendingImport by remember { mutableStateOf<ImportResult?>(null) }
    var importMode by remember { mutableStateOf(ImportMode.ADD) }
    val db = remember { AppDatabase.getDatabase(context) }
    val existingMembers by db.memberDao().getAllMembers().observeAsState(emptyList())

    fun shareSpreadsheet(file: java.io.File, title: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title))
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            isBusy = true
            scope.launch {
                val account = GoogleSignIn.getLastSignedInAccount(context)
                if (account == null) {
                    isBusy = false
                    Toast.makeText(context, "Google sign-in is required to update the shared database.", Toast.LENGTH_LONG).show()
                    return@launch
                }
                val cred = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE)).apply { selectedAccount = account.account }
                val sync = DriveSyncManager(context, syncManagerFromCred(context, cred).drive)
                context.contentResolver.openInputStream(it)?.use { stream ->
                    val result = sync.previewLocalSheet(stream)
                    if (result != null) {
                        pendingImport = result
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
                    Text(
                        "Import and export Excel files safely. Preview changes before updating your shared database. Customer imports need headers: Name, Phone, Email, Address, Notes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val account = GoogleSignIn.getLastSignedInAccount(context) ?: return@launch
                                val cred = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE)).apply { selectedAccount = account.account }
                                val sync = DriveSyncManager(context, syncManagerFromCred(context, cred).drive)
                                val file = sync.exportLocalBackup(config.isPro)
                                file?.let { shareSpreadsheet(it, "Share EasyPass Backup") }
                            }
                        }, 
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Download, null)
                        Spacer(Modifier.width(12.dp))
                        Text("Export EasyPass Backup")
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val account = GoogleSignIn.getLastSignedInAccount(context) ?: return@launch
                                val cred = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE)).apply { selectedAccount = account.account }
                                val sync = DriveSyncManager(context, syncManagerFromCred(context, cred).drive)
                                sync.exportImportTemplate()?.let { shareSpreadsheet(it, "Share Import Template") }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Description, null)
                        Spacer(Modifier.width(12.dp))
                        Text("Download Import Template")
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

        if (activeRole != SessionManager.ROLE_SCANNER) {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Roles & Access", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    RoleInfoRow(Icons.Default.QrCodeScanner, "Scanner", "Scan passes only. No import, export, billing, or database editing.")
                    RoleInfoRow(Icons.Default.AdminPanelSettings, "Admin", "Manage members and import/export organization data.")
                    RoleInfoRow(Icons.Default.VpnKey, "Owner", "Manage billing, branding, organization settings, and access keys.")
                }
            }
        }
        
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                "EasyPass v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.labelSmall,
                color = Color.LightGray
            )
        }
        
        Spacer(modifier = Modifier.height(100.dp))
    }

    pendingImport?.let { result ->
        fun normalized(value: String?): String =
            value.orEmpty().trim().lowercase().replace(Regex("[^a-z0-9@+]"), "")

        fun duplicateKeys(member: com.example.access.data.Member): List<String> {
            val keys = mutableListOf<String>()
            val email = normalized(member.email)
            if (email.isNotBlank()) keys += "email:$email"
            val phone = normalized(member.phone)
            if (phone.isNotBlank()) keys += "phone:$phone"
            return keys
        }

        val existingKeys = existingMembers.flatMap(::duplicateKeys).toSet()
        val newMembersForAdd = result.members.filter { member ->
            duplicateKeys(member).none { it in existingKeys }
        }
        val existingDuplicateCount = result.members.size - newMembersForAdd.size
        val importMembers = if (importMode == ImportMode.ADD) newMembersForAdd else result.members
        val projectedCount = if (importMode == ImportMode.ADD) existingMembers.size + importMembers.size else importMembers.size
        val duplicateCount = result.duplicateRows.size + if (importMode == ImportMode.ADD) existingDuplicateCount else 0
        val freeLimitBlocked = !config.isPro && projectedCount > FREE_TIER_MEMBER_LIMIT

        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Preview Import", fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Choose how EasyPass should apply this spreadsheet before updating your shared database.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = importMode == ImportMode.ADD,
                            onClick = { importMode = ImportMode.ADD },
                            label = { Text("Add to existing") }
                        )
                        FilterChip(
                            selected = importMode == ImportMode.OVERWRITE,
                            onClick = { importMode = ImportMode.OVERWRITE },
                            label = { Text("Overwrite") }
                        )
                    }
                    Text("Valid rows: ${result.originalValidMemberCount}", style = MaterialTheme.typography.bodyMedium)
                    Text("Duplicates skipped: $duplicateCount", style = MaterialTheme.typography.bodyMedium)
                    Text("Malformed rows skipped: ${result.skippedRows.size}", style = MaterialTheme.typography.bodyMedium)
                    Text("Projected final members: $projectedCount", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    val names = importMembers.take(5).joinToString { it.fullName }
                    if (names.isNotBlank()) {
                        Text("Preview: $names", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    if (freeLimitBlocked) {
                        Text(
                            "Free organizations can manage up to $FREE_TIER_MEMBER_LIMIT members. This import would create $projectedCount members. Upgrade to Pro to continue.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isBusy = true
                        scope.launch {
                            val account = GoogleSignIn.getLastSignedInAccount(context)
                            if (account == null) {
                                Toast.makeText(context, "Google sign-in is required to update the shared database.", Toast.LENGTH_LONG).show()
                                isBusy = false
                                return@launch
                            }
                            try {
                                val cred = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE)).apply { selectedAccount = account.account }
                                val sync = DriveSyncManager(context, syncManagerFromCred(context, cred).drive)
                                sync.applyImportResult(result, importMode, config.isPro)
                                val uploaded = sync.exportRoomToExcelAndUpload(config.activeDatabaseId, config.isPro)
                                if (uploaded) {
                                    val msg = "Import complete: $projectedCount active members, $duplicateCount duplicates skipped, ${result.skippedRows.size} malformed rows skipped."
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    pendingImport = null
                                } else {
                                    Toast.makeText(context, "Import saved locally, but Drive upload failed. Try manual sync when connection is restored.", Toast.LENGTH_LONG).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "Import failed. No Drive update was completed.", Toast.LENGTH_LONG).show()
                            } finally {
                                isBusy = false
                            }
                        }
                    },
                    enabled = !freeLimitBlocked && !isBusy
                ) { Text("Confirm Import") }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Cancel") } }
        )
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

@Composable
private fun RoleInfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            Text(body, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
}

private fun syncManagerFromCred(context: Context, cred: GoogleAccountCredential): DriveSyncManager {
    return DriveSyncManager.createWithCredential(context, cred)
}
