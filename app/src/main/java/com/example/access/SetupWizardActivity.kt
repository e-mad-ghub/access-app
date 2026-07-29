package com.example.access

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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

    enum class SetupStep { SIGN_IN, PASSWORDS, PASTE_LINK, CONFIRMATION, RESTORE_FOUND, ENTER_JOIN_PIN }

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

    private var encryptedInviteKey by mutableStateOf<String?>(null)

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
        
        // Detect Deep Link
        val data = intent.data
        if (data != null && data.host == "join") {
            encryptedInviteKey = data.getQueryParameter("key")
        }

        if (googleAccount != null) checkDriveForExistingSetup()

        setContent {
            Box(modifier = Modifier.fillMaxSize().background(Color(0xFFF8F9FB))) {
                SetupWizardScreen()
                LoadingOverlay(isVisible = isBusy, message = "Securing Your Access...")
            }
        }
    }

    @Composable
    fun SetupWizardScreen() {
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Logo
            Surface(
                modifier = Modifier.size(64.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_pass_logo),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            Text("EASYPASS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Text("Membership Simplified", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            
            Spacer(modifier = Modifier.height(32.dp))

            if (errorMessage != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = errorMessage!!, color = Color(0xFFD32F2F), style = MaterialTheme.typography.bodySmall)
                        if (errorMessage!!.contains("Access Denied")) {
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { signOutAndReset() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Sign Out & Reset", fontSize = 12.sp)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            AnimatedContent(targetState = currentStep, label = "StepTransition") { step ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    when (step) {
                        SetupStep.SIGN_IN -> SignInStep()
                        SetupStep.PASSWORDS -> PasswordStep()
                        SetupStep.PASTE_LINK -> PasteLinkStep()
                        SetupStep.CONFIRMATION -> ConfirmationStep()
                        SetupStep.RESTORE_FOUND -> RestoreFoundStep()
                        SetupStep.ENTER_JOIN_PIN -> EnterJoinPinStep()
                    }
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
            Text(
                "Establish your enterprise access hub in minutes.", 
                textAlign = TextAlign.Center, 
                style = MaterialTheme.typography.bodyLarge,
                color = Color.DarkGray
            )
            Spacer(modifier = Modifier.height(48.dp))
            if (isSearching) {
                CircularProgressIndicator(strokeWidth = 3.dp)
                Spacer(Modifier.height(16.dp))
                Text("Authenticating with Cloud...", style = MaterialTheme.typography.labelSmall)
            } else {
                Button(
                    onClick = {
                        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                            .requestEmail()
                            .requestScopes(Scope(DriveScopes.DRIVE))
                            .build()
                        signInLauncher.launch(GoogleSignIn.getClient(this@SetupWizardActivity, gso).signInIntent)
                    }, 
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Login, null)
                    Spacer(Modifier.width(12.dp))
                    Text("Secure Login with Google", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    @Composable
    fun RestoreFoundStep() {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.CloudDone, null, modifier = Modifier.size(64.dp), tint = Color(0xFF4CAF50))
            Spacer(modifier = Modifier.height(24.dp))
            Text("Vault Detected", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("We found an existing organization on your Drive.", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            
            Spacer(modifier = Modifier.height(48.dp))
            
            Button(
                onClick = { isBusy = true; finalizeRestore(existingConfigFoundId!!) }, 
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Restore System Data", fontWeight = FontWeight.Bold)
            }
            TextButton(
                onClick = { currentStep = SetupStep.PASSWORDS; existingConfigFoundId = null },
                modifier = Modifier.padding(top = 8.dp)
            ) { Text("Create New Organization Instead", fontSize = 12.sp) }
        }
    }

    @Composable
    fun EnterJoinPinStep() {
        var pin by remember { mutableStateOf("") }
        var isVerifying by remember { mutableStateOf(false) }

        Column {
            Text("Join Organization", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Enter the 4-digit PIN provided by your Admin to unlock the secure connection.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            
            Spacer(modifier = Modifier.height(32.dp))
            
            OutlinedTextField(
                value = pin,
                onValueChange = { if (it.length <= 4) pin = it },
                label = { Text("Invitation PIN") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                singleLine = true
            )
            
            Spacer(modifier = Modifier.height(48.dp))
            
            Button(
                onClick = {
                    isVerifying = true
                    errorMessage = null
                    val decryptedId = SecurityUtils.decryptInvite(encryptedInviteKey!!, pin)
                    if (decryptedId != null) {
                        lifecycleScope.launch {
                            val syncManager = DriveSyncManager(this@SetupWizardActivity, getCredential())
                            val isEditable = syncManager.verifyFolderPermissions(decryptedId)
                            if (isEditable) {
                                selectedFolderId = decryptedId
                                selectedFolderName = syncManager.getFolderName(decryptedId)
                                currentStep = SetupStep.CONFIRMATION
                            } else {
                                errorMessage = "Access Denied: You must be added as an 'Editor' to the Drive folder."
                            }
                            isVerifying = false
                        }
                    } else {
                        errorMessage = "Incorrect PIN. Please try again."
                        isVerifying = false
                    }
                },
                enabled = pin.length == 4 && !isVerifying,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (isVerifying) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                else Text("Unlock & Join", fontWeight = FontWeight.Bold)
            }
        }
    }

    @Composable
    fun PasswordStep() {
        Column {
            Text("Security Profile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Set private keys to authorize management actions.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            
            Spacer(modifier = Modifier.height(32.dp))
            
            OutlinedTextField(
                value = adminPass, 
                onValueChange = { adminPass = it }, 
                label = { Text("Admin Master Key") }, 
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = ownerPass, 
                onValueChange = { ownerPass = it }, 
                label = { Text("Owner Master Key") }, 
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                singleLine = true
            )
            
            Spacer(modifier = Modifier.height(48.dp))
            
            Button(
                onClick = { currentStep = SetupStep.PASTE_LINK },
                enabled = adminPass.isNotEmpty() && ownerPass.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Continue to Storage", fontWeight = FontWeight.Bold)
            }
        }
    }

    @Composable
    fun PasteLinkStep() {
        var link by remember { mutableStateOf("") }
        var isVerifying by remember { mutableStateOf(false) }

        Column {
            IconButton(onClick = { currentStep = SetupStep.PASSWORDS }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
            Text("Storage Configuration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Paste your shared Google Drive folder URL.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            
            Spacer(Modifier.height(32.dp))
            
            OutlinedTextField(
                value = link, 
                onValueChange = { link = it }, 
                label = { Text("Folder Link") }, 
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                placeholder = { Text("https://drive.google.com/...") }
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Folder MUST be shared as 'Editor' for 'Anyone with the link'.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))
            
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
                                errorMessage = "Access Denied: Re-authorize or check folder permissions."
                            }
                            isVerifying = false
                        }
                    } else {
                        errorMessage = "Invalid folder link format."
                    }
                }, 
                enabled = link.contains("folders/") && !isVerifying,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (isVerifying) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 3.dp)
                else Text("Validate & Continue", fontWeight = FontWeight.Bold)
            }
        }
    }

    @Composable
    fun ConfirmationStep() {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                modifier = Modifier.size(80.dp),
                shape = CircleShape,
                color = Color(0xFFE8F5E9)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Verified, null, modifier = Modifier.size(40.dp), tint = Color(0xFF4CAF50))
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text("Ready for Launch", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text("Destination Verified:", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Text(selectedFolderName.uppercase(), fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.sp)
            
            Spacer(modifier = Modifier.height(48.dp))
            
            Button(
                onClick = { isBusy = true; startNewSetup(selectedFolderId, adminPass, ownerPass) }, 
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Initialize Organization", fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = { 
                if (encryptedInviteKey != null) currentStep = SetupStep.ENTER_JOIN_PIN
                else currentStep = SetupStep.PASTE_LINK 
            }) { Text("Choose Different Location", fontSize = 12.sp) }
        }
    }

    private fun extractFolderIdFromLink(link: String): String? {
        val regex = Regex("folders/([^/?]+)")
        return regex.find(link)?.groupValues?.get(1)
    }

    private fun getCredential(): GoogleAccountCredential {
        val account = googleAccount!!
        val credential = GoogleAccountCredential.usingOAuth2(this, listOf(DriveScopes.DRIVE))
        credential.selectedAccount = account.account
        return credential
    }

    private fun checkDriveForExistingSetup() {
        isSearching = true
        lifecycleScope.launch {
            try {
                val syncManager = DriveSyncManager(this@SetupWizardActivity, getCredential())
                existingConfigFoundId = syncManager.findExistingConfigId()
                
                if (encryptedInviteKey != null) {
                    currentStep = SetupStep.ENTER_JOIN_PIN
                } else if (existingConfigFoundId != null) {
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
        getSharedPreferences("easypass_prefs", MODE_PRIVATE).edit()
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
