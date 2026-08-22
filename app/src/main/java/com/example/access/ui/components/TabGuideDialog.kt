package com.example.access.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.access.util.SessionManager
import java.util.Locale

private const val GUIDE_PREFS = "easypass_prefs"

data class TabGuideModel(
    val title: String,
    val subtitle: String,
    val bullets: List<String>
)

@Composable
fun TabGuideDialog(
    tabTitle: String,
    activeRole: String,
    onDismiss: () -> Unit
) {
    val guide = tabGuideContent(tabTitle, activeRole)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Surface(shape = MaterialTheme.shapes.small) {
                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.padding(4.dp))
            }
        },
        title = { Text(guide.title, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    guide.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    guide.bullets.forEach { bullet ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text("• ", style = MaterialTheme.typography.bodySmall)
                            Text(
                                bullet,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Got it")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Skip")
            }
        }
    )
}

fun hasSeenTabGuide(context: Context, tabTitle: String): Boolean {
    return context
        .getSharedPreferences(GUIDE_PREFS, Context.MODE_PRIVATE)
        .getBoolean(tabGuideKey(tabTitle), false)
}

fun markTabGuideSeen(context: Context, tabTitle: String) {
    context
        .getSharedPreferences(GUIDE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(tabGuideKey(tabTitle), true)
        .apply()
}

private fun tabGuideKey(tabTitle: String): String {
    return "guide_seen_tab_${tabTitle.lowercase(Locale.US)}"
}

private fun tabGuideContent(tabTitle: String, activeRole: String): TabGuideModel {
    return when (tabTitle) {
        "Dashboard" -> TabGuideModel(
            title = "Dashboard",
            subtitle = "A quick overview of the organization at a glance.",
            bullets = listOf(
                "Check member count and sync status.",
                "Review the latest scan activity.",
                "Use manual sync if you need to refresh the database."
            )
        )
        "Scanner" -> TabGuideModel(
            title = "Scanner",
            subtitle = "Use this screen to verify passes quickly and read the result clearly.",
            bullets = listOf(
                "Scan member QR codes with the camera.",
                "Keep the result visible until you confirm it.",
                "Switch roles only if you need Manager or Director access."
            )
        )
        "Settings" -> TabGuideModel(
            title = "Settings",
            subtitle = "Manage organization access from this device.",
            bullets = if (activeRole == SessionManager.ROLE_SCANNER) {
                listOf(
                    "Switch organization with an invitation link.",
                    "Leave the current organization without deleting Drive files.",
                    "Sign out of Google if you want a clean setup next time."
                )
            } else {
                listOf(
                    "Invite staff when your role allows it.",
                    "Switch organization with an invitation link.",
                    "Leave the current organization without deleting Drive files."
                )
            }
        )
        "Members" -> TabGuideModel(
            title = "Members",
            subtitle = "Manage the shared member database from one place.",
            bullets = listOf(
                "Search by name, phone, email, notes, or ID.",
                "Add, suspend, or reactivate members.",
                "Use the data tools button for import and export."
            )
        )
        "Director" -> TabGuideModel(
            title = "Director",
            subtitle = "Higher-level organization controls are grouped here.",
            bullets = listOf(
                "Manage Pro, branding, and optional member fields.",
                "Change role passwords and storage settings.",
                "Director changes sync through the organization Drive database."
            )
        )
        else -> TabGuideModel(
            title = tabTitle,
            subtitle = "Overview for this screen.",
            bullets = emptyList()
        )
    }
}
