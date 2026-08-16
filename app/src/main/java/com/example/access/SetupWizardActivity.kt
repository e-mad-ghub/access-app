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
import com.easyapps.easypass.R
import com.example.access.ui.components.LoadingOverlay
import com.example.access.ui.components.secretElevation
import com.example.access.util.DriveSyncManager
import com.example.access.util.SecurityUtils
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.launch

class SetupWizardActivity : ComponentActivity() {

    enum class SetupStep { SIGN_IN_OPTION, PASSWORDS, PASTE_LINK, CONFIRMATION, RESTORE_FOUND, ENTER_JOIN_PIN }

    private var googleAccount by mutableStateOf<GoogleSignInAccount?>(null)
    private var currentStep by mutableStateOf(SetupStep.SIGN_IN_OPTION)
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
                errorMessage = "Google login failed (${e.statusCode}: ${CommonStatusCodes.getStatusCodeString(e.statusCode)})."
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        googleAccount = GoogleSignIn.getLastSignedInAccount(this)
        
        val data = intent.data
        if (data != null && (data.host == "join" || data.host == "easyapps-solutions.com")) {
            encryptedInviteKey = data.getQueryParameter("key")
        }

        // --- 1. ANONYMOUS STARTUP LOGIC ---
        // If we have an invite key, go straight to PIN. NO Google Sign-In required.
        if (encryptedInviteKey != null) {
            currentStep = SetupStep.ENTER_JOIN_PIN
        } else if (googleAccount != null) {
            checkDriveForExistingSetup()
        }

        setContent {
            Box(modifier = Modifier.fillMaxSize().background(Color(0xFFF8F9FB))) {
                SetupWizardScreen()
                LoadingOverlay(isVisible = isBusy, message = "Securing Connection...")
            }
        }
    }

    @Composable
    fun SetupWizardScreen() {
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Logo - Matches Black Master Asset Exactly
            Surface(
                modifier = Modifier.size(90.dp),
                shape = CircleShape,
                color = Color.Black,
                shadowElevation = 4.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_pass_logo),
                        contentDescription = null,
                        modifier = Modifier.size(60.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(20.dp))
            Text("EASYPASS", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            
            // SECRET GESTURE: Tap "Organization Hub" 5 times
            Text(
                text = "Organization Hub", 
                style = MaterialTheme.typography.labelSmall, 
                color = Color(0xFF00BFA5), 
                fontWeight = FontWeight.Bold,
                modifier = Modifier.secretElevation {
                    // Discreet Toast to confirm secret trigger works in Wizard
                    Toast.makeText(this@SetupWizardActivity, "System Keys Active", Toast.LENGTH_SHORT).show()
                }
            )
            
            Spacer(modifier = Modifier.height(40.dp))

            if (errorMessage != null) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)), shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = errorMessage!!, color = Color(0xFFD32F2F), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { signOutAndReset() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)), shape = RoundedCornerShape(12.dp)) {
                            Text("Reset Session")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            AnimatedContent(targetState = currentStep, label = "StepTransition") { step ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    when (step) {
                        SetupStep.SIGN_IN_OPTION -> SignInStep()
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
            currentStep = SetupStep.SIGN_IN_OPTION
            errorMessage = null
            encryptedInviteKey = null
        }
    }

    @Composable
    fun SignInStep() {
        var showManualJoin by remember { mutableStateOf(false) }
        var manualLink by remember { mutableStateOf("") }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Welcome to the world's most effortless membership system.", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge, color = Color.DarkGray)
            Spacer(modifier = Modifier.height(24.dp))

            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDFA)), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Info, null, tint = Color(0xFF00BFA5), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Google sign-in is only needed when creating or managing an organization, so EasyPass can write your shared database to Google Drive.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF006064)
                        )
                    }
                    Text(
                        "Members who join with an invitation link can connect without signing in.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = {
                    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail().requestScopes(Scope(DriveScopes.DRIVE)).build()
                    signInLauncher.launch(GoogleSignIn.getClient(this@SetupWizardActivity, gso).signInIntent)
                }, 
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                Icon(Icons.Default.CloudUpload, null)
                Spacer(Modifier.width(12.dp))
                Text("Sign in & Setup Organization", fontWeight = FontWeight.Black)
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedButton(
                onClick = { showManualJoin = true },
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                Icon(Icons.Default.AddLink, null)
                Spacer(Modifier.width(12.dp))
                Text("Join Organization", fontWeight = FontWeight.Bold)
            }
        }

        if (showManualJoin) {
            AlertDialog(
                onDismissRequest = { showManualJoin = false },
                title = { Text("Organization Join") },
                text = {
                    Column {
                        Text("Paste your secure invitation link below.", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(value = manualLink, onValueChange = { manualLink = it }, label = { Text("Invitation Link") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val key = android.net.Uri.parse(manualLink).getQueryParameter("key")
                        if (key != null) {
                            encryptedInviteKey = key
                            currentStep = SetupStep.ENTER_JOIN_PIN
                            showManualJoin = false
                        }
                    }) { Text("Continue") }
                },
                dismissButton = { TextButton(onClick = { showManualJoin = false }) { Text("Cancel") } }
            )
        }
    }

    @Composable
    fun EnterJoinPinStep() {
        var pin by remember { mutableStateOf("") }
        var isVerifying by remember { mutableStateOf(false) }

        Column {
            Text("Access Unlock", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text("Enter the 4-digit PIN provided with your link.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Spacer(modifier = Modifier.height(32.dp))
            OutlinedTextField(value = pin, onValueChange = { if (it.length <= 4) pin = it }, label = { Text("Join PIN") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), singleLine = true)
            Spacer(modifier = Modifier.height(48.dp))
            Button(
                onClick = {
                    isVerifying = true
                    errorMessage = null
                    val decryptedId = SecurityUtils.decryptInvite(encryptedInviteKey!!, pin)
                    if (decryptedId != null) {
                        // SUCCESS: PIN is correct. Instantly connect and bypass all searching.
                        finalizeRestore(decryptedId)
                    } else {
                        errorMessage = "Incorrect PIN. The link could not be unlocked."
                        isVerifying = false
                    }
                },
                enabled = pin.length == 4 && !isVerifying,
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                if (isVerifying) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                else Text("Unlock & Connect", fontWeight = FontWeight.Black)
            }
        }
    }
    
    @Composable
    fun PasswordStep() {
        Column {
            Text("Security Profile", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text("Set private keys to authorize management actions. These keys stay separate from your Google account.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Spacer(modifier = Modifier.height(32.dp))
            OutlinedTextField(value = adminPass, onValueChange = { adminPass = it }, label = { Text("New Admin Key") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), singleLine = true)
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(value = ownerPass, onValueChange = { ownerPass = it }, label = { Text("New Owner Key") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), singleLine = true)
            Spacer(modifier = Modifier.height(48.dp))
            Button(onClick = { currentStep = SetupStep.PASTE_LINK }, enabled = adminPass.isNotEmpty() && ownerPass.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(20.dp)) { Text("Setup Storage Hub", fontWeight = FontWeight.Black) }
        }
    }

    @Composable
    fun PasteLinkStep() {
        var link by remember { mutableStateOf("") }
        var isVerifying by remember { mutableStateOf(false) }
        Column {
            IconButton(onClick = { currentStep = SetupStep.PASSWORDS }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
            Text("Data Storage", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text("Paste the Google Drive folder where EasyPass can store the organization database.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Spacer(Modifier.height(32.dp))
            OutlinedTextField(value = link, onValueChange = { link = it }, label = { Text("Folder Link") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), placeholder = { Text("https://drive.google.com/...") })
            Spacer(modifier = Modifier.height(24.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDFA)), shape = RoundedCornerShape(20.dp)) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Info, null, tint = Color(0xFF00BFA5), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("EasyPass writes only its organization files in this folder. Share it as 'Editor' so authorized devices can sync updates.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF006064))
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
                            try {
                                val syncManager = DriveSyncManager.createWithCredential(this@SetupWizardActivity, getCredential())
                                if (syncManager.verifyFolderPermissions(id)) {
                                    selectedFolderId = id
                                    selectedFolderName = syncManager.getFolderName(id)
                                    currentStep = SetupStep.CONFIRMATION
                                } else { errorMessage = "Access Denied: Folder must be shared as 'Editor'." }
                            } catch (e: Exception) { errorMessage = "Verification Failed." } finally { isVerifying = false }
                        }
                    } else { errorMessage = "Invalid link." }
                }, 
                enabled = link.contains("folders/") && !isVerifying,
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                if (isVerifying) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                else Text("Verify & Continue", fontWeight = FontWeight.Black)
            }
        }
    }

    @Composable
    fun ConfirmationStep() {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(80.dp), tint = Color(0xFF00BFA5))
            Spacer(modifier = Modifier.height(24.dp))
            Text("Ready for Launch", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text(selectedFolderName.uppercase(), fontWeight = FontWeight.Black, color = Color(0xFF00BFA5), fontSize = 20.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "This Google Drive folder will become the shared source of truth for your organization's members, settings, and Pro status.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(48.dp))
            Button(
                onClick = {
                    isBusy = true
                    lifecycleScope.launch {
                        try {
                            val syncManager = if (googleAccount != null) DriveSyncManager.createWithCredential(this@SetupWizardActivity, getCredential()) else DriveSyncManager.createAnonymous(this@SetupWizardActivity)
                            val configId = syncManager.findConfigInFolder(selectedFolderId)
                            if (configId != null) finalizeRestore(configId)
                            else if (googleAccount != null) startNewSetup(selectedFolderId, adminPass, ownerPass)
                            else { errorMessage = "Join Failed: No config found and not signed in."; isBusy = false }
                        } catch (e: Exception) { errorMessage = "Handshake Failed."; isBusy = false }
                    }
                }, 
                modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(20.dp)
            ) { Text("Connect to EasyPass", fontWeight = FontWeight.Black) }
        }
    }

    @Composable
    fun RestoreFoundStep() {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.CloudDone, null, modifier = Modifier.size(80.dp), tint = Color(0xFF00BFA5))
            Spacer(modifier = Modifier.height(24.dp))
            Text("EasyPass Hub Detected", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            Spacer(modifier = Modifier.height(48.dp))
            Button(onClick = { isBusy = true; finalizeRestore(existingConfigFoundId!!) }, modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(20.dp)) { Text("Restore This Hub", fontWeight = FontWeight.Black) }
            TextButton(onClick = { currentStep = SetupStep.PASSWORDS; existingConfigFoundId = null }) { Text("Create New Instead", fontWeight = FontWeight.Bold) }
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
        if (googleAccount == null) return
        isSearching = true
        lifecycleScope.launch {
            try {
                val syncManager = DriveSyncManager.createWithCredential(this@SetupWizardActivity, getCredential())
                existingConfigFoundId = syncManager.findExistingConfigId()
                currentStep = if (existingConfigFoundId != null) SetupStep.RESTORE_FOUND else SetupStep.PASSWORDS
            } catch (e: Exception) { errorMessage = "Cloud Error."; currentStep = SetupStep.SIGN_IN_OPTION } finally { isSearching = false }
        }
    }

    private fun finalizeRestore(configId: String) {
        getSharedPreferences("easypass_prefs", MODE_PRIVATE).edit().putString("config_file_id", configId).apply()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun startNewSetup(folderId: String, adminPass: String, ownerPass: String) {
        lifecycleScope.launch {
            try {
                val syncManager = DriveSyncManager.createWithCredential(this@SetupWizardActivity, getCredential())
                val configId = syncManager.createInitialFiles(folderId, SecurityUtils.hashPassword(adminPass), SecurityUtils.hashPassword(ownerPass))
                if (configId != null) finalizeRestore(configId)
            } catch (e: Exception) { errorMessage = "Init failed."; isBusy = false }
        }
    }
}
