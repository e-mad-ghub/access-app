package com.example.access.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.access.data.AppDatabase
import com.example.access.data.Config
import com.example.access.data.Member
import com.example.access.util.DriveSyncManager
import com.example.access.util.QrBadgeExporter
import com.example.access.util.SecurityUtils
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MemberManagementScreen(config: Config) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    var showAddDialog by remember { mutableStateOf(false) }
    var memberToEdit by remember { mutableStateOf<Member?>(null) }
    var memberToDelete by remember { mutableStateOf<Member?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    
    var isBusy by remember { mutableStateOf(false) }

    val members by db.memberDao().getAllMembers().observeAsState(emptyList())

    val filteredMembers = remember(searchQuery, members) {
        val query = searchQuery.trim().lowercase()
        if (query.isBlank()) {
            emptyList()
        } else if (query == "all") {
            members.sortedBy { it.fullName.lowercase() }
        } else {
            members.filter { member ->
                val nameWords = member.fullName.lowercase().split(" ")
                val matchesName = nameWords.any { it.startsWith(query) }
                val matchesId = member.memberId.lowercase().startsWith(query)
                matchesName || matchesId
            }.sortedBy { it.fullName.lowercase() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        // --- HEADER ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Members", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Text("Directory & Pass Management", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape,
                modifier = Modifier.size(56.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search by name or key ID...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, contentDescription = null) } },
            shape = RoundedCornerShape(16.dp),
            colors = TextFieldDefaults.colors(
                unfocusedContainerColor = Color.White,
                focusedContainerColor = Color.White,
                unfocusedIndicatorColor = Color.Transparent,
                focusedIndicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            ),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (searchQuery.isBlank()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        modifier = Modifier.size(80.dp),
                        color = Color.White,
                        shape = CircleShape,
                        shadowElevation = 2.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Badge, contentDescription = null, modifier = Modifier.size(40.dp), tint = Color.LightGray)
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Search to manage passes", color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(filteredMembers, key = { it.memberId }) { member ->
                    MemberBadgeCard(
                        member = member, 
                        config = config, 
                        onEdit = { memberToEdit = it },
                        onDelete = { memberToDelete = it },
                        onStatusChange = { isBusy = it }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        MemberEditDialog(
            existingMembers = members,
            config = config,
            onDismiss = { showAddDialog = false }
        ) { name, phone, email, address, notes ->
            isBusy = true
            scope.launch {
                val id = "M" + (1000..9999).random()
                val hash = SecurityUtils.generateSecureQRHash(id)
                val newMember = Member(id, name, "Active", hash, System.currentTimeMillis().toString(), phone, email, address, notes)
                
                withContext(Dispatchers.IO) {
                    db.memberDao().insertMember(newMember)
                    val account = GoogleSignIn.getLastSignedInAccount(context)
                    if (account != null) {
                        val cred = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE)).apply { selectedAccount = account.account }
                        DriveSyncManager(context, cred).exportRoomToExcelAndUpload(config.activeDatabaseId)
                    }
                }
                isBusy = false
                showAddDialog = false
            }
        }
    }

    if (memberToEdit != null) {
        MemberEditDialog(
            member = memberToEdit,
            existingMembers = members,
            config = config,
            onDismiss = { memberToEdit = null }
        ) { name, phone, email, address, notes ->
            isBusy = true
            val targetId = memberToEdit!!.memberId
            val targetHash = memberToEdit!!.qrCodeHash
            val targetStatus = memberToEdit!!.status
            scope.launch {
                withContext(Dispatchers.IO) {
                    db.memberDao().insertMember(Member(targetId, name, targetStatus, targetHash, System.currentTimeMillis().toString(), phone, email, address, notes))
                    val account = GoogleSignIn.getLastSignedInAccount(context)
                    if (account != null) {
                        val cred = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE)).apply { selectedAccount = account.account }
                        DriveSyncManager(context, cred).exportRoomToExcelAndUpload(config.activeDatabaseId)
                    }
                }
                isBusy = false
                memberToEdit = null
            }
        }
    }

    if (memberToDelete != null) {
        AlertDialog(
            onDismissRequest = { memberToDelete = null },
            title = { Text("Remove Access Pass") },
            text = { Text("Are you sure you want to delete ${memberToDelete?.fullName}? This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        val idToDelete = memberToDelete!!.memberId
                        memberToDelete = null
                        isBusy = true
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                db.memberDao().deleteMember(idToDelete)
                                val account = GoogleSignIn.getLastSignedInAccount(context)
                                if (account != null) {
                                    val cred = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE)).apply { selectedAccount = account.account }
                                    DriveSyncManager(context, cred).exportRoomToExcelAndUpload(config.activeDatabaseId)
                                }
                            }
                            isBusy = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete Forever")
                }
            },
            dismissButton = {
                TextButton(onClick = { memberToDelete = null }) { Text("Keep Member") }
            }
        )
    }

    if (isBusy) {
        com.example.access.ui.components.LoadingOverlay(isVisible = true, message = "Updating Directory...")
    }
}

@Composable
fun MemberBadgeCard(
    member: Member, 
    config: Config, 
    onEdit: (Member) -> Unit,
    onDelete: (Member) -> Unit,
    onStatusChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val primaryColor = MaterialTheme.colorScheme.primary

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White,
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF3F4F6)),
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier.size(52.dp).background(primaryColor.copy(alpha = 0.05f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(member.fullName.take(1).uppercase(), fontWeight = FontWeight.Black, color = primaryColor, fontSize = 20.sp)
                }
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(member.fullName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = Color(0xFF263238))
                    Text("ID: ${member.memberId}", style = MaterialTheme.typography.labelSmall, color = Color.Gray, letterSpacing = 1.sp)
                }

                StatusPill(member.status)
            }

            // Information rows based on Config and data presence
            val showPhone = config.branding.fieldConfig.showPhone && !member.phone.isNullOrBlank()
            val showEmail = config.branding.fieldConfig.showEmail && !member.email.isNullOrBlank()
            val showAddress = config.branding.fieldConfig.showAddress && !member.address.isNullOrBlank()
            val showNotes = config.branding.fieldConfig.showNotes && !member.notes.isNullOrBlank()

            if (showPhone || showEmail || showAddress || showNotes) {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = Color(0xFFF9FAFB))
                Spacer(modifier = Modifier.height(12.dp))
                
                if (showPhone) DetailItem(Icons.Default.Phone, member.phone!!)
                if (showEmail) DetailItem(Icons.Default.Email, member.email!!)
                if (showAddress) DetailItem(Icons.Default.LocationOn, member.address!!)
                if (showNotes) DetailItem(Icons.Default.Notes, member.notes!!)
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { QrBadgeExporter.exportAndSharePass(context, member, config.branding.organizationName, null) },
                    modifier = Modifier.weight(1.5f).height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Default.QrCode, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share Pass", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = {
                        onStatusChange(true)
                        scope.launch {
                            val nextS = if (member.status == "Active") "Paused" else "Active"
                            withContext(Dispatchers.IO) { 
                                db.memberDao().updateStatus(member.memberId, nextS)
                                val acc = GoogleSignIn.getLastSignedInAccount(context)
                                if (acc != null) {
                                    val cred = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE)).apply { selectedAccount = acc.account }
                                    DriveSyncManager(context, cred).exportRoomToExcelAndUpload(config.activeDatabaseId)
                                }
                            }
                            onStatusChange(false)
                        }
                    },
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (member.status == "Active") "Pause" else "Resume", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                IconButton(onClick = { onEdit(member) }, modifier = Modifier.size(44.dp).background(Color(0xFFF5F5F5), CircleShape)) {
                    Icon(Icons.Default.Edit, null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                }

                IconButton(onClick = { onDelete(member) }, modifier = Modifier.size(44.dp).background(Color(0xFFFFEBEE), CircleShape)) {
                    Icon(Icons.Default.Delete, null, tint = Color(0xFFD32F2F), modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
fun MemberEditDialog(
    member: Member? = null,
    existingMembers: List<Member>,
    config: Config,
    onDismiss: () -> Unit,
    onConfirm: suspend (String, String?, String?, String?, String?) -> Unit
) {
    var name by remember { mutableStateOf(member?.fullName ?: "") }
    var phone by remember { mutableStateOf(member?.phone ?: "") }
    var email by remember { mutableStateOf(member?.email ?: "") }
    var address by remember { mutableStateOf(member?.address ?: "") }
    var notes by remember { mutableStateOf(member?.notes ?: "") }
    var isCreating by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = if (isCreating) ({}) else onDismiss,
        title = { Text(if (member == null) "Register New Member" else "Edit Member Profile", fontWeight = FontWeight.Black) },
        text = { 
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it; err = null }, label = { Text("Full Name") }, modifier = Modifier.fillMaxWidth(), isError = err != null, shape = RoundedCornerShape(12.dp), enabled = !isCreating)
                if (config.branding.fieldConfig.showPhone) OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), enabled = !isCreating)
                if (config.branding.fieldConfig.showEmail) OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), enabled = !isCreating)
                if (config.branding.fieldConfig.showAddress) OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Address") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), enabled = !isCreating)
                if (config.branding.fieldConfig.showNotes) OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), enabled = !isCreating)
                if (err != null) Text(err!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (member == null && existingMembers.any { it.fullName.equals(name.trim(), true) }) err = "Name already exists"
                    else { isCreating = true; scope.launch { onConfirm(name.trim(), phone, email, address, notes) } }
                },
                enabled = name.isNotBlank() && !isCreating
            ) { 
                if (isCreating) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                else Text("Confirm")
            }
        },
        dismissButton = { if (!isCreating) TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun StatusPill(status: String) {
    val active = status == "Active"
    Surface(
        color = if (active) Color(0xFFECFDF5) else Color(0xFFF3F4F6),
        shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (active) Color(0xFF10B981).copy(alpha = 0.2f) else Color.Transparent)
    ) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(6.dp).background(if (active) Color(0xFF10B981) else Color.Gray, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(status.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Black, color = if (active) Color(0xFF065F46) else Color.Gray)
        }
    }
}

@Composable
fun DetailItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Icon(icon, null, tint = Color.LightGray, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Medium)
    }
}
