package com.example.access.ui.screens

import com.example.access.BillingViewModel
import com.example.access.ui.components.PaywallDialog
import com.example.access.util.BillingManager
import com.android.billingclient.api.ProductDetails
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.FileProvider
import com.example.access.data.AppDatabase
import com.example.access.data.Config
import com.example.access.data.Member
import com.example.access.data.MemberDao
import com.example.access.ui.components.ProfessionalActionRow
import com.example.access.ui.components.ProfessionalInfoText
import com.example.access.ui.components.ProfessionalPageHeader
import com.example.access.ui.components.ProfessionalStatusChip
import com.example.access.util.DriveSyncManager
import com.example.access.util.FREE_TIER_MEMBER_LIMIT
import com.example.access.util.ImportMode
import com.example.access.util.ImportResult
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
    SUSPENDED("Suspended"),
    MISSING_PHONE("Missing phone"),
    MISSING_EMAIL("Missing email")
}

@OptIn(ExperimentalMaterial3Api::class)
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
    var showDataTools by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<ImportResult?>(null) }
    var importMode by remember { mutableStateOf(ImportMode.ADD) }
    
    var isBusy by remember { mutableStateOf(false) }
    var showPaywall by remember { mutableStateOf(false) }
    val productDetails by billingViewModel.productDetails.collectAsState()
    val billingState by billingViewModel.billingState.collectAsState()

    val members by db.memberDao().getAllMembers().observeAsState(emptyList())
    val usableMembers = remember(members, config.isPro) {
        if (config.isPro) members else members.take(FREE_TIER_MEMBER_LIMIT)
    }

    fun normalized(value: String?): String =
        value.orEmpty().trim().lowercase().replace(Regex("[^a-z0-9@+]"), "")

    fun memberMatchesSearch(member: Member, query: String): Boolean {
        val normalizedQuery = normalized(query)
        val searchableFields = listOf(
            member.fullName,
            member.memberId,
            member.status,
            member.phone.orEmpty(),
            member.email.orEmpty(),
            member.address.orEmpty(),
            member.notes.orEmpty(),
            member.lastUpdated
        )
        return searchableFields.any { it.lowercase().contains(query) } ||
            normalizedQuery.isNotBlank() && listOf(member.phone, member.email).any {
                normalized(it).contains(normalizedQuery)
            }
    }

    val filteredMembers = remember(searchQuery, selectedFilter, usableMembers) {
        val query = searchQuery.trim().lowercase()
        val baseMembers = when {
            query == "@all" || selectedFilter != null -> usableMembers
            query.isBlank() -> emptyList()
            else -> usableMembers.filter { memberMatchesSearch(it, query) }
        }
        baseMembers.filter { member ->
            when (selectedFilter) {
                null, MemberFilter.ALL -> true
                MemberFilter.ACTIVE -> member.status.equals("Active", ignoreCase = true)
                MemberFilter.SUSPENDED -> !member.status.equals("Active", ignoreCase = true)
                MemberFilter.MISSING_PHONE -> member.phone.isNullOrBlank()
                MemberFilter.MISSING_EMAIL -> member.email.isNullOrBlank()
            }
        }.sortedBy { it.fullName.lowercase() }
    }

    fun shareSpreadsheet(file: java.io.File, title: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title))
    }

    fun driveSyncOrNull(): DriveSyncManager? {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account == null) {
            Toast.makeText(context, "Google sign-in is required to update the shared database.", Toast.LENGTH_LONG).show()
            return null
        }
        val cred = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE)).apply {
            selectedAccount = account.account
        }
        return DriveSyncManager(context, DriveSyncManager.createWithCredential(context, cred).drive)
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            isBusy = true
            scope.launch {
                try {
                    val sync = driveSyncOrNull() ?: return@launch
                    context.contentResolver.openInputStream(it)?.use { stream ->
                        val result = sync.previewLocalSheet(stream)
                        if (result != null) {
                            pendingImport = result
                            showDataTools = false
                        } else {
                            Toast.makeText(context, "Import failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                } finally {
                    isBusy = false
                }
            }
        }
    }

    fun updateMemberStatus(member: Member) {
        isBusy = true
        scope.launch {
            try {
                val nextStatus = if (member.status == "Active") "Suspended" else "Active"
                withContext(Dispatchers.IO) {
                    db.memberDao().updateStatus(member.memberId, nextStatus)
                    val account = GoogleSignIn.getLastSignedInAccount(context)
                    if (account != null) {
                        val cred = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE)).apply {
                            selectedAccount = account.account
                        }
                        DriveSyncManager(context, DriveSyncManager.createWithCredential(context, cred).drive)
                            .exportRoomToExcelAndUpload(config.activeDatabaseId, config.isPro)
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Status updated locally, but Drive sync failed. Try manual sync.", Toast.LENGTH_LONG).show()
            } finally {
                isBusy = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        ProfessionalPageHeader(
            title = "Members",
            subtitle = "Directory and pass management for this organization.",
            action = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmallFloatingActionButton(
                        onClick = { showDataTools = true },
                        containerColor = Color.White,
                        contentColor = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(Icons.Default.Storage, contentDescription = "Bulk data tools")
                    }
                    FloatingActionButton(
                        onClick = { if (!config.isPro && members.size >= FREE_TIER_MEMBER_LIMIT) showPaywall = true else showAddDialog = true },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add member")
                    }
                }
            }
        )

        if (showDataTools) {
            ModalBottomSheet(onDismissRequest = { showDataTools = false }) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Member data tools", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    ProfessionalInfoText("Import and export Excel files safely. Customer imports need headers: Name, Phone, Email, Address, Notes.")
                    ProfessionalActionRow(
                        icon = Icons.Default.Download,
                        title = "Export EasyPass backup",
                        body = "Download a full app backup spreadsheet.",
                        onClick = {
                            scope.launch {
                                val sync = driveSyncOrNull() ?: return@launch
                                sync.exportLocalBackup(config.isPro)?.let { shareSpreadsheet(it, "Share EasyPass Backup") }
                            }
                        }
                    )
                    ProfessionalActionRow(
                        icon = Icons.Default.Description,
                        title = "Download import template",
                        body = "Create a blank spreadsheet with the supported import columns.",
                        onClick = {
                            scope.launch {
                                val sync = driveSyncOrNull() ?: return@launch
                                sync.exportImportTemplate()?.let { shareSpreadsheet(it, "Share Import Template") }
                            }
                        }
                    )
                    ProfessionalActionRow(
                        icon = Icons.Default.Upload,
                        title = "Bulk import spreadsheet",
                        body = "Preview valid, skipped, and duplicate rows before updating.",
                        onClick = { importLauncher.launch("*/*") }
                    )
                    Spacer(Modifier.height(18.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search by name, ID or type '@all'...") },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.primary) },
            trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, null) } },
            shape = RoundedCornerShape(18.dp),
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
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f)
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
                    Surface(modifier = Modifier.size(72.dp), color = Color.White, shape = RoundedCornerShape(18.dp), shadowElevation = 1.dp) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Badge, null, modifier = Modifier.size(40.dp), tint = Color.LightGray) }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Search to manage passes", color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 80.dp)) {
                items(filteredMembers, key = { it.memberId }) { member ->
                    MemberBadgeCard(
                        member = member,
                        config = config,
                        onEdit = { memberToEdit = it },
                        onDelete = { memberToDelete = it },
                        onStatusChange = { updateMemberStatus(it) }
                    )
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

    pendingImport?.let { result ->
        fun duplicateKeys(member: Member): List<String> {
            val keys = mutableListOf<String>()
            val email = normalized(member.email)
            if (email.isNotBlank()) keys += "email:$email"
            val phone = normalized(member.phone)
            if (phone.isNotBlank()) keys += "phone:$phone"
            return keys
        }

        val existingKeys = members.flatMap(::duplicateKeys).toSet()
        val newMembersForAdd = result.members.filter { member ->
            duplicateKeys(member).none { it in existingKeys }
        }
        val existingDuplicateCount = result.members.size - newMembersForAdd.size
        val importMembers = if (importMode == ImportMode.ADD) newMembersForAdd else result.members
        val projectedCount = if (importMode == ImportMode.ADD) members.size + importMembers.size else importMembers.size
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
                            try {
                                val sync = driveSyncOrNull() ?: return@launch
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
fun MemberBadgeCard(member: Member, config: Config, onEdit: (Member) -> Unit, onDelete: (Member) -> Unit, onStatusChange: (Member) -> Unit) {
    val context = LocalContext.current
    val primaryColor = MaterialTheme.colorScheme.primary
    Surface(modifier = Modifier.fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFD0D5DD)), shadowElevation = 0.dp) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(modifier = Modifier.size(48.dp).background(primaryColor.copy(alpha = 0.08f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    Text(member.fullName.take(1).uppercase(), fontWeight = FontWeight.SemiBold, color = primaryColor, fontSize = 20.sp)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(member.fullName, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, color = Color(0xFF111827))
                    Text("ID: ${member.memberId}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF667085))
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
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { QrBadgeExporter.exportAndSharePass(context, member, config.branding.organizationName, null) },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(Icons.Default.QrCode, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share QR pass", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onStatusChange(member) },
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Text(if (member.status == "Active") "Suspend" else "Reactivate", maxLines = 1, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(onClick = { onEdit(member) }, modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Edit", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = { onDelete(member) },
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Delete", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
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
    ProfessionalStatusChip(if (active) "ACTIVE" else "SUSPENDED", if (active) Color(0xFF039855) else Color(0xFF667085))
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
