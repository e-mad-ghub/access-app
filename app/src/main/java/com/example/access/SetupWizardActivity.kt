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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.easyapps.easypass.R
import com.example.access.ui.components.LoadingOverlay
import com.example.access.ui.components.SetupOrientationDialog
import com.example.access.ui.components.hasSeenSetupOrientation
import com.example.access.ui.components.markSetupOrientationSeen
import com.example.access.ui.components.secretElevation
import com.example.access.ui.theme.AccessTheme
import com.example.access.util.ConfigLoadResult
import com.example.access.util.DatabaseLoadResult
import com.example.access.util.DriveSyncManager
import com.example.access.util.DriveFilePicker
import com.example.access.util.FileDownloadResult
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

    private val driveFilePicker = DriveFilePicker(this)

    enum class SetupStep { SIGN_IN_OPTION, PASSWORDS, PASTE_LINK, CONFIRMATION, RESTORE_FOUND, ENTER_JOIN_PIN }

    private var googleAccount by mutableStateOf<GoogleSignInAccount?>(null)
    private var currentStep by mutableStateOf(SetupStep.SIGN_IN_OPTION)
    private var isSearching by mutableStateOf(false)
    private var isBusy by mutableStateOf(false)
    private var existingConfigFoundId by mutableStateOf<String?>(null)
    private var errorMessage by mutableStateOf<String?>(null)
    private var showSetupOrientation by mutableStateOf(false)
    private var runRestoreCheckAfterOrientation = false
    
    private var selectedFolderName by mutableStateOf("")
    private var selectedFolderId by mutableStateOf("")

    private var adminPass by mutableStateOf("")
    private var ownerPass by mutableStateOf("")
    private var adminPassConfirm by mutableStateOf("")
    private var ownerPassConfirm by mutableStateOf("")

    private var encryptedInviteKey by mutableStateOf<String?>(null)
    private var pendingInvitePayload by mutableStateOf<SecurityUtils.InvitePayload?>(null)

    private val signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                googleAccount = task.getResult(ApiException::class.java)
                pendingInvitePayload?.let(::finalizeRestore) ?: checkDriveForExistingSetup()
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

        showSetupOrientation = !hasSeenSetupOrientation(this)

        if (encryptedInviteKey != null) {
            currentStep = SetupStep.ENTER_JOIN_PIN
        } else if (googleAccount != null) {
            if (showSetupOrientation) {
                runRestoreCheckAfterOrientation = true
            } else {
                checkDriveForExistingSetup()
            }
        }

        setContent {
            AccessTheme {
                Box(modifier = Modifier.fillMaxSize().background(Color(0xFFFAFBFC))) {
                    SetupWizardScreen()
                    LoadingOverlay(isVisible = isBusy, message = "Securing Connection...")
                }
            }
        }
    }

    @Composable
    fun SetupWizardScreen() {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            Surface(
                modifier = Modifier.size(96.dp),
                shape = RoundedCornerShape(24.dp),
                color = Color.White,
                shadowElevation = 1.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Image(
                        painter = painterResource(id = R.drawable.easypass_logo),
                        contentDescription = null,
                        modifier = Modifier.size(72.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(20.dp))
            Text("easyPass", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            
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

            if (showSetupOrientation) {
                SetupOrientationDialog(
                    onFinished = { finishSetupOrientation() }
                )
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
            pendingInvitePayload = null
            runRestoreCheckAfterOrientation = false
        }
    }

    private fun finishSetupOrientation() {
        markSetupOrientationSeen(this)
        showSetupOrientation = false
        if (runRestoreCheckAfterOrientation) {
            runRestoreCheckAfterOrientation = false
            checkDriveForExistingSetup()
        }
    }

    @Composable
    fun SignInStep() {
        var showManualJoin by remember { mutableStateOf(false) }
        var manualLink by remember { mutableStateOf("") }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Affordable QR access control for small and medium organizations.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.DarkGray
            )
            Spacer(modifier = Modifier.height(24.dp))

            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDFA)), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF00BFA5), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Affordable, lightweight, and private by design. easyPass keeps your organization database in your own Google Drive and uses minimal device storage.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF006064)
                        )
                    }
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Info, null, tint = Color(0xFF00BFA5), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Every device signs in with its approved Google account before easyPass loads the organization.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF006064)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = {
                    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail().requestScopes(Scope(DriveScopes.DRIVE_FILE)).build()
                    signInLauncher.launch(GoogleSignIn.getClient(this@SetupWizardActivity, gso).signInIntent)
                }, 
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                Icon(Icons.Default.CloudUpload, null)
                Spacer(Modifier.width(12.dp))
                Text("Create or Restore Organization", fontWeight = FontWeight.Black)
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Create or restore an organization database using your Google Drive.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedButton(
                onClick = { showManualJoin = true },
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                Icon(Icons.Default.AddLink, null)
                Spacer(Modifier.width(12.dp))
                Text("Join Existing Organization", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Connect this device using an invitation from an existing organization.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = { showSetupOrientation = true }) {
                Icon(Icons.Default.Info, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("How easyPass works")
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
        var secretInput by remember { mutableStateOf("") }
        var isVerifying by remember { mutableStateOf(false) }
        var revealAccessCode by remember { mutableStateOf(false) }
        val inviteCredentialType = remember(encryptedInviteKey) {
            encryptedInviteKey?.let(SecurityUtils::getInviteCredentialType)
                ?: SecurityUtils.InviteCredentialType.PIN
        }
        val usesAccessCode = inviteCredentialType == SecurityUtils.InviteCredentialType.ACCESS_CODE
        val normalizedAccessCode = remember(secretInput) { SecurityUtils.normalizeInviteAccessCode(secretInput) }
        val normalizedPin = remember(secretInput) { secretInput.filter(Char::isDigit) }
        val canUnlock = if (usesAccessCode) {
            SecurityUtils.isValidInviteAccessCode(secretInput)
        } else {
            normalizedPin.length == 4
        }

        Column {
            Text("Access Unlock", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text(
                if (usesAccessCode) "Enter the invitation access code provided with your link."
                else "Enter the 4-digit PIN provided with your link.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(32.dp))
            OutlinedTextField(
                value = secretInput,
                onValueChange = {
                    secretInput = if (usesAccessCode) {
                        SecurityUtils.formatInviteAccessCode(
                            SecurityUtils.normalizeInviteAccessCode(it).take(16)
                        )
                    } else {
                        it.filter(Char::isDigit).take(4)
                    }
                },
                label = { Text(if (usesAccessCode) "Invitation access code" else "Join PIN") },
                placeholder = {
                    if (usesAccessCode) {
                        Text("ABCD-EFGH-JKLM-NPQR")
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (usesAccessCode) KeyboardType.Password else KeyboardType.NumberPassword
                ),
                visualTransformation = when {
                    !usesAccessCode -> PasswordVisualTransformation()
                    revealAccessCode -> VisualTransformation.None
                    else -> PasswordVisualTransformation()
                },
                trailingIcon = {
                    if (usesAccessCode) {
                        IconButton(onClick = { revealAccessCode = !revealAccessCode }) {
                            Icon(
                                if (revealAccessCode) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (revealAccessCode) "Hide access code" else "Show access code"
                            )
                        }
                    }
                },
                supportingText = {
                    if (usesAccessCode) {
                        Text("Hyphens are optional. You can enter ${normalizedAccessCode.length}/16 characters.")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(48.dp))
            Button(
                onClick = {
                    isVerifying = true
                    errorMessage = null
                    val secret = if (usesAccessCode) normalizedAccessCode else normalizedPin
                    val invitePayload = SecurityUtils.decryptInvitePayload(encryptedInviteKey!!, secret)
                    if (invitePayload != null) {
                        pendingInvitePayload = invitePayload
                        isVerifying = false
                        if (googleAccount == null) {
                            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                                .requestEmail()
                                .requestScopes(Scope(DriveScopes.DRIVE_FILE))
                                .build()
                            signInLauncher.launch(GoogleSignIn.getClient(this@SetupWizardActivity, gso).signInIntent)
                        } else {
                            finalizeRestore(invitePayload)
                        }
                    } else {
                        errorMessage = if (usesAccessCode) {
                            "Incorrect invitation access code. The link could not be unlocked."
                        } else {
                            "Incorrect PIN. The link could not be unlocked."
                        }
                        isVerifying = false
                    }
                },
                enabled = canUnlock && !isVerifying,
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
        val adminMismatch = adminPassConfirm.isNotEmpty() && adminPassConfirm != adminPass
        val ownerMismatch = ownerPassConfirm.isNotEmpty() && ownerPassConfirm != ownerPass
        val keysMatchEachOther = adminPass.isNotEmpty() && ownerPass.isNotEmpty() && adminPass == ownerPass
        val canContinue = adminPass.isNotEmpty() &&
            ownerPass.isNotEmpty() &&
            adminPassConfirm == adminPass &&
            ownerPassConfirm == ownerPass &&
            !keysMatchEachOther

        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text("Already have an organization?", fontWeight = FontWeight.Bold)
            Text("Choose its existing config.json in Google Drive to restore it. You do not need to create new passwords.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { chooseExistingOrganization() }, modifier = Modifier.fillMaxWidth()) {
                Text("Restore existing organization")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text("Security Profile", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text("Set private keys to authorize management actions. These keys stay separate from your Google account.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Spacer(modifier = Modifier.height(32.dp))
            OutlinedTextField(
                value = adminPass,
                onValueChange = { adminPass = it },
                label = { Text("New Manager Password") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = adminPassConfirm,
                onValueChange = { adminPassConfirm = it },
                label = { Text("Confirm Manager Password") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
                isError = adminMismatch,
                supportingText = {
                    if (adminMismatch) Text("Manager password confirmation does not match.")
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = ownerPass,
                onValueChange = { ownerPass = it },
                label = { Text("New Director Password") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = ownerPassConfirm,
                onValueChange = { ownerPassConfirm = it },
                label = { Text("Confirm Director Password") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
                isError = ownerMismatch,
                supportingText = {
                    if (ownerMismatch) Text("Director password confirmation does not match.")
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            if (keysMatchEachOther) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Manager and Director passwords must be different.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(modifier = Modifier.height(48.dp))
            Button(onClick = { currentStep = SetupStep.PASTE_LINK }, enabled = canContinue, modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(20.dp)) { Text("Setup Storage Hub", fontWeight = FontWeight.Black) }
        }
    }

    @Composable
    fun PasteLinkStep() {
        var link by remember { mutableStateOf("") }
        var isVerifying by remember { mutableStateOf(false) }
        Column {
            IconButton(onClick = { currentStep = SetupStep.PASSWORDS }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
            Text("Data Storage", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text("Paste the destination folder link, then select that folder in Google Drive. The app creates the organization files afterward; the folder can be empty.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Spacer(Modifier.height(32.dp))
            OutlinedTextField(value = link, onValueChange = { link = it }, label = { Text("Folder Link") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), placeholder = { Text("https://drive.google.com/...") })
            Spacer(modifier = Modifier.height(24.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDFA)), shape = RoundedCornerShape(20.dp)) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Info, null, tint = Color(0xFF00BFA5), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("easyPass creates one private folder named EasyPass-configs inside the folder you choose. The shared database stays in your Drive, while the app keeps only the needed local copy on the device.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF006064))
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
                                val selected = driveFilePicker.selectFolder(googleAccount!!.account!!)
                                if (selected != id) {
                                    errorMessage = if (selected == null) "Folder selection cancelled. Select the destination folder itself, not a file inside it."
                                        else "The selected folder does not match your link. Select the same destination folder to continue."
                                    return@launch
                                }
                                if (syncManager.verifyFolderPermissions(id)) {
                                    selectedFolderId = id
                                    selectedFolderName = syncManager.getDriveItemName(id)
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
                else Text("Select Folder & Continue", fontWeight = FontWeight.Black)
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
                "easyPass will create a private EasyPass-configs folder inside this Google Drive location. That private folder becomes the shared source of truth for your organization's members, settings, and Pro status while easyPass keeps device storage minimal.",
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
                            val syncManager = DriveSyncManager.createWithCredential(this@SetupWizardActivity, getCredential())
                            val configId = syncManager.findConfigInFolder(selectedFolderId)
                            if (configId != null) finalizeRestore(configId)
                            else startNewSetup(selectedFolderId, adminPass, ownerPass)
                        } catch (e: Exception) { errorMessage = "Handshake Failed."; isBusy = false }
                    }
                }, 
                modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(20.dp)
            ) { Text("Connect to easyPass", fontWeight = FontWeight.Black) }
        }
    }

    @Composable
    fun RestoreFoundStep() {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.CloudDone, null, modifier = Modifier.size(80.dp), tint = Color(0xFF00BFA5))
            Spacer(modifier = Modifier.height(24.dp))
            Text("easyPass Hub Detected", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            Spacer(modifier = Modifier.height(48.dp))
            Button(onClick = { isBusy = true; finalizeRestore(existingConfigFoundId!!) }, modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(20.dp)) { Text("Restore This Hub", fontWeight = FontWeight.Black) }
            TextButton(onClick = { chooseExistingOrganization() }) { Text("Choose a different organization") }
            TextButton(onClick = { currentStep = SetupStep.PASSWORDS; existingConfigFoundId = null }) { Text("Create New Instead", fontWeight = FontWeight.Bold) }
        }
    }

    private fun extractFolderIdFromLink(link: String): String? {
        val regex = Regex("folders/([^/?]+)")
        return regex.find(link)?.groupValues?.get(1)
    }

    private fun getCredential(): GoogleAccountCredential {
        val account = googleAccount!!
        val credential = GoogleAccountCredential.usingOAuth2(this, listOf(DriveScopes.DRIVE_FILE))
        credential.selectedAccount = account.account
        return credential
    }

    private fun checkDriveForExistingSetup() {
        if (googleAccount == null) return
        isSearching = true
        lifecycleScope.launch {
            try {
                if (!driveFilePicker.authorize(googleAccount!!.account!!)) {
                    errorMessage = "Google Drive access was cancelled. Sign in again to continue."
                    currentStep = SetupStep.SIGN_IN_OPTION
                    return@launch
                }
                val syncManager = DriveSyncManager.createWithCredential(this@SetupWizardActivity, getCredential())
                existingConfigFoundId = syncManager.findExistingConfigId()
                currentStep = if (existingConfigFoundId != null) SetupStep.RESTORE_FOUND else SetupStep.PASSWORDS
            } catch (e: Exception) { errorMessage = "Cloud Error."; currentStep = SetupStep.SIGN_IN_OPTION } finally { isSearching = false }
        }
    }

    private fun chooseExistingOrganization() {
        lifecycleScope.launch {
            isBusy = true
            var selectedConfigId: String? = null
            try {
                val account = googleAccount?.account ?: return@launch
                val selected = driveFilePicker.select(account, mimeTypes = "application/json")
                if (selected?.size == 1) {
                    selectedConfigId = selected.single()
                } else if (selected != null) {
                    errorMessage = "Choose one organization's config.json to restore."
                }
            } catch (error: Exception) {
                errorMessage = "Could not open Google Drive. Check your connection and try again."
            } finally {
                isBusy = false
            }
            selectedConfigId?.let(::finalizeRestore)
        }
    }

    private fun finalizeRestore(configId: String) {
        finalizeRestore(SecurityUtils.InvitePayload(configId = configId))
    }

    private fun finalizeRestore(invitePayload: SecurityUtils.InvitePayload) {
        isBusy = true
        errorMessage = null
        lifecycleScope.launch {
            try {
                val account = googleAccount
                if (account?.email.isNullOrBlank()) {
                    errorMessage = "Sign in with an approved Google account to use this organization."
                    return@launch
                }
                val signedInEmail = account!!.email!!.trim().lowercase()
                val invitedEmail = invitePayload.invitedEmail?.trim()?.lowercase()
                if (!invitedEmail.isNullOrBlank() && signedInEmail != invitedEmail) {
                    errorMessage = "This invitation was issued for a different Google account. Sign in using the invited email."
                    return@launch
                }
                val syncManager = DriveSyncManager.createWithCredential(this@SetupWizardActivity, getCredential())
                if (!driveFilePicker.authorize(account.account!!) ||
                    !driveFilePicker.ensureAccess(syncManager.drive, account.account!!, listOf(invitePayload.configId))) {
                    errorMessage = "Select this organization's config.json in Google Drive to connect. Your invitation is still valid."
                    return@launch
                }
                when (val configResult = syncManager.downloadConfigResult(invitePayload.configId)) {
                    is ConfigLoadResult.Success -> {
                        val config = configResult.config
                        if (!config.hasAccessRoster()) {
                            errorMessage = "This organization must be secured with approved Google emails before it can be used on this app version."
                            return@launch
                        }
                        val rosterEntry = config.activeAccessEntryFor(signedInEmail)
                        if (rosterEntry == null) {
                            errorMessage = if (!invitedEmail.isNullOrBlank()) {
                                "This Google account is not approved for this organization. Ask a Director for access."
                            } else {
                                "Organization access has been removed for this Google account. Ask a Director for a new invitation if needed."
                            }
                            return@launch
                        }
                        val databaseId = config.activeDatabaseId
                        if (databaseId.isBlank()) {
                            errorMessage = "Organization database unavailable. Use a current invitation link."
                        } else {
                            if (!driveFilePicker.ensureAccess(syncManager.drive, account.account!!, listOf(databaseId))) {
                                errorMessage = "Select this organization's database.xlsx in Google Drive to finish connecting."
                                return@launch
                            }
                            when (syncManager.downloadAndParseExcelResult(databaseId, config.isPro)) {
                                is DatabaseLoadResult.Success -> {
                                    getSharedPreferences("easypass_prefs", MODE_PRIVATE).edit().putString("config_file_id", invitePayload.configId).apply()
                                    startActivity(Intent(this@SetupWizardActivity, MainActivity::class.java))
                                    finish()
                                }
                                is DatabaseLoadResult.DownloadFailure,
                                is DatabaseLoadResult.ParseFailure -> {
                                    errorMessage = "Organization database unavailable. Try again or use a current invitation link."
                                }
                            }
                        }
                    }
                    is ConfigLoadResult.DownloadFailure -> {
                        errorMessage = mapConfigRestoreError(configResult.error)
                    }
                    is ConfigLoadResult.ParseFailure -> {
                        errorMessage = "Organization connection unavailable. Use a current invitation link."
                    }
                }
            } catch (e: Exception) {
                errorMessage = "Connection unavailable. Try again."
            } finally {
                isBusy = false
            }
        }
    }

    private fun startNewSetup(folderId: String, adminPass: String, ownerPass: String) {
        lifecycleScope.launch {
            try {
                val syncManager = DriveSyncManager.createWithCredential(this@SetupWizardActivity, getCredential())
                val ownerEmail = googleAccount?.email?.trim()?.lowercase()
                if (ownerEmail.isNullOrBlank()) {
                    errorMessage = "Sign in with Google before creating an organization."
                    isBusy = false
                    return@launch
                }
                val configId = syncManager.createInitialFiles(folderId, SecurityUtils.hashPassword(adminPass), SecurityUtils.hashPassword(ownerPass), ownerEmail)
                if (configId != null) finalizeRestore(configId)
            } catch (e: Exception) { errorMessage = "Init failed."; isBusy = false }
        }
    }

    private fun mapConfigRestoreError(error: FileDownloadResult): String {
        return when (error) {
            is FileDownloadResult.HttpError -> when (error.statusCode) {
                403 -> "This Google account is not approved for this organization. Ask a Director for access."
                404, 410 -> "This invitation is no longer valid. Ask an organization Director for a current invitation link."
                else -> "Connection unavailable. Try again or use a current invitation link."
            }
            is FileDownloadResult.NetworkError,
            is FileDownloadResult.UnexpectedError,
            is FileDownloadResult.Success -> "Connection unavailable. Try again or use a current invitation link."
        }
    }
}
