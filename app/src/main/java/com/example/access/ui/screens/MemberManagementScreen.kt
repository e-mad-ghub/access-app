package com.example.access.ui.screens

import com.example.access.BillingViewModel
import com.example.access.ui.components.PaywallDialog
import com.example.access.util.BillingManager
import com.android.billingclient.api.ProductDetails
import android.app.Activity
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.access.data.AppDatabase
import com.example.access.data.Config
import com.example.access.data.Member
import com.example.access.data.MemberDao
import com.example.access.util.DriveSyncManager
import com.example.access.util.FREE_TIER_MEMBER_LIMIT
import com.example.access.util.QrBadgeExporter
import com.example.access.util.SecurityUtils
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

private enum class MemberFilter(val label: String) {
    ALL("All"),
    ACTIVE("Active"),
    PAUSED("Paused"),
    MISSING_PHONE("Missing phone"),
    MISSING_EMAIL("Missing email")
}

@Composable
fun MemberManagementScreen(
    config: Config,
    billingViewModel: BillingViewModel,
    configFileId: String,
    onConfigUpdated: (Config) -> Unit,
    onUpgradeRequest: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    var showAddDialog by remember { mutableStateOf(false) }
    var memberToEdit by remember { mutableStateOf<Member?>(null) }
    var memberToDelete by remember { mutableStateOf<Member?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf<MemberFilter?>(null) }
    
    var isBusy by remember { mutableStateOf(false) }
var showPaywall by remember { mutableStateOf(false) }
val productDetails by billingViewModel.productDetails.collectAsState()
val billingState by billingViewModel.billingState.collectAsState()

    val members by db.memberDao().getAllMembers().observeAsState(emptyList())
    val usableMembers = remember(members, config.isPro) {
        if (config.isPro) members else members.take(FREE_TIER_MEMBER_LIMIT)
    }

    val filteredMembers = remember(searchQuery, selectedFilter, usableMembers) {
        val query = searchQuery.trim().lowercase()
        val baseMembers = when {
            query == "@all" || selectedFilter != null -> usableMembers
            query.isBlank() -> emptyList()
            else -> usableMembers.filter { m ->
                m.fullName.lowercase().contains(query) || m.memberId.lowercase().contains(query)
            }
        }
        baseMembers.filter { member ->
            when (selectedFilter) {
                null, MemberFilter.ALL -> true
                MemberFilter.ACTIVE -> member.status.equals("Active", ignoreCase = true)
                MemberFilter.PAUSED -> !member.status.equals("Active", ignoreCase = true)
                MemberFilter.MISSING_PHONE -> member.phone.isNullOrBlank()
                MemberFilter.MISSING_EMAIL -> member.email.isNullOrBlank()
            }
        }.sortedBy { it.fullName.lowercase() }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Members", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Text("Directory & Pass Management", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            FloatingActionButton(onClick = { if (!config.isPro && members.size >= FREE_TIER_MEMBER_LIMIT) showPaywall = true else showAddDialog = true }, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, shape = CircleShape, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Default.Add, contentDescription = null)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search by name, ID or type '@all'...") },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.primary) },
            trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, null) } },
            shape = RoundedCornerShape(16.dp),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MemberFilter.values().forEach { filter ->
                FilterChip(
                    selected = selectedFilter == filter,
                    onClick = { selectedFilter = if (selectedFilter == filter) null else filter },
                    label = { Text(filter.label) }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (!config.isPro && members.size > usableMembers.size) {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f))
            ) {
                Text(
                    "Free plan limit reached. Only the first $FREE_TIER_MEMBER_LIMIT members are active on this device. Upgrade to Pro to unlock the full database.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (searchQuery.isBlank() && selectedFilter == null) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(modifier = Modifier.size(80.dp), color = Color.White, shape = CircleShape, shadowElevation = 2.dp) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Badge, null, modifier = Modifier.size(40.dp), tint = Color.LightGray) }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Search to manage passes", color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 80.dp)) {
                items(filteredMembers, key = { it.memberId }) { member ->
                    MemberBadgeCard(member = member, config = config, onEdit = { memberToEdit = it }, onDelete = { memberToDelete = it }, onStatusChange = { isBusy = it })
                }
            }
        }
    }

    if (showAddDialog) {
        MemberEditDialog(config = config, onDismiss = { showAddDialog = false }, existingMembers = members) { name, p, e, a, nt ->
            isBusy = true
            scope.launch {
                withContext(Dispatchers.IO) {
                    val id = generateUniqueMemberId(db.memberDao())
                    val token = SecurityUtils.generateSecureQrToken()
                    val newMember = Member(
                        memberId = id,
                        fullName = name,
                        status = "Active",
                        qrCodeHash = token,
                        lastUpdated = Instant.now().toString(),
                        phone = p,
                        email = e,
                        address = a,
                        notes = nt
                    )
                    db.memberDao().insertMember(newMember)
                    val acc = GoogleSignIn.getLastSignedInAccount(context)
                    if (acc != null) {
                        val cred = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE)).apply { selectedAccount = acc.account }
                        DriveSyncManager(context, DriveSyncManager.createWithCredential(context, cred).drive).exportRoomToExcelAndUpload(config.activeDatabaseId, config.isPro)
                    }
                }
                isBusy = false
                showAddDialog = false
            }
        }
    }

    if (memberToEdit != null) {
        MemberEditDialog(member = memberToEdit, config = config, onDismiss = { memberToEdit = null }, existingMembers = members) { name, p, e, a, nt ->
            isBusy = true
            val m = memberToEdit!!
            scope.launch {
                withContext(Dispatchers.IO) {
                    db.memberDao().insertMember(Member(m.memberId, name, m.status, m.qrCodeHash, Instant.now().toString(), p, e, a, nt))
                    val acc = GoogleSignIn.getLastSignedInAccount(context)
                    if (acc != null) {
                        val cred = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE)).apply { selectedAccount = acc.account }
                        DriveSyncManager(context, DriveSyncManager.createWithCredential(context, cred).drive).exportRoomToExcelAndUpload(config.activeDatabaseId, config.isPro)
                    }
                }
                isBusy = false
                memberToEdit = null
            }
        }
    }

    if (memberToDelete != null) {
        AlertDialog(onDismissRequest = { memberToDelete = null }, title = { Text("Remove Pass") }, text = { Text("Permanently delete ${memberToDelete?.fullName}?") }, confirmButton = {
            Button(onClick = {
                val id = memberToDelete!!.memberId
                memberToDelete = null
                isBusy = true
                scope.launch {
                    withContext(Dispatchers.IO) {
                        db.memberDao().deleteMember(id)
                        val acc = GoogleSignIn.getLastSignedInAccount(context)
                        if (acc != null) {
                            val cred = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE)).apply { selectedAccount = acc.account }
                            DriveSyncManager(context, DriveSyncManager.createWithCredential(context, cred).drive).exportRoomToExcelAndUpload(config.activeDatabaseId, config.isPro)
                        }
                    }
                    isBusy = false
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
        }, dismissButton = { TextButton(onClick = { memberToDelete = null }) { Text("Cancel") } })
    }

if (showPaywall) {
        PaywallDialog(
            onDismissRequest = { showPaywall = false },
            onPurchaseClick = {
                val activity = context as Activity
                billingViewModel.launchPurchase(activity) { purchase ->
                    billingViewModel.activateProAfterPurchase(
                        purchase = purchase,
                        currentConfig = config,
                        configFileId = configFileId,
                        onConfigUpdated = onConfigUpdated
                    )
                    showPaywall = false
                }
            },
            productDetails = productDetails,
            isLoading = billingState is BillingManager.BillingState.LOADING || billingState is BillingManager.BillingState.PURCHASING,
            errorMessage = (billingState as? BillingManager.BillingState.ERROR)?.message
        )
    }
    if (isBusy) com.example.access.ui.components.LoadingOverlay(isVisible = true, message = "Updating...")
}

@Composable
fun MemberBadgeCard(member: Member, config: Config, onEdit: (Member) -> Unit, onDelete: (Member) -> Unit, onStatusChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val primaryColor = MaterialTheme.colorScheme.primary
    Surface(modifier = Modifier.fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(24.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF3F4F6)), shadowElevation = 2.dp) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(modifier = Modifier.size(52.dp).background(primaryColor.copy(alpha = 0.05f), CircleShape), contentAlignment = Alignment.Center) {
                    Text(member.fullName.take(1).uppercase(), fontWeight = FontWeight.Black, color = primaryColor, fontSize = 20.sp)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(member.fullName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = Color(0xFF263238))
                    Text("ID: ${member.memberId}", style = MaterialTheme.typography.labelSmall, color = Color.Gray, letterSpacing = 1.sp)
                }
                StatusPill(member.status)
            }
            val showPhone = config.branding.fieldConfig.showPhone && !member.phone.isNullOrBlank()
            val showEmail = config.branding.fieldConfig.showEmail && !member.email.isNullOrBlank()
            if (showPhone || showEmail) {
                Spacer(modifier = Modifier.height(16.dp))
                if (showPhone) DetailItem(Icons.Default.Phone, member.phone!!)
                if (showEmail) DetailItem(Icons.Default.Email, member.email!!)
            }
            Spacer(modifier = Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { QrBadgeExporter.exportAndSharePass(context, member, config.branding.organizationName, null) }, modifier = Modifier.weight(1.5f).height(44.dp), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(0.dp)) {
                    Icon(Icons.Default.QrCode, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("Share Pass", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = {
                    onStatusChange(true)
                    scope.launch {
                        val nextS = if (member.status == "Active") "Paused" else "Active"
                        withContext(Dispatchers.IO) { 
                            db.memberDao().updateStatus(member.memberId, nextS)
                            val acc = GoogleSignIn.getLastSignedInAccount(context)
                            if (acc != null) {
                                val cred = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE)).apply { selectedAccount = acc.account }
                                DriveSyncManager(context, DriveSyncManager.createWithCredential(context, cred).drive).exportRoomToExcelAndUpload(config.activeDatabaseId, config.isPro)
                            }
                        }
                        onStatusChange(false)
                    }
                }, modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(12.dp)) {
                    Text(if (member.status == "Active") "Pause" else "Resume", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = { onEdit(member) }, modifier = Modifier.size(44.dp).background(Color(0xFFF5F5F5), CircleShape)) { Icon(Icons.Default.Edit, null, tint = Color.Gray, modifier = Modifier.size(20.dp)) }
                IconButton(onClick = { onDelete(member) }, modifier = Modifier.size(44.dp).background(Color(0xFFFFEBEE), CircleShape)) { Icon(Icons.Default.Delete, null, tint = Color(0xFFD32F2F), modifier = Modifier.size(20.dp)) }
            }
        }
    }
}

@Composable
fun MemberEditDialog(member: Member? = null, existingMembers: List<Member>, config: Config, onDismiss: () -> Unit, onConfirm: suspend (String, String?, String?, String?, String?) -> Unit) {
    var name by remember { mutableStateOf(member?.fullName ?: "") }
    var phone by remember { mutableStateOf(member?.phone ?: "") }
    var email by remember { mutableStateOf(member?.email ?: "") }
    var address by remember { mutableStateOf(member?.address ?: "") }
    var notes by remember { mutableStateOf(member?.notes ?: "") }
    var isBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (member == null) "Register" else "Edit Profile") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            if (config.branding.fieldConfig.showPhone) OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            if (config.branding.fieldConfig.showEmail) OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            if (config.branding.fieldConfig.showAddress) OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Address") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            if (config.branding.fieldConfig.showNotes) OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
        }
    }, confirmButton = {
        Button(onClick = { isBusy = true; scope.launch { onConfirm(name.trim(), phone, email, address, notes) } }, enabled = name.isNotBlank() && !isBusy) { Text("Confirm") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun StatusPill(status: String) {
    val active = status == "Active"
    Surface(color = if (active) Color(0xFFECFDF5) else Color(0xFFF3F4F6), shape = CircleShape) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(6.dp).background(if (active) Color(0xFF10B981) else Color.Gray, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(status.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Black, color = if (active) Color(0xFF065F46) else Color.Gray)
        }
    }
}

private suspend fun generateUniqueMemberId(dao: MemberDao): String {
    while (true) {
        // 96 random bits keeps IDs compact while making collisions negligible;
        // the database check makes collision handling explicit rather than relying
        // on REPLACE, which could silently overwrite another member.
        val candidate = "M-${UUID.randomUUID().toString().replace("-", "").take(24).uppercase()}"
        if (!dao.memberIdExists(candidate)) return candidate
    }
}

@Composable
fun DetailItem(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.LightGray, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 11.sp, color = Color.Gray)
    }
}
