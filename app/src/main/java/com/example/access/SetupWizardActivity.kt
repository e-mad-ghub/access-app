package com.example.access

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.access.ui.components.LoadingOverlay
import com.example.access.util.DriveSyncManager
import com.example.access.util.SecurityUtils
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.launch

class SetupWizardActivity : ComponentActivity() {

    enum class SetupStep { SIGN_IN, PASSWORDS, PASTE_LINK, CONFIRMATION, RESTORE_FOUND }

    private var googleAccount by mutableStateOf<GoogleSignInAccount?>(null)
    private var currentStep by mutableStateOf(SetupStep.SIGN_IN)
    private var isSearching by mutableStateOf(false)
    private var isBusy by mutableStateOf(false)
    private var existingConfigFoundId by mutableStateOf<String?>(null)
    private var errorMessage by mutableStateOf<String?>(null)
    
    private var selectedFolderName by mutableStateOf("")
    private var selectedFolderId by mutableStateOf("")

    private var adminPass by mutableStateOf("")
    private var ownerPass by mutableStateOf("")

    private val signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                googleAccount = task.getResult(ApiException::class.java)
                checkDriveForExistingSetup()
            } catch (e: ApiException) {
                errorMessage = "Sign-in failed: ${e.statusCode}"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        googleAccount = GoogleSignIn.getLastSignedInAccount(this)
        if (googleAccount != null) checkDriveForExistingSetup()

        setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                SetupWizardScreen()
                LoadingOverlay(isVisible = isBusy, message = "Finalizing Setup...")
            }
        }
    }

    @Composable
    fun SetupWizardScreen() {
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("VaultAccess", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
            Text("Business Onboarding", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            
            Spacer(modifier = Modifier.height(32.dp))

            if (errorMessage != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = errorMessage!!, color = MaterialTheme.colorScheme.error)
                        if (errorMessage!!.contains("Access Denied")) {
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { signOutAndReset() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Sign Out & Re-authorize")
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            AnimatedContent(targetState = currentStep, label = "StepTransition") { step ->
                when (step) {
                    SetupStep.SIGN_IN -> SignInStep()
                    SetupStep.PASSWORDS -> PasswordStep()
                    SetupStep.PASTE_LINK -> PasteLinkStep()
                    SetupStep.CONFIRMATION -> ConfirmationStep()
                    SetupStep.RESTORE_FOUND -> RestoreFoundStep()
                }
            }
        }
    }

    private fun signOutAndReset() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
        GoogleSignIn.getClient(this, gso).signOut().addOnCompleteListener {
            googleAccount = null
            currentStep = SetupStep.SIGN_IN
            errorMessage = null
        }
    }

    @Composable
    fun SignInStep() {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Welcome to the next generation of offline-first access control.", textAlign = TextAlign.Center, color = Color.DarkGray)
            Spacer(modifier = Modifier.height(32.dp))
            if (isSearching) {
                CircularProgressIndicator()
                Text("Scanning your Drive...", modifier = Modifier.padding(top = 16.dp), style = MaterialTheme.typography.labelSmall)
            } else {
                Button(onClick = {
                    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                        .requestEmail()
                        .requestScopes(Scope(DriveScopes.DRIVE)) // FULL SCOPE
                        .build()
                    signInLauncher.launch(GoogleSignIn.getClient(this@SetupWizardActivity, gso).signInIntent)
                }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Login, null)
                    Spacer(Modifier.width(12.dp))
                    Text("Get Started with Google")
                }
            }
        }
    }

    @Composable
    fun RestoreFoundStep() {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.CloudDone, null, modifier = Modifier.size(64.dp), tint = Color(0xFF4CAF50))
            Spacer(modifier = Modifier.height(16.dp))
            Text("Welcome Back!", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("We found an existing organization setup on your Drive.", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(onClick = { 
                isBusy = true
                finalizeRestore(existingConfigFoundId!!) 
            }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Restore System Access")
            }
            TextButton(onClick = { 
                currentStep = SetupStep.PASSWORDS 
                existingConfigFoundId = null
            }) { Text("Create New Organization Instead") }
        }
    }

    @Composable
    fun PasswordStep() {
        Column {
            Text("Secure Management", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Set your private keys to unlock administrative features.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            
            Spacer(modifier = Modifier.height(24.dp))
            
            OutlinedTextField(
                value = adminPass, 
                onValueChange = { adminPass = it }, 
                label = { Text("Admin Passcode") }, 
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = ownerPass, 
                onValueChange = { ownerPass = it }, 
                label = { Text("Owner Passcode") }, 
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = { currentStep = SetupStep.PASTE_LINK },
                enabled = adminPass.isNotEmpty() && ownerPass.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("Next: Storage Location")
            }
        }
    }

    @Composable
    fun PasteLinkStep() {
        var link by remember { mutableStateOf("") }
        var isVerifying by remember { mutableStateOf(false) }

        Column {
            IconButton(onClick = { currentStep = SetupStep.PASSWORDS }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
            Text("Data Storage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Paste the URL of your target Drive folder below.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            
            Spacer(Modifier.height(24.dp))
            
            OutlinedTextField(
                value = link, 
                onValueChange = { link = it }, 
                label = { Text("Google Drive Folder URL") }, 
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                placeholder = { Text("https://drive.google.com/drive/folders/...") }
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "NOTE: This folder MUST be shared as 'Editor' for 'Anyone with the link' BEFORE pasting the link here.",
                style = MaterialTheme.typography.labelSmall,
                color = Color.DarkGray,
                lineHeight = 14.sp
            )

            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = {
                    val id = extractFolderIdFromLink(link)
                    if (id != null) {
                        isVerifying = true
                        errorMessage = null
                        lifecycleScope.launch {
                            val syncManager = DriveSyncManager(this@SetupWizardActivity, getCredential())
                            val isEditable = syncManager.verifyFolderPermissions(id)
                            if (isEditable) {
                                selectedFolderId = id
                                selectedFolderName = syncManager.getFolderName(id)
                                currentStep = SetupStep.CONFIRMATION
                            } else {
                                errorMessage = "Access Denied: Please tap 'Sign Out' above, then sign in again and check the 'Google Drive' box to grant full access."
                            }
                            isVerifying = false
                        }
                    } else {
                        errorMessage = "Invalid folder link format."
                    }
                }, 
                enabled = link.contains("folders/") && !isVerifying,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                if (isVerifying) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                else Text("Verify & Continue")
            }
        }
    }

    @Composable
    fun ConfirmationStep() {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.VerifiedUser, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("Final Confirmation", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Your directory will be initialized in:", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Text(selectedFolderName, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(16.dp)) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "Ready to go! All files will be created in the linked folder.",
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
            Button(onClick = { 
                isBusy = true
                startNewSetup(selectedFolderId, adminPass, ownerPass) 
            }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Create My Directory")
            }
            TextButton(onClick = { currentStep = SetupStep.PASTE_LINK }) { Text("Change Location") }
        }
    }

    private fun extractFolderIdFromLink(link: String): String? {
        val regex = Regex("folders/([^/?]+)")
        return regex.find(link)?.groupValues?.get(1)
    }

    private fun getCredential(): GoogleAccountCredential {
        val account = googleAccount!!
        val credential = GoogleAccountCredential.usingOAuth2(this, listOf(DriveScopes.DRIVE)) // FULL SCOPE
        credential.selectedAccount = account.account
        return credential
    }

    private fun checkDriveForExistingSetup() {
        isSearching = true
        lifecycleScope.launch {
            try {
                val syncManager = DriveSyncManager(this@SetupWizardActivity, getCredential())
                existingConfigFoundId = syncManager.findExistingConfigId()
                if (existingConfigFoundId != null) {
                    currentStep = SetupStep.RESTORE_FOUND
                } else {
                    currentStep = SetupStep.PASSWORDS
                }
            } catch (e: Exception) {
                errorMessage = e.message
                currentStep = SetupStep.SIGN_IN
            } finally {
                isSearching = false
            }
        }
    }

    private fun finalizeRestore(configId: String) {
        getSharedPreferences("vault_access_prefs", MODE_PRIVATE).edit()
            .putString("config_file_id", configId).apply()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun startNewSetup(folderId: String, adminPass: String, ownerPass: String) {
        lifecycleScope.launch {
            try {
                val syncManager = DriveSyncManager(this@SetupWizardActivity, getCredential())
                val configId = syncManager.createInitialFiles(
                    folderId,
                    SecurityUtils.hashPassword(adminPass),
                    SecurityUtils.hashPassword(ownerPass)
                )
                if (configId != null) finalizeRestore(configId)
            } catch (e: Exception) {
                errorMessage = "Setup failed: ${e.message}"
                isBusy = false
            }
        }
    }
}
