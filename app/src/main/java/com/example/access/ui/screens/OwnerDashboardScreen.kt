package com.example.access.ui.screens

import android.content.Context
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
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import com.example.access.data.Config
import com.example.access.data.FieldConfig
import com.example.access.util.DriveSyncManager
import com.example.access.util.SecurityUtils
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class ThemePalette(val name: String, val primary: String, val secondary: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OwnerDashboardScreen(
    config: Config,
    onConfigUpdated: (Config) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var orgName by remember { mutableStateOf(config.branding.organizationName) }
    var selectedColor by remember { mutableStateOf(config.branding.primaryColor) }
    var adminPass by remember { mutableStateOf("") }
    var ownerPass by remember { mutableStateOf("") }
    var activeFolderName by remember { mutableStateOf("Loading...") }
    var folderLink by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    var showPhone by remember { mutableStateOf(config.branding.fieldConfig.showPhone) }
    var showEmail by remember { mutableStateOf(config.branding.fieldConfig.showEmail) }
    var showAddress by remember { mutableStateOf(config.branding.fieldConfig.showAddress) }
    var showNotes by remember { mutableStateOf(config.branding.fieldConfig.showNotes) }
    var pickedImageUri by remember { mutableStateOf<Uri?>(null) }
    var showCropDialog by remember { mutableStateOf(false) }

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

    LaunchedEffect(config.activeDatabaseId) {
        syncManager?.let { sync ->
            activeFolderName = sync.getFolderName(config.activeDatabaseId)
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

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp).verticalScroll(scrollState), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("Admin Operations", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
        OperationCard("Brand Identity") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Surface(modifier = Modifier.size(100.dp), shape = CircleShape, border = BorderStroke(1.dp, Color(0xFFEEEEEE)), shadowElevation = 2.dp) {
                        if (config.branding.logoFileId != null) {
                            AsyncImage(model = "https://lh3.googleusercontent.com/u/0/d/${config.branding.logoFileId}?v=${System.currentTimeMillis()}", contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.padding(12.dp))
                        } else { Icon(Icons.Default.Business, null, modifier = Modifier.size(48.dp), tint = Color.LightGray) }
                    }
                }
                Button(onClick = { logoLauncher.launch("image/*") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("Upload Logo") }
                OutlinedTextField(value = orgName, onValueChange = { orgName = it }, label = { Text("Business Name") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    palettes.forEach { palette ->
                        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selectedColor == palette.primary) MaterialTheme.colorScheme.primaryContainer else Color(0xFFF9FAFB)).clickable { selectedColor = palette.primary }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(24.dp).background(Color(android.graphics.Color.parseColor(palette.primary)), CircleShape))
                            Spacer(Modifier.width(12.dp))
                            Text(palette.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            if (selectedColor == palette.primary) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Button(onClick = {
                    isBusy = true
                    val updated = config.copy(branding = config.branding.copy(organizationName = orgName, primaryColor = selectedColor))
                    updateConfigOnDrive(context, updated) { onConfigUpdated(it); isBusy = false }
                }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("Save Brand Profile") }
            }
        }
        OperationCard("Data Requirements") {
            Column {
                FieldToggleRow("Phone Access", showPhone) { showPhone = it }
                FieldToggleRow("Email Contact", showEmail) { showEmail = it }
                FieldToggleRow("Physical Address", showAddress) { showAddress = it }
                FieldToggleRow("Management Notes", showNotes) { showNotes = it }
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    isBusy = true
                    val updated = config.copy(branding = config.branding.copy(fieldConfig = FieldConfig(showPhone, showEmail, showAddress, showNotes)))
                    updateConfigOnDrive(context, updated) { onConfigUpdated(it); isBusy = false }
                }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("Update Member Fields") }
            }
        }
        OperationCard("Security Credentials") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(value = adminPass, onValueChange = { adminPass = it }, label = { Text("New Admin Key") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                OutlinedTextField(value = ownerPass, onValueChange = { ownerPass = it }, label = { Text("New Owner Key") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                Button(onClick = {
                    isBusy = true
                    val newH = config.roleHashes.toMutableMap()
                    if (adminPass.isNotEmpty()) newH["admin"] = SecurityUtils.hashPassword(adminPass)
                    if (ownerPass.isNotEmpty()) newH["owner"] = SecurityUtils.hashPassword(ownerPass)
                    updateConfigOnDrive(context, config.copy(roleHashes = newH)) { onConfigUpdated(it); adminPass = ""; ownerPass = ""; isBusy = false }
                }, modifier = Modifier.fillMaxWidth(), enabled = adminPass.isNotEmpty() || ownerPass.isNotEmpty(), shape = RoundedCornerShape(12.dp)) { Text("Update Access Keys") }
            }
        }
        OperationCard("Data Infrastructure") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Storage, null, tint = Color.Gray); Spacer(Modifier.width(12.dp)); Text(activeFolderName, fontWeight = FontWeight.Bold)
                }
                OutlinedTextField(value = folderLink, onValueChange = { folderLink = it }, label = { Text("Migrate Link") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                Button(onClick = {
                    val ext = extractFolderIdFromLink(folderLink)
                    if (ext != null) {
                        isBusy = true
                        scope.launch { if (syncManager?.verifyFolderPermissions(ext) == true) relocate(context, ext) { onConfigUpdated(it); isBusy = false; folderLink = "" } else { isBusy = false; Toast.makeText(context, "Editor required", Toast.LENGTH_LONG).show() } }
                    }
                }, modifier = Modifier.fillMaxWidth(), enabled = folderLink.contains("folders/"), shape = RoundedCornerShape(12.dp)) { Text("Relocate Hub") }
            }
        }
        Spacer(modifier = Modifier.height(100.dp))
    }
    if (isBusy) com.example.access.ui.components.LoadingOverlay(isVisible = true, message = "Processing...")
}

@Composable
fun OperationCard(title: String, content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black, color = Color.Gray, letterSpacing = 1.sp)
            Spacer(Modifier.height(20.dp)); content()
        }
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
    (context as androidx.activity.ComponentActivity).lifecycleScope.launch { sync.updateConfigOnDrive(configId, config); onSuccess(config) }
}

private suspend fun uploadCroppedLogo(context: Context, bitmap: Bitmap, onSuccess: (Config) -> Unit, config: Config) {
    val account = GoogleSignIn.getLastSignedInAccount(context) ?: return
    val cred = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE)).apply { selectedAccount = account.account }
    val sync = DriveSyncManager(context, DriveSyncManager.createWithCredential(context, cred).drive)
    val configId = context.getSharedPreferences("easypass_prefs", Context.MODE_PRIVATE).getString("config_file_id", null) ?: return
    val file = File(context.cacheDir, "logo_cropped.png")
    withContext(Dispatchers.IO) { FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    val parentId = sync.getParentId(configId) ?: "root"
    sync.uploadLogo(parentId, file, config.branding.logoFileId)?.let { 
        val updated = config.copy(branding = config.branding.copy(logoFileId = it)); sync.updateConfigOnDrive(configId, updated)
        withContext(Dispatchers.Main) { onSuccess(updated) }
    }
}
