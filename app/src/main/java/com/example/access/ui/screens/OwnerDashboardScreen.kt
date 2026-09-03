package com.example.access.ui.screens

import com.example.access.BillingViewModel
import android.content.Context
import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import com.example.access.data.Config
import com.example.access.data.FieldConfig
import com.example.access.data.AccessRosterEntry
import com.example.access.ui.components.ProfessionalActionRow
import com.example.access.ui.components.ProfessionalPageHeader
import com.example.access.ui.components.ProfessionalScreen
import com.example.access.ui.components.ProfessionalSectionCard
import com.example.access.ui.components.ProfessionalStatusChip
import com.example.access.util.DriveSyncManager
import com.example.access.util.OrganizationAccessCapabilities
import com.example.access.util.RevokeOrganizationAccessResult
import com.example.access.util.SecurityUtils
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.drive.DriveScopes
import com.example.access.ui.components.PaywallDialog
import com.example.access.util.BillingManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.access.util.SessionManager
import java.io.File
import java.io.FileOutputStream

data class ThemePalette(val name: String, val primary: String, val secondary: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OwnerDashboardScreen(
    config: Config,
    billingViewModel: BillingViewModel,
    configFileId: String,
    onConfigUpdated: (Config) -> Unit,
    onRevokeAllUsersPermission: (RevokeOrganizationAccessResult) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var orgName by remember { mutableStateOf(config.branding.organizationName) }
    var selectedColor by remember { mutableStateOf(config.branding.primaryColor) }
    var passwordChangeRole by remember { mutableStateOf<String?>(null) }
    var activeFolderName by remember { mutableStateOf("Loading...") }
    var folderLink by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    var showPhone by remember { mutableStateOf(config.branding.fieldConfig.showPhone) }
    var showEmail by remember { mutableStateOf(config.branding.fieldConfig.showEmail) }
    var showAddress by remember { mutableStateOf(config.branding.fieldConfig.showAddress) }
    var showNotes by remember { mutableStateOf(config.branding.fieldConfig.showNotes) }
    var pickedImageUri by remember { mutableStateOf<Uri?>(null) }
    var showCropDialog by remember { mutableStateOf(false) }
    var showUpgradeDialog by remember { mutableStateOf(false) }
    var showRevokeDialog by remember { mutableStateOf(false) }
    var showInviteDialog by remember { mutableStateOf(false) }
    var accessEntryToRemove by remember { mutableStateOf<AccessRosterEntry?>(null) }
    var accessCapabilities by remember { mutableStateOf<OrganizationAccessCapabilities?>(null) }
    val productDetails by billingViewModel.productDetails.collectAsState()
    val billingState by billingViewModel.billingState.collectAsState()

    val palettes = listOf(
        ThemePalette("Midnight Pro", "#1A237E", "#5C6BC0"),
        ThemePalette("Emerald Cloud", "#006064", "#26A69A"),
        ThemePalette("Ruby SaaS", "#B71C1C", "#EF5350"),
        ThemePalette("Forest Flow", "#1B5E20", "#66BB6A"),
        ThemePalette("Amber Access", "#E65100", "#FFB74D"),
        ThemePalette("Violet Pass", "#4A148C", "#AB47BC")
    )

    val account = remember { GoogleSignIn.getLastSignedInAccount(context) }
    val syncManager = remember(account) {
        account?.let {
            val cred = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE)).apply { selectedAccount = it.account }
            DriveSyncManager(context, DriveSyncManager.createWithCredential(context, cred).drive)
        }
    }
    val signedInEmail = remember(account) { account?.email?.trim()?.lowercase() }

    LaunchedEffect(config.activeDatabaseId, config.accessRoster, config.branding.logoFileId) {
        syncManager?.let { sync ->
            activeFolderName = sync.getFolderName(config.activeDatabaseId)
            accessCapabilities = sync.getOrganizationAccessCapabilities(config, configFileId, signedInEmail)
        }
    }

    val logoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { pickedImageUri = uri; showCropDialog = true }
    }

    if (showCropDialog && pickedImageUri != null) {
        LogoCropDialog(uri = pickedImageUri!!, onDismiss = { showCropDialog = false }, onConfirm = { croppedBitmap ->
            showCropDialog = false; isBusy = true
            scope.launch { uploadCroppedLogo(context, croppedBitmap, { onConfigUpdated(it); isBusy = false }, config) }
        })
    }

    ProfessionalScreen(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp)) {
        ProfessionalPageHeader(
            title = "Director Control Center",
            subtitle = "Manage plan, branding, access keys, and organization storage."
        )

        ProfessionalSectionCard(
            title = "Plan status",
            subtitle = if (config.isPro) "Organization-wide Pro access is active." else "Free plan is active for this organization.",
            icon = Icons.Default.WorkspacePremium
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Current plan", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    ProfessionalStatusChip(
                        text = if (config.isPro) "PRO ACTIVE" else "FREE PLAN",
                        color = if (config.isPro) MaterialTheme.colorScheme.primary else Color(0xFF667085)
                    )
                }
                Text(
                    if (config.isPro) {
                        "All connected devices use Pro features."
                    } else {
                        "Upgrade once for this organization when you need unlimited members, branding, member field controls, and storage relocation."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!config.isPro) {
                    Button(
                        onClick = { showUpgradeDialog = true },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.WorkspacePremium, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Upgrade to Pro", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        OperationCard(
            title = "Brand and appearance",
            subtitle = "These controls change how this organization appears on connected devices.",
            icon = Icons.Default.Palette
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Surface(modifier = Modifier.size(100.dp), shape = CircleShape, border = BorderStroke(1.dp, Color(0xFFEEEEEE)), shadowElevation = 2.dp) {
                        if (config.branding.logoFileId != null) {
                            AsyncImage(model = "https://lh3.googleusercontent.com/u/0/d/${config.branding.logoFileId}?v=${System.currentTimeMillis()}", contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.padding(12.dp))
                        } else { Icon(Icons.Default.Business, null, modifier = Modifier.size(48.dp), tint = Color.LightGray) }
                    }
                }
                Button(
                    onClick = {
                        if (config.isPro) logoLauncher.launch("image/*") else showUpgradeDialog = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(if (config.isPro) Icons.Default.Upload else Icons.Default.WorkspacePremium, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (config.isPro) "Update logo" else "Upgrade to update logo")
                }
                Text(
                    "Branding changes are written to the shared Google Drive database so connected devices stay consistent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                OutlinedTextField(value = orgName, onValueChange = { orgName = it }, label = { Text("Organization name") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    palettes.forEach { palette ->
                        Surface(
                            onClick = { selectedColor = palette.primary },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = Color.White,
                            border = BorderStroke(
                                if (selectedColor == palette.primary) 1.5.dp else 1.dp,
                                if (selectedColor == palette.primary) MaterialTheme.colorScheme.primary else Color(0xFFD0D5DD)
                            )
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = selectedColor == palette.primary,
                                    onClick = { selectedColor = palette.primary }
                                )
                                Spacer(Modifier.width(8.dp))
                                Box(modifier = Modifier.size(24.dp).background(Color(android.graphics.Color.parseColor(palette.primary)), CircleShape))
                                Spacer(Modifier.width(12.dp))
                                Text(palette.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                Button(
                    onClick = {
                        if (!config.isPro) {
                            showUpgradeDialog = true
                            return@Button
                        }
                        isBusy = true
                        val updated = config.copy(branding = config.branding.copy(organizationName = orgName, primaryColor = selectedColor))
                        updateConfigOnDrive(context, updated) { onConfigUpdated(it); isBusy = false }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(if (config.isPro) Icons.Default.Save else Icons.Default.WorkspacePremium, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (config.isPro) "Save brand profile" else "Upgrade to save brand profile")
                }
            }
        }
        OperationCard(
            title = "Member data fields",
            subtitle = "Choose which optional fields appear when managers manage member records.",
            icon = Icons.Default.ViewList
        ) {
            Column {
                FieldToggleRow("Phone Access", showPhone) { showPhone = it }
                FieldToggleRow("Email Contact", showEmail) { showEmail = it }
                FieldToggleRow("Physical Address", showAddress) { showAddress = it }
                FieldToggleRow("Management Notes", showNotes) { showNotes = it }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (!config.isPro) {
                            showUpgradeDialog = true
                            return@Button
                        }
                        isBusy = true
                        val updated = config.copy(branding = config.branding.copy(fieldConfig = FieldConfig(showPhone, showEmail, showAddress, showNotes)))
                        updateConfigOnDrive(context, updated) { onConfigUpdated(it); isBusy = false }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(if (config.isPro) Icons.Default.Save else Icons.Default.WorkspacePremium, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (config.isPro) "Update member fields" else "Upgrade to update member fields")
                }
            }
        }
        OperationCard(
            title = "Security credentials",
            subtitle = "Change management passwords separately so Manager and Director access stays controlled.",
            icon = Icons.Default.Security
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Use these actions only when you want to replace a role password. Existing passwords stay unchanged until you save a new one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                OutlinedButton(
                    onClick = { passwordChangeRole = SessionManager.ROLE_MANAGER },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.AdminPanelSettings, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Change manager password", fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = { passwordChangeRole = SessionManager.ROLE_DIRECTOR },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.VpnKey, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Change director password", fontWeight = FontWeight.SemiBold)
                }
            }
        }
        OperationCard(
            title = "Database storage",
            subtitle = "See where the shared organization database is stored in Google Drive.",
            icon = Icons.Default.Storage
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ProfessionalActionRow(
                    icon = Icons.Default.Folder,
                    title = activeFolderName,
                    body = "Current shared database folder in Google Drive.",
                    trailing = {
                        ProfessionalStatusChip("CURRENT", Color(0xFF667085))
                    }
                )
                Text(
                    "This folder holds the easyPass organization database. Only the storage-owning Director can relocate or replace organization storage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                OutlinedTextField(value = folderLink, onValueChange = { folderLink = it }, label = { Text("Migrate Link") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                Button(
                    onClick = {
                        if (!config.isPro) {
                            showUpgradeDialog = true
                            return@Button
                        }
                        val ext = extractFolderIdFromLink(folderLink)
                        if (ext != null) {
                            isBusy = true
                            scope.launch {
                                if (accessCapabilities?.canManageAccess == true && syncManager?.verifyFolderPermissions(ext) == true) {
                                    relocate(context, ext) { onConfigUpdated(it); isBusy = false; folderLink = "" }
                                } else {
                                    isBusy = false
                                    Toast.makeText(context, accessCapabilities?.restrictionMessage ?: "Storage-owner access is required.", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = if (!config.isPro) true else accessCapabilities?.canManageAccess == true && folderLink.contains("folders/"),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(if (config.isPro) Icons.Default.Folder else Icons.Default.WorkspacePremium, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (config.isPro) "Relocate database folder" else "Upgrade to relocate database folder")
                }
            }
        }
        OperationCard(
            title = "Organization access",
            subtitle = "Approve Google emails, share invitations, and remove organization access.",
            icon = Icons.Default.ManageAccounts
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Every device signs in with an approved Google account before easyPass loads the organization. Only the storage-owning Director can manage access.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                val capabilityMessage = accessCapabilities?.restrictionMessage
                if (!capabilityMessage.isNullOrBlank()) {
                    Surface(
                        color = Color(0xFFFFF7ED),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color(0xFFFDBA74))
                    ) {
                        Text(
                            capabilityMessage,
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF9A3412)
                        )
                    }
                }
                Button(
                    onClick = { showInviteDialog = true },
                    enabled = accessCapabilities?.canManageAccess == true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.PersonAdd, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Invite staff", fontWeight = FontWeight.SemiBold)
                }
                config.accessRoster
                    .filter { it.isActive }
                    .sortedBy { it.email.lowercase() }
                    .forEach { entry ->
                        ProfessionalActionRow(
                            icon = when (entry.role) {
                                SessionManager.ROLE_DIRECTOR -> Icons.Default.VpnKey
                                SessionManager.ROLE_MANAGER -> Icons.Default.AdminPanelSettings
                                else -> Icons.Default.QrCodeScanner
                            },
                            title = entry.email,
                            body = if (entry.email == config.storageOwnerEmail) {
                                "${roleDisplayName(entry.role)} • Storage owner"
                            } else {
                                roleDisplayName(entry.role)
                            },
                            trailing = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    ProfessionalStatusChip(roleDisplayName(entry.role).uppercase(), MaterialTheme.colorScheme.primary)
                                    IconButton(
                                        onClick = {
                                            shareOrganizationInvitation(
                                                context = context,
                                                configFileId = configFileId,
                                                email = entry.email,
                                                role = entry.role
                                            )
                                        },
                                        enabled = accessCapabilities?.canManageAccess == true
                                    ) {
                                        Icon(
                                            Icons.Default.Share,
                                            contentDescription = "Share invitation for ${entry.email}",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    if (entry.email != config.storageOwnerEmail) {
                                        TextButton(
                                            onClick = { accessEntryToRemove = entry },
                                            enabled = accessCapabilities?.canManageAccess == true
                                        ) {
                                            Text("Remove", color = Color(0xFFB42318))
                                        }
                                    }
                                }
                            }
                        )
                    }
            }
        }
        OperationCard(
            title = "Organization access",
            subtitle = "Replace the active organization connection and require all other devices to reconnect.",
            icon = Icons.Default.LockReset
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Use this only when you need to block all currently connected easyPass devices and issue a fresh invitation link. This device stays connected after the replacement finishes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                Button(
                    onClick = {
                        if (syncManager == null) {
                            Toast.makeText(context, "Google sign-in is required for Director storage actions.", Toast.LENGTH_LONG).show()
                        } else {
                            showRevokeDialog = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB42318)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.LockReset, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Revoke all Users Access", fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(modifier = Modifier.height(84.dp))
    }
    if (showUpgradeDialog) {
        PaywallDialog(
            onDismissRequest = { showUpgradeDialog = false },
            onPurchaseClick = {
                val activity = context as Activity
                billingViewModel.launchPurchase(activity) { purchase ->
                    billingViewModel.activateProAfterPurchase(
                        purchase = purchase,
                        currentConfig = config,
                        configFileId = configFileId,
                        onConfigUpdated = onConfigUpdated
                    )
                    showUpgradeDialog = false
                }
            },
            productDetails = productDetails,
            isLoading = billingState is BillingManager.BillingState.LOADING || billingState is BillingManager.BillingState.PURCHASING,
            errorMessage = (billingState as? BillingManager.BillingState.ERROR)?.message
        )
    }
    passwordChangeRole?.let { role ->
        ChangeRolePasswordDialog(
            role = role,
            onDismiss = { passwordChangeRole = null },
            onSave = { newPassword ->
                val oppositeRole = if (role == SessionManager.ROLE_MANAGER) SessionManager.ROLE_DIRECTOR else SessionManager.ROLE_MANAGER
                val roleLabel = roleDisplayName(role)
                val oppositeLabel = roleDisplayName(oppositeRole)
                val oppositeHash = config.hashForRole(oppositeRole)
                if (!oppositeHash.isNullOrBlank() && SecurityUtils.verifyPassword(newPassword, oppositeHash)) {
                    Toast.makeText(context, "$roleLabel password conflicts with existing $oppositeLabel password", Toast.LENGTH_LONG).show()
                    return@ChangeRolePasswordDialog
                }
                isBusy = true
                val newHashes = config.roleHashes.toMutableMap().apply {
                    when (role) {
                        SessionManager.ROLE_MANAGER -> put(SessionManager.ROLE_MANAGER, SecurityUtils.hashPassword(newPassword))
                        SessionManager.ROLE_DIRECTOR -> put(SessionManager.ROLE_DIRECTOR, SecurityUtils.hashPassword(newPassword))
                    }
                }
                updateConfigOnDrive(context, config.copy(roleHashes = newHashes)) {
                    onConfigUpdated(it)
                    passwordChangeRole = null
                    isBusy = false
                    Toast.makeText(context, "$roleLabel password updated", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
    if (showRevokeDialog) {
        RevokeUsersPermissionDialog(
            config = config,
            onDismiss = { showRevokeDialog = false },
            onConfirm = { currentDirectorPassword, newManagerPassword, newDirectorPassword ->
                val managerHash = SecurityUtils.hashPassword(newManagerPassword)
                val directorHash = SecurityUtils.hashPassword(newDirectorPassword)
                isBusy = true
                scope.launch {
                    val result = syncManager?.revokeAllUsersAccess(
                        oldConfigId = configFileId,
                        currentConfig = config,
                        newManagerHash = managerHash,
                        newDirectorHash = directorHash,
                        initiatingDirectorEmail = signedInEmail.orEmpty()
                    ) ?: RevokeOrganizationAccessResult.Failure("Google sign-in is required for Director storage actions.")
                    isBusy = false
                    showRevokeDialog = false
                    onRevokeAllUsersPermission(result)
                }
            }
        )
    }
    if (showInviteDialog) {
        InviteOrganizationUserDialog(
            config = config,
            onDismiss = { showInviteDialog = false },
            onInvite = { email, role ->
                isBusy = true
                scope.launch {
                    val updatedConfig = syncManager?.inviteOrganizationUser(configFileId, config, email, role)
                    isBusy = false
                    if (updatedConfig == null) {
                        Toast.makeText(context, "easyPass could not grant access for this Google account.", Toast.LENGTH_LONG).show()
                    } else {
                        onConfigUpdated(updatedConfig)
                        shareOrganizationInvitation(context, configFileId, email, role)
                        showInviteDialog = false
                    }
                }
            }
        )
    }
    accessEntryToRemove?.let { entry ->
        AlertDialog(
            onDismissRequest = { accessEntryToRemove = null },
            title = { Text("Remove organization access") },
            text = {
                Text("This Google account will lose access to the organization on its next validation: ${entry.email}")
            },
            confirmButton = {
                Button(
                    onClick = {
                        isBusy = true
                        scope.launch {
                            val updatedConfig = syncManager?.removeOrganizationUser(configFileId, config, entry.email)
                            isBusy = false
                            accessEntryToRemove = null
                            if (updatedConfig == null) {
                                Toast.makeText(context, "easyPass could not remove this Google account.", Toast.LENGTH_LONG).show()
                            } else {
                                onConfigUpdated(updatedConfig)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB42318))
                ) {
                    Text("Remove access")
                }
            },
            dismissButton = {
                TextButton(onClick = { accessEntryToRemove = null }) {
                    Text("Cancel")
                }
            }
        )
    }
    if (isBusy) com.example.access.ui.components.LoadingOverlay(isVisible = true, message = "Processing...")
}

@Composable
private fun ChangeRolePasswordDialog(
    role: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    val roleLabel = roleDisplayName(role)
    var password by remember(role) { mutableStateOf("") }
    var confirmPassword by remember(role) { mutableStateOf("") }
    val mismatch = confirmPassword.isNotEmpty() && confirmPassword != password
    val canSave = password.isNotEmpty() && confirmPassword == password

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                if (role == SessionManager.ROLE_MANAGER) Icons.Default.AdminPanelSettings else Icons.Default.VpnKey,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("Change ${roleLabel.lowercase()} password", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "Enter the new $roleLabel password twice. The current password remains active until this change is saved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("New $roleLabel Password") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = { Text("Confirm $roleLabel Password") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    singleLine = true,
                    isError = mismatch,
                    supportingText = {
                        when {
                            confirmPassword.isEmpty() && password.isNotEmpty() -> Text("Confirm this password before saving.")
                            mismatch -> Text("$roleLabel password confirmation does not match.")
                        }
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(password) },
                enabled = canSave,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Save password")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun roleDisplayName(role: String): String {
    return SessionManager.roleDisplayName(role)
}

/** Generates a fresh join credential without changing the recipient's existing Drive access. */
private fun shareOrganizationInvitation(
    context: Context,
    configFileId: String,
    email: String,
    role: String
) {
    try {
        val joinAccessCode = SecurityUtils.generateInviteAccessCode()
        val formattedJoinAccessCode = SecurityUtils.formatInviteAccessCode(joinAccessCode)
        val encryptedKey = SecurityUtils.encryptInvite(configFileId, joinAccessCode, email, role)
        val inviteUrl = "https://easyapps-solutions.com/join?key=$encryptedKey"
        val inviteText = "Join this easyPass organization with Google account: ${email.trim()}\nRole: ${roleDisplayName(role)}\nInvitation access code: $formattedJoinAccessCode\n\nLink: $inviteUrl"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, inviteText)
        }
        context.startActivity(Intent.createChooser(intent, "Share easyPass invitation"))
    } catch (error: Exception) {
        Toast.makeText(context, "easyPass could not create an invitation.", Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun InviteOrganizationUserDialog(
    config: Config,
    onDismiss: () -> Unit,
    onInvite: (email: String, role: String) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var selectedRole by remember { mutableStateOf(SessionManager.ROLE_SCANNER) }
    val normalizedEmail = email.trim()
    val isValidEmail = normalizedEmail.matches(Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$"))
    val existingEntry = config.activeAccessEntryFor(normalizedEmail)
    val isStorageOwnerEmail = existingEntry?.email == config.storageOwnerEmail
    val selectedRoleLabel = roleDisplayName(selectedRole)
    val confirmLabel = when {
        isStorageOwnerEmail -> "Use share from the access list"
        existingEntry != null && SessionManager.canonicalRole(existingEntry.role) == SessionManager.canonicalRole(selectedRole) -> "Reinvite"
        existingEntry != null -> "Replace role and invite"
        else -> "Grant access"
    }
    val canInvite = isValidEmail && !isStorageOwnerEmail

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invite staff") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Enter the exact Google email that should be approved for this organization, then choose its role.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Google email") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                when {
                    isStorageOwnerEmail -> {
                        Surface(
                            color = Color(0xFFEFF6FF),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color(0xFF93C5FD))
                        ) {
                            Text(
                                "This email is already the storage-owning Director. Use the share button beside this email in the access list to send a fresh invitation without changing its access.",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF1D4ED8)
                            )
                        }
                    }
                    existingEntry != null -> {
                        val currentRoleLabel = roleDisplayName(existingEntry.role)
                        val replacementMessage = if (SessionManager.canonicalRole(existingEntry.role) == SessionManager.canonicalRole(selectedRole)) {
                            "This email already has $currentRoleLabel access. You can still send a fresh invitation for the same role."
                        } else {
                            "This email already has $currentRoleLabel access. Continuing will replace that access with $selectedRoleLabel and share a fresh invitation."
                        }
                        Surface(
                            color = Color(0xFFFFF7ED),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color(0xFFFDBA74))
                        ) {
                            Text(
                                replacementMessage,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF9A3412)
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedRole == SessionManager.ROLE_SCANNER,
                        onClick = { selectedRole = SessionManager.ROLE_SCANNER },
                        label = { Text("Scanner") }
                    )
                    FilterChip(
                        selected = selectedRole == SessionManager.ROLE_MANAGER,
                        onClick = { selectedRole = SessionManager.ROLE_MANAGER },
                        label = { Text("Manager") }
                    )
                    FilterChip(
                        selected = selectedRole == SessionManager.ROLE_DIRECTOR,
                        onClick = { selectedRole = SessionManager.ROLE_DIRECTOR },
                        label = { Text("Director") }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onInvite(normalizedEmail, selectedRole) },
                enabled = canInvite
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun RevokeUsersPermissionDialog(
    config: Config,
    onDismiss: () -> Unit,
    onConfirm: (currentDirectorPassword: String, newManagerPassword: String, newDirectorPassword: String) -> Unit
) {
    var currentDirectorPassword by remember { mutableStateOf("") }
    var newManagerPassword by remember { mutableStateOf("") }
    var confirmManagerPassword by remember { mutableStateOf("") }
    var newDirectorPassword by remember { mutableStateOf("") }
    var confirmDirectorPassword by remember { mutableStateOf("") }
    var acknowledged by remember { mutableStateOf(false) }

    val currentPasswordInvalid = currentDirectorPassword.isNotEmpty() &&
        !SecurityUtils.verifyPassword(currentDirectorPassword, config.hashForRole(SessionManager.ROLE_DIRECTOR))
    val managerMismatch = confirmManagerPassword.isNotEmpty() && confirmManagerPassword != newManagerPassword
    val directorMismatch = confirmDirectorPassword.isNotEmpty() && confirmDirectorPassword != newDirectorPassword
    val passwordsConflict = newManagerPassword.isNotEmpty() &&
        newDirectorPassword.isNotEmpty() &&
        newManagerPassword == newDirectorPassword
    val canConfirm = !currentPasswordInvalid &&
        currentDirectorPassword.isNotBlank() &&
        newManagerPassword.isNotBlank() &&
        newDirectorPassword.isNotBlank() &&
        confirmManagerPassword == newManagerPassword &&
        confirmDirectorPassword == newDirectorPassword &&
        !passwordsConflict &&
        acknowledged

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Default.LockReset, contentDescription = null, tint = Color(0xFFB42318))
        },
        title = {
            Text("Revoke all Users Access", fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "This device remains connected. All other updated easyPass devices will lose their current connection on their next foreground validation and must reconnect with a new invitation link.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Previous config and database files are permanently deleted. Internet is required after easyPass returns to the foreground. Google Drive sharing is still managed separately in Google Drive.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = currentDirectorPassword,
                    onValueChange = { currentDirectorPassword = it },
                    label = { Text("Current Director Password") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    singleLine = true,
                    isError = currentPasswordInvalid,
                    supportingText = {
                        if (currentPasswordInvalid) {
                            Text("Current Director password is incorrect.")
                        }
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                OutlinedTextField(
                    value = newManagerPassword,
                    onValueChange = { newManagerPassword = it },
                    label = { Text("New Manager Password") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                OutlinedTextField(
                    value = confirmManagerPassword,
                    onValueChange = { confirmManagerPassword = it },
                    label = { Text("Confirm Manager Password") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    singleLine = true,
                    isError = managerMismatch,
                    supportingText = {
                        if (managerMismatch) {
                            Text("Manager password confirmation does not match.")
                        }
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                OutlinedTextField(
                    value = newDirectorPassword,
                    onValueChange = { newDirectorPassword = it },
                    label = { Text("New Director Password") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                OutlinedTextField(
                    value = confirmDirectorPassword,
                    onValueChange = { confirmDirectorPassword = it },
                    label = { Text("Confirm Director Password") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    singleLine = true,
                    isError = directorMismatch || passwordsConflict,
                    supportingText = {
                        when {
                            directorMismatch -> Text("Director password confirmation does not match.")
                            passwordsConflict -> Text("Manager and Director passwords must be different.")
                        }
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Checkbox(
                        checked = acknowledged,
                        onCheckedChange = { acknowledged = it }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "I understand that all other devices will need a new invitation link after this completes.",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(currentDirectorPassword, newManagerPassword, newDirectorPassword)
                },
                enabled = canConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB42318)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Revoke access")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun OperationCard(
    title: String,
    subtitle: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    content: @Composable () -> Unit
) {
    ProfessionalSectionCard(title = title, subtitle = subtitle, icon = icon) {
        content()
    }
}

@Composable
fun FieldToggleRow(label: String, isChecked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium); Switch(checked = isChecked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun LogoCropDialog(uri: Uri, onDismiss: () -> Unit, onConfirm: (Bitmap) -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val originalBitmap = remember(uri) { try { context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) } } catch (e: Exception) { null } }
    if (originalBitmap == null) { onDismiss(); return }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Column {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp).statusBarsPadding(), horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null, tint = Color.White) }
                    Button(onClick = {
                        val size = 512; val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(result); val cx = containerSize.width / 2f; val cy = containerSize.height / 2f
                        val circleRadiusPx = with(density) { 140.dp.toPx() }; val matrix = Matrix()
                        matrix.postTranslate(-originalBitmap.width / 2f, -originalBitmap.height / 2f)
                        matrix.postScale(scale * (containerSize.width.toFloat() / originalBitmap.width), scale * (containerSize.width.toFloat() / originalBitmap.width))
                        matrix.postTranslate(cx + offset.x, cy + offset.y)
                        val temp = Bitmap.createBitmap(containerSize.width, containerSize.height, Bitmap.Config.ARGB_8888)
                        Canvas(temp).drawBitmap(originalBitmap, matrix, null)
                        val cropped = Bitmap.createBitmap(temp, (cx - circleRadiusPx).toInt().coerceAtLeast(0), (cy - circleRadiusPx).toInt().coerceAtLeast(0), (circleRadiusPx * 2).toInt(), (circleRadiusPx * 2).toInt())
                        val scaled = Bitmap.createScaledBitmap(cropped, size, size, true)
                        canvas.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG))
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG); paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                        canvas.drawBitmap(scaled, 0f, 0f, paint); onConfirm(result)
                    }) { Text("Confirm") }
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { containerSize = it.size }.pointerInput(Unit) { detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(0.2f, 10f); offset += pan } }, contentAlignment = Alignment.Center) {
                    Image(bitmap = originalBitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y))
                    androidx.compose.foundation.Canvas(modifier = Modifier.size(280.dp)) { drawCircle(Color.White, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx())) }
                }
            }
        }
    }
}

private fun extractFolderIdFromLink(link: String): String? {
    return try { val r = Regex("folders/([^/?]+)"); r.find(link)?.groupValues?.get(1) } catch (e: Exception) { null }
}

private fun relocate(context: Context, newParentId: String, onSuccess: (Config) -> Unit) {
    val account = GoogleSignIn.getLastSignedInAccount(context) ?: return
    val cred = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE)).apply { selectedAccount = account.account }
    val sync = DriveSyncManager(context, DriveSyncManager.createWithCredential(context, cred).drive)
    val configId = context.getSharedPreferences("easypass_prefs", Context.MODE_PRIVATE).getString("config_file_id", null) ?: return
    (context as androidx.activity.ComponentActivity).lifecycleScope.launch {
        val nextId = sync.relocateFolder(configId, newParentId)
        if (nextId != null) { context.getSharedPreferences("easypass_prefs", Context.MODE_PRIVATE).edit().putString("config_file_id", nextId).apply(); sync.downloadConfig(nextId)?.let { onSuccess(it) } }
    }
}

private fun updateConfigOnDrive(context: Context, config: Config, onSuccess: (Config) -> Unit) {
    val account = GoogleSignIn.getLastSignedInAccount(context) ?: return
    val cred = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE)).apply { selectedAccount = account.account }
    val sync = DriveSyncManager(context, DriveSyncManager.createWithCredential(context, cred).drive)
    val configId = context.getSharedPreferences("easypass_prefs", Context.MODE_PRIVATE).getString("config_file_id", null) ?: return
    (context as androidx.activity.ComponentActivity).lifecycleScope.launch {
        if (sync.updateConfigOnDrive(configId, config)) {
            onSuccess(config)
        } else {
            Toast.makeText(context, "easyPass could not save these organization changes.", Toast.LENGTH_LONG).show()
        }
    }
}

private suspend fun uploadCroppedLogo(context: Context, bitmap: Bitmap, onSuccess: (Config) -> Unit, config: Config) {
    val account = GoogleSignIn.getLastSignedInAccount(context) ?: return
    val cred = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE)).apply { selectedAccount = account.account }
    val sync = DriveSyncManager(context, DriveSyncManager.createWithCredential(context, cred).drive)
    val configId = context.getSharedPreferences("easypass_prefs", Context.MODE_PRIVATE).getString("config_file_id", null) ?: return
    val file = File(context.cacheDir, "logo_cropped.png")
    withContext(Dispatchers.IO) { FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    val parentId = config.storageFolderId ?: sync.getParentId(configId) ?: "root"
    sync.uploadLogo(parentId, file, config.branding.logoFileId)?.let {
        val updated = sync.applyRosterToLogo(configId, config, it) ?: return
        withContext(Dispatchers.Main) { onSuccess(updated) }
    }
}
