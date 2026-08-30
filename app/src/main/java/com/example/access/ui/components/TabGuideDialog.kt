package com.example.access.ui.components

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.ToggleOff
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private const val GUIDE_PREFS = "easypass_prefs"
private const val ORIENTATION_KEY = "app_orientation_seen_v1"

private val TabGuideIds = listOf(
    "dashboard",
    "scanner",
    "settings",
    "members",
    "director"
)

data class ControlGuide(
    val icon: ImageVector,
    val name: String,
    val description: String,
    val readOnly: Boolean = false
)

data class TabGuideModel(
    val title: String,
    val purpose: String,
    val detail: String,
    val controls: List<ControlGuide>
)

private data class OrientationPage(
    val icon: ImageVector,
    val title: String,
    val body: String,
    val bullets: List<String>
)

@Composable
fun AppOrientationDialog(
    onFinished: () -> Unit
) {
    val pages = remember { orientationPages() }
    var page by remember { mutableIntStateOf(0) }
    val currentPage = pages[page]
    val isLastPage = page == pages.lastIndex

    AlertDialog(
        modifier = Modifier.fillMaxWidth(),
        onDismissRequest = onFinished,
        icon = { GuideIcon(currentPage.icon) },
        title = {
            Text(
                currentPage.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                PageIndicator(current = page, total = pages.size)
                Text(
                    currentPage.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                BulletList(currentPage.bullets)
            }
        },
        confirmButton = {
            Button(onClick = {
                if (isLastPage) {
                    onFinished()
                } else {
                    page += 1
                }
            }) {
                Text(if (isLastPage) "Start using EasyPass" else "Next")
            }
        },
        dismissButton = {
            Row {
                if (page > 0) {
                    TextButton(onClick = { page -= 1 }) {
                        Text("Back")
                    }
                }
                TextButton(onClick = onFinished) {
                    Text("Skip introduction")
                }
            }
        }
    )
}

@Composable
fun TabGuideDialog(
    tabId: String,
    onDismiss: () -> Unit
) {
    val guide = remember(tabId) { tabGuideContent(tabId) }
    var page by remember(tabId) { mutableIntStateOf(0) }

    AlertDialog(
        modifier = Modifier.fillMaxWidth(),
        onDismissRequest = onDismiss,
        icon = { GuideIcon(if (page == 0) Icons.Default.Info else Icons.Default.Navigation) },
        title = {
            Text(
                if (page == 0) guide.title else "${guide.title} controls",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                PageIndicator(current = page, total = 2)
                if (page == 0) {
                    Text(
                        guide.purpose,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        guide.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    guide.controls.forEachIndexed { index, control ->
                        ControlGuideRow(control = control)
                        if (index != guide.controls.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (page == 0) {
                    page = 1
                } else {
                    onDismiss()
                }
            }) {
                Text(if (page == 0) "Show controls" else "Got it")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (page == 0) "Skip guide" else "Close")
            }
        }
    )
}

@Composable
private fun GuideIcon(icon: ImageVector) {
    Surface(
        modifier = Modifier.size(42.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.24f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun PageIndicator(current: Int, total: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(total) { index ->
            Surface(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .width(if (index == current) 22.dp else 8.dp)
                    .height(8.dp),
                shape = CircleShape,
                color = if (index == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
            ) {}
        }
    }
}

@Composable
private fun BulletList(bullets: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        bullets.forEach { bullet ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(
                    "•",
                    modifier = Modifier.width(18.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    bullet,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ControlGuideRow(control: ControlGuide) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            modifier = Modifier.size(38.dp),
            shape = RoundedCornerShape(12.dp),
            color = if (control.readOnly) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
            }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    control.icon,
                    contentDescription = null,
                    tint = if (control.readOnly) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    control.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (control.readOnly) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ) {
                        Text(
                            "Read-only",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                control.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

fun hasSeenAppOrientation(context: Context): Boolean {
    return context
        .getSharedPreferences(GUIDE_PREFS, Context.MODE_PRIVATE)
        .getBoolean(ORIENTATION_KEY, false)
}

fun markAppOrientationSeen(context: Context) {
    context
        .getSharedPreferences(GUIDE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(ORIENTATION_KEY, true)
        .apply()
}

fun hasSeenTabGuide(context: Context, tabId: String): Boolean {
    return context
        .getSharedPreferences(GUIDE_PREFS, Context.MODE_PRIVATE)
        .getBoolean(tabGuideKey(tabId), false)
}

fun markTabGuideSeen(context: Context, tabId: String) {
    context
        .getSharedPreferences(GUIDE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(tabGuideKey(tabId), true)
        .apply()
}

fun resetAppGuides(context: Context) {
    val editor = context.getSharedPreferences(GUIDE_PREFS, Context.MODE_PRIVATE).edit()
    editor.putBoolean(ORIENTATION_KEY, false)
    TabGuideIds.forEach { editor.putBoolean(tabGuideKey(it), false) }
    editor.apply()
}

private fun tabGuideKey(tabId: String): String {
    return "tab_guide_seen_v2_$tabId"
}

private fun orientationPages(): List<OrientationPage> {
    return listOf(
        OrientationPage(
            icon = Icons.Default.Badge,
            title = "Membership and access in one app",
            body = "EasyPass combines an organization member database with QR access verification.",
            bullets = listOf(
                "Maintain member information and membership status.",
                "Generate and share member QR passes.",
                "Verify passes quickly from connected devices.",
                "Designed as an affordable and lightweight solution for small and medium organizations."
            )
        ),
        OrientationPage(
            icon = Icons.Default.Cloud,
            title = "Your data stays in your Drive",
            body = "Your organization database is stored in your own Google Drive.",
            bullets = listOf(
                "You remain in control of the database.",
                "Connected EasyPass devices use the shared Drive database.",
                "Google sign-in is needed only when creating, restoring, or managing Drive-backed data.",
                "EasyPass uses minimal local device storage."
            )
        ),
        OrientationPage(
            icon = Icons.Default.VerifiedUser,
            title = "Three roles, clear responsibilities",
            body = "EasyPass starts in Scanner. Protected roles require their assigned passwords.",
            bullets = listOf(
                "Scanner verifies QR passes without editing the organization database.",
                "Manager adds, edits, imports, exports, suspends, and reactivates members.",
                "Director controls the subscription, branding, fields, passwords, and database storage."
            )
        ),
        OrientationPage(
            icon = Icons.Default.Timeline,
            title = "Your first EasyPass workflow",
            body = "Configure organization -> Add or import members -> Share QR passes -> Scan and verify",
            bullets = listOf(
                "Switch to Director to configure the organization.",
                "Switch to Manager to add or import members.",
                "Share generated QR passes with members.",
                "Return to Scanner for daily access verification."
            )
        )
    )
}

private fun tabGuideContent(tabId: String): TabGuideModel {
    return when (tabId) {
        "dashboard" -> TabGuideModel(
            title = "Dashboard",
            purpose = "Review your organization, member total, Google Drive connection, and recent scanner activity.",
            detail = "This tab gives a quick operational view. Database status is synchronized with your shared Google Drive file, while recent scanner activity is local to this device.",
            controls = listOf(
                ControlGuide(Icons.Default.People, "Member count", "Number of usable members available on this device.", readOnly = true),
                ControlGuide(Icons.Default.Cloud, "Database status", "Shows whether the shared database is synced, syncing, or temporarily offline.", readOnly = true),
                ControlGuide(Icons.Default.Refresh, "System connection", "Tap to synchronize manually with the shared Google Drive database."),
                ControlGuide(Icons.Default.Timeline, "Recent activity", "Latest scanner results recorded on this device.", readOnly = true),
                ControlGuide(Icons.Default.Key, "Switch Role", "Enter Manager or Director using the appropriate password."),
                ControlGuide(Icons.Default.Navigation, "Bottom navigation", "Tap a tab or swipe horizontally between available tabs.")
            )
        )
        "scanner" -> TabGuideModel(
            title = "Scanner",
            purpose = "Verify member QR passes without editing the organization database.",
            detail = "The scanner is read-only. It checks QR codes against the local synced database and does not write scan history to Google Drive.",
            controls = listOf(
                ControlGuide(Icons.Default.CameraAlt, "Camera frame", "Automatically recognizes an EasyPass QR code."),
                ControlGuide(Icons.Default.CameraAlt, "Allow Camera", "Grants the required Android camera permission."),
                ControlGuide(Icons.Default.Visibility, "Result card", "Shows member name, scan time, and access result.", readOnly = true),
                ControlGuide(Icons.Default.QrCodeScanner, "Scan Next", "Closes the current result and accepts another scan."),
                ControlGuide(Icons.Default.Logout, "Return to Scanner", "Ends a protected Manager or Director session.")
            )
        )
        "settings" -> TabGuideModel(
            title = "Settings",
            purpose = "Manage how this device connects to the organization and replay app guidance.",
            detail = "Settings actions affect this device connection. Leaving or switching organizations does not delete the shared Google Drive database.",
            controls = listOf(
                ControlGuide(Icons.Default.Share, "Invite staff", "Creates a protected invitation for another device. Available to Manager and Director."),
                ControlGuide(Icons.Default.AddLink, "Switch organization", "Connects the device using another organization's invitation link."),
                ControlGuide(Icons.Default.Logout, "Leave organization", "Disconnects locally without deleting Drive files."),
                ControlGuide(Icons.Default.ManageAccounts, "Also sign out of Google", "Removes the selected Google account when leaving."),
                ControlGuide(Icons.Default.RestartAlt, "Show app guides again", "Resets the introduction and every tab handbook."),
                ControlGuide(Icons.Default.Info, "Version information", "Displays the installed app version and build.", readOnly = true)
            )
        )
        "members" -> TabGuideModel(
            title = "Members",
            purpose = "Manage and exchange organization member information through the shared Drive database.",
            detail = "Confirmed member changes synchronize with Google Drive. Use search, filters, and data tools to keep the member database organized.",
            controls = listOf(
                ControlGuide(Icons.Default.PersonAdd, "Add member", "Creates a member, ID, and QR pass."),
                ControlGuide(Icons.Default.Storage, "Data tools", "Opens spreadsheet import, template, and backup actions."),
                ControlGuide(Icons.Default.Search, "Search", "Searches customer-facing member fields. Type @all to show every usable member."),
                ControlGuide(Icons.Default.FilterList, "Filters", "Shows Active, Suspended, Missing phone, or Missing email members."),
                ControlGuide(Icons.Default.QrCode, "Share QR pass", "Generates and shares a member pass."),
                ControlGuide(Icons.Default.ToggleOff, "Suspend / Reactivate", "Changes whether the pass grants access."),
                ControlGuide(Icons.Default.Edit, "Edit", "Updates member information."),
                ControlGuide(Icons.Default.Delete, "Delete", "Permanently removes a member after confirmation."),
                ControlGuide(Icons.Default.Download, "Export EasyPass backup", "Produces a complete app backup spreadsheet."),
                ControlGuide(Icons.Default.FileDownload, "Download import template", "Produces a blank supported import file."),
                ControlGuide(Icons.Default.UploadFile, "Bulk import spreadsheet", "Previews and applies Add or Overwrite imports.")
            )
        )
        "director" -> TabGuideModel(
            title = "Director",
            purpose = "Control organization-wide settings, branding, subscription, security, and storage.",
            detail = "Director changes are protected because they affect the whole organization and are saved through the shared Drive-backed configuration.",
            controls = listOf(
                ControlGuide(Icons.Default.WorkspacePremium, "Upgrade to Pro", "Opens the organization-wide subscription offer."),
                ControlGuide(Icons.Default.Brush, "Update logo", "Selects and crops organization branding."),
                ControlGuide(Icons.Default.ColorLens, "Theme selection", "Chooses the organization accent color."),
                ControlGuide(Icons.Default.CheckCircle, "Save brand profile", "Saves organization name and appearance."),
                ControlGuide(Icons.Default.ToggleOff, "Member field toggles", "Controls optional member fields."),
                ControlGuide(Icons.Default.CheckCircle, "Update member fields", "Saves the selected field configuration."),
                ControlGuide(Icons.Default.Lock, "Change Manager password", "Replaces Manager access credentials after confirmation."),
                ControlGuide(Icons.Default.Lock, "Change Director password", "Replaces Director credentials after confirmation."),
                ControlGuide(Icons.Default.Folder, "Current database folder", "Shows Google Drive folder information.", readOnly = true),
                ControlGuide(Icons.Default.CloudSync, "Relocate database folder", "Verifies and moves the database to another permitted Drive folder.")
            )
        )
        else -> TabGuideModel(
            title = "EasyPass",
            purpose = "This tab contains EasyPass tools.",
            detail = "Use the visible actions on this screen to continue.",
            controls = emptyList()
        )
    }
}
