package com.example.access.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.easyapps.easypass.BuildConfig
import com.example.access.data.Config
import com.example.access.ui.components.ProfessionalActionRow
import com.example.access.ui.components.ProfessionalPageHeader
import com.example.access.ui.components.ProfessionalScreen
import com.example.access.ui.components.ProfessionalSectionCard

@Composable
fun AdminSettingsScreen(
    config: Config,
    onLeaveOrganization: (Boolean) -> Unit,
    onReplayGuides: () -> Unit
) {
    val context = LocalContext.current
    
    var showJoinDialog by remember { mutableStateOf(false) }
    var showLeaveDialog by remember { mutableStateOf(false) }
    var signOutGoogleOnLeave by remember { mutableStateOf(false) }

    ProfessionalScreen(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp)) {
        ProfessionalPageHeader(
            title = "Settings",
            subtitle = "Manage this device, organization access, and app guidance."
        )

        ProfessionalSectionCard(
            title = "Organization",
            subtitle = "Connect this device to the right easyPass database.",
            icon = Icons.Default.Business
        ) {
            ProfessionalActionRow(
                icon = Icons.Default.AddLink,
                title = "Switch organization",
                body = "Use an invitation link to connect this device to another organization.",
                onClick = { showJoinDialog = true }
            )
            ProfessionalActionRow(
                icon = Icons.Default.Logout,
                title = "Leave organization",
                body = "Disconnect this device and return to setup. Drive files stay untouched.",
                destructive = true,
                onClick = { showLeaveDialog = true }
            )
        }

        ProfessionalSectionCard(
            title = "App guidance",
            subtitle = "Replay the easyPass introduction and the guide for each tab.",
            icon = Icons.Default.RestartAlt
        ) {
            ProfessionalActionRow(
                icon = Icons.Default.RestartAlt,
                title = "Show app guides again",
                body = "Reset the introduction and every tab handbook on this device.",
                onClick = onReplayGuides
            )
        }

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                "easyPass v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF98A2B3)
            )
        }

        Spacer(modifier = Modifier.height(84.dp))
    }

    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            icon = { Icon(Icons.Default.Logout, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Leave Organization?", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("This device will disconnect from the current easyPass organization. The shared database in Google Drive will not be deleted.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = signOutGoogleOnLeave,
                            onCheckedChange = { signOutGoogleOnLeave = it }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Also sign out of Google on this device", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { onLeaveOrganization(signOutGoogleOnLeave) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Leave Organization")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveDialog = false }) { Text("Cancel") }
            }
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

}
