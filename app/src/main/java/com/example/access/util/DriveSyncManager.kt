package com.example.access.util

import android.content.Context
import android.util.Log
import com.example.access.data.AppDatabase
import com.example.access.data.Config
import com.example.access.data.Member
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.*
data class ImportResult(val members: List<Member>, val skippedRows: List<Int>)



class DriveSyncManager(
    private val context: Context,
    val drive: Drive,
    private val isAnonymous: Boolean = false
) {

    private val db = AppDatabase.getDatabase(context)
    private val gson = Gson()

    /**
     * Downloads raw bytes of a file. When the manager was created anonymously
     * (invite-link join, no Google sign-in), the Drive API has no credentials/API
     * key and every call fails with 403. For files shared as "Anyone with the link"
     * we can instead use the public direct-download endpoint over plain HTTP.
     */
    private fun downloadFileBytes(fileId: String): ByteArray? {
        if (!isAnonymous) {
            val outputStream = ByteArrayOutputStream()
            drive.files().get(fileId).executeMediaAndDownloadTo(outputStream)
            return outputStream.toByteArray()
        }
        return try {
            val url = URL("https://drive.google.com/uc?export=download&id=$fileId")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            try {
                if (connection.responseCode !in 200..299) return null
                connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.e("DriveSync", "Anonymous download failed for $fileId", e)
            null
        }
    }

    private val EXCEL_COLUMNS = listOf("MemberID", "FullName", "Status", "QRCodeHash", "LastUpdated", "Phone", "Email", "Address", "Notes")
    private val CONFIG_FOLDER_NAME = "EasyPass-configs"
    
    /**
     * Parses an Excel date string and converts it to ISO-8601 format.
     * Excel cells can contain various date formats, numeric Excel serial dates,
     * or already-formatted ISO strings. This function handles:
     * 1. ISO-8601 strings (yyyy-MM-dd'T'HH:mm:ss'Z') - return as-is
     * 2. Excel numeric serial dates (e.g., "44927" = 2023-01-01)
     * 3. Various date string formats via lenient parsing
     */
    private fun parseExcelDate(dateStr: String): String {
        if (dateStr.isBlank()) return Instant.now().toString()
        
        // Check if it's already an ISO-8601 format (like 2023-01-01T12:00:00Z)
        val isoPattern = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z?\$")
        if (isoPattern.matches(dateStr)) return dateStr
        
        // Try to parse as Excel serial number (days since 1900-01-01)
        try {
            val excelSerial = dateStr.toDouble()
            // Excel's epoch is 1899-12-30 (with 1900 incorrectly treated as leap year)
            val daysSince1899 = excelSerial - 1.0 // Adjust for Excel bug (1900-02-29)
            val millis = (daysSince1899 * 86400000).toLong()
            val date = Date(millis)
            return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(date)
        } catch (_: NumberFormatException) {
            // Not a numeric Excel serial, try parsing as date string
            val dateFormats = listOf(
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd",
                "MM/dd/yyyy HH:mm:ss",
                "MM/dd/yyyy",
                "dd/MM/yyyy HH:mm:ss",
                "dd/MM/yyyy",
                "EEE MMM dd HH:mm:ss z yyyy" // Default Date.toString() format
            )
            
            for (format in dateFormats) {
                try {
                    val parser = SimpleDateFormat(format, Locale.US)
                    parser.isLenient = true
                    val date = parser.parse(dateStr)
                    return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(date)
                } catch (_: Exception) {
                    continue
                }
            }
            
            // If all parsing fails, return current timestamp
            return Instant.now().toString()
        }
    }

    suspend fun getOrCreateConfigFolder(parentId: String): String? = withContext(Dispatchers.IO) {
        try {
            // First search for existing folder
            val result = drive.files().list()
                .setQ("name = '$CONFIG_FOLDER_NAME' and '$parentId' in parents and trashed = false")
                .setSpaces("drive")
                .setSupportsAllDrives(true)
                .setFields("files(id,name)")
                .execute()
            val existing = result.files?.firstOrNull()
            if (existing != null) return@withContext existing.id

            // Create folder
            val folder = com.google.api.services.drive.model.File().apply {
                name = CONFIG_FOLDER_NAME
                mimeType = "application/vnd.google-apps.folder"
                parents = listOf(parentId)
            }
            val created = drive.files().create(folder)
                .setSupportsAllDrives(true)
                .setFields("id")
                .execute()
            return@withContext created.id
        } catch (e: Exception) {
            Log.e("DriveSync", "getOrCreateConfigFolder failed for parent $parentId", e)
            null
        }
    }

    suspend fun moveFileToFolder(fileId: String, newParentId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // Get current parent(s)
            val file = drive.files().get(fileId).setFields("parents").setSupportsAllDrives(true).execute()
            val currentParents = file.parents ?: emptyList()
            if (newParentId in currentParents) return@withContext true // already there

            // Need at least one parent to remove; we'll remove the first parent (should be only one)
            val parentToRemove = currentParents.firstOrNull() ?: return@withContext false

            drive.files().update(fileId, null)
                .setAddParents(newParentId)
                .setRemoveParents(parentToRemove)
                .setSupportsAllDrives(true)
                .execute()
            true
        } catch (e: Exception) {
            Log.e("DriveSync", "moveFileToFolder failed for file $fileId to parent $newParentId", e)
            false
        }
    }

    private suspend fun isConfigInConfigFolder(configId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val parentId = getParentId(configId) ?: return@withContext false
            val parent = drive.files().get(parentId).setFields("name").setSupportsAllDrives(true).execute()
            parent.name == CONFIG_FOLDER_NAME
        } catch (e: Exception) {
            Log.e("DriveSync", "isConfigInConfigFolder failed for config $configId", e)
            false
        }
    }
suspend fun migrateToConfigFolder(configId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // Check if already in config folder
            if (isConfigInConfigFolder(configId)) {
                Log.d("DriveSync", "Config $configId is already in config folder, no migration needed")
                return@withContext true
            }
            
            val config = downloadConfig(configId) ?: return@withContext false
            val parentId = getParentId(configId) ?: return@withContext false
            
            // Get or create config folder in parent
            val configFolderId = getOrCreateConfigFolder(parentId) ?: return@withContext false
            
            // Move config file to config folder
            if (!moveFileToFolder(configId, configFolderId)) {
                Log.e("DriveSync", "Failed to move config file to config folder")
                return@withContext false
            }
            
            // Move database file to config folder
            if (!moveFileToFolder(config.activeDatabaseId, configFolderId)) {
                Log.e("DriveSync", "Failed to move database file to config folder")
                // Try to move config back? For now, just report failure
                return@withContext false
            }
            
            // Move logo file if exists
            config.branding.logoFileId?.let { logoId ->
                if (!moveFileToFolder(logoId, configFolderId)) {
                    Log.w("DriveSync", "Failed to move logo file to config folder, continuing")
                }
            }
            
            Log.d("DriveSync", "Successfully migrated config $configId to config folder")
            true
        } catch (e: Exception) {
            Log.e("DriveSync", "migrateToConfigFolder failed for config $configId", e)
            false
        }
    }
    suspend fun getFolderName(fileId: String): String = withContext(Dispatchers.IO) {
        try {
            val file = drive.files().get(fileId).setFields("name, parents, trashed").execute()
            if (file.trashed == true) return@withContext "Deleted"
            val parentId = file.parents?.firstOrNull() ?: return@withContext "Root"
            val parent = drive.files().get(parentId).setFields("name").execute()
            return@withContext parent.name
        } catch (e: Exception) { 
            Log.e("DriveSync", "getFolderName failed for $fileId", e)
            "Unknown Folder" 
        }
    }

    suspend fun verifyFolderPermissions(folderId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = drive.files().get(folderId).setFields("capabilities").execute()
            return@withContext file.capabilities?.canAddChildren ?: false
        } catch (e: Exception) { 
            Log.e("DriveSync", "verifyFolderPermissions failed for $folderId", e)
            false 
        }
    }

    suspend fun findExistingConfigId(): String? = withContext(Dispatchers.IO) {
        try {
            val result = drive.files().list()
                .setQ("name = 'config.json' and trashed = false")
                .setSpaces("drive")
                .setFields("files(id)")
                .execute()
            return@withContext result.files?.firstOrNull()?.id
        } catch (e: Exception) { 
            Log.e("DriveSync", "findExistingConfigId failed", e)
            null 
        }
    }

    suspend fun findConfigInFolder(folderId: String): String? = withContext(Dispatchers.IO) {
        try {
            Log.d("DriveSync", "Searching for config.json in folder: $folderId")
            
            // First, search inside CONFIG_FOLDER_NAME subfolder (new layout)
            val configFolderResult = drive.files().list()
                .setQ("name = '$CONFIG_FOLDER_NAME' and '$folderId' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false")
                .setSpaces("drive")
                .setSupportsAllDrives(true)
                .setFields("files(id)")
                .execute()
            val configFolderId = configFolderResult.files?.firstOrNull()?.id
            
            if (configFolderId != null) {
                val subfolderSearch = drive.files().list()
                    .setQ("name = 'config.json' and '$configFolderId' in parents and trashed = false")
                    .setSpaces("drive")
                    .setSupportsAllDrives(true)
                    .setFields("files(id)")
                    .execute()
                val foundId = subfolderSearch.files?.firstOrNull()?.id
                if (foundId != null) return@withContext foundId
            }
            
            // Fallback: search directly in parent folder (legacy layout)
            val directResult = drive.files().list()
                .setQ("name = 'config.json' and '$folderId' in parents and trashed = false")
                .setSpaces("drive")
                .setSupportsAllDrives(true)
                .setFields("files(id)")
                .execute()
            val foundId = directResult.files?.firstOrNull()?.id
            if (foundId == null) {
                Log.d("DriveSync", "Direct parent search failed, checking if $folderId is config.json itself")
                try {
                    val file = drive.files().get(folderId).setFields("name").setSupportsAllDrives(true).execute()
                    if (file.name == "config.json") return@withContext folderId
                } catch(e: Exception) {
                    Log.e("DriveSync", "Direct file check failed", e)
                }
            }
            return@withContext foundId
        } catch (e: Exception) {
            Log.e("DriveSync", "findConfigInFolder overall failed for $folderId", e)
            null
        }
    }

    suspend fun downloadConfig(configId: String): Config? = withContext(Dispatchers.IO) {
        try {
            val bytes = downloadFileBytes(configId) ?: return@withContext null
            return@withContext gson.fromJson(String(bytes), Config::class.java)
        } catch (e: Exception) { 
            Log.e("DriveSync", "downloadConfig failed for $configId", e)
            null 
        }
    }

    suspend fun updateConfigOnDrive(configId: String, config: Config) = withContext(Dispatchers.IO) {
        try {
            val configFile = File(context.cacheDir, "config.json").apply { writeText(gson.toJson(config)) }
            val content = FileContent("application/json", configFile)
            drive.files().update(configId, null, content).execute()
        } catch (e: Exception) { 
            Log.e("DriveSync", "updateConfigOnDrive failed", e)
        }
    }

    suspend fun downloadAndParseExcel(fileId: String): ImportResult? = withContext(Dispatchers.IO) {
        try {
            val bytes = downloadFileBytes(fileId) ?: return@withContext null
            val result = WorkbookFactory.create(ByteArrayInputStream(bytes)).use { workbook ->
                val sheet = workbook.getSheetAt(0)
                val formatter = DataFormatter(Locale.US)
                val parsed = mutableListOf<Member>()
                val seenIds = mutableSetOf<String>()
                val skippedRows = mutableListOf<Int>()

                for (i in 1..sheet.lastRowNum) {
                    val row = sheet.getRow(i) ?: continue
                    fun cell(index: Int): String =
                        formatter.formatCellValue(row.getCell(index)).trim()

                    val memberId = cell(0)
                    val fullName = cell(1)
                    val qrToken = cell(3)

                    // Ignore truly empty spreadsheet rows, but reject malformed
                    // member rows so a bad cloud file cannot wipe/poison the cache.
                    if (memberId.isBlank() && fullName.isBlank() && qrToken.isBlank()) continue
                    val rowNumber = i + 1
                    if (memberId.isBlank()) {
                        skippedRows.add(rowNumber)
                        continue
                    }
                    if (fullName.isBlank()) {
                        skippedRows.add(rowNumber)
                        continue
                    }
                    if (qrToken.isBlank()) {
                        skippedRows.add(rowNumber)
                        continue
                    }
                    if (!seenIds.add(memberId)) {
                        skippedRows.add(rowNumber)
                        continue
                    }

                    parsed += Member(
                        memberId = memberId,
                        fullName = fullName,
                        status = cell(2).ifBlank { "Active" },
                        qrCodeHash = qrToken,
                        lastUpdated = parseExcelDate(cell(4)),
                        phone = cell(5).takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) },
                        email = cell(6).takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) },
                        address = cell(7).takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) },
                        notes = cell(8).takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
                    )
                }
                ImportResult(parsed, skippedRows)
            }

            // Cloud Excel is authoritative. A transaction removes members no
            // longer present, preventing revoked/deleted badges from staying valid.
            db.memberDao().replaceAllMembers(result.members)
            result
        } catch (e: Exception) {
            Log.e("DriveSync", "downloadAndParseExcel failed", e)
            null
        }
    }

    suspend fun exportRoomToExcelAndUpload(fileId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val members = db.memberDao().getAllMembersList()
            val workbook = XSSFWorkbook()
            val sheet = workbook.createSheet("Members")
            val header = sheet.createRow(0)
            EXCEL_COLUMNS.forEachIndexed { i, t -> header.createCell(i).setCellValue(t) }
            members.forEachIndexed { i, m ->
                val row = sheet.createRow(i + 1)
                row.createCell(0).setCellValue(m.memberId); row.createCell(1).setCellValue(m.fullName); row.createCell(2).setCellValue(m.status); row.createCell(3).setCellValue(m.qrCodeHash); row.createCell(4).setCellValue(m.lastUpdated); row.createCell(5).setCellValue(m.phone ?: ""); row.createCell(6).setCellValue(m.email ?: ""); row.createCell(7).setCellValue(m.address ?: ""); row.createCell(8).setCellValue(m.notes ?: "")
            }
            val tempFile = File(context.cacheDir, "db.xlsx")
            FileOutputStream(tempFile).use { workbook.write(it) }
            val content = FileContent("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", tempFile)
            drive.files().update(fileId, null, content).execute()
            return@withContext true
        } catch (e: Exception) { 
            Log.e("DriveSync", "exportRoomToExcelAndUpload failed", e)
            return@withContext false 
        }
    }

    suspend fun createInitialFiles(folderId: String, adminHash: String, ownerHash: String): String? = withContext(Dispatchers.IO) {
        try {
            val workbook = XSSFWorkbook()
            val sheet = workbook.createSheet("Members")
            val header = sheet.createRow(0)
            EXCEL_COLUMNS.forEachIndexed { i, t -> header.createCell(i).setCellValue(t) }
            val excelFile = File(context.cacheDir, "initial_db.xlsx")
            FileOutputStream(excelFile).use { workbook.write(it) }
            val configFolderId = getOrCreateConfigFolder(folderId) ?: return@withContext null
            val uploadedExcel = drive.files().create(com.google.api.services.drive.model.File().apply { 
                name = "database.xlsx"; parents = listOf(configFolderId) 
            }, FileContent("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelFile))
                .setSupportsAllDrives(true)
                .setFields("id")
                .execute()
            val config = Config(activeDatabaseId = uploadedExcel.id, roleHashes = mapOf("admin" to adminHash, "owner" to ownerHash), lastUpdated = Instant.now().toString())
            val configFile = File(context.cacheDir, "initial_config.json").apply { writeText(gson.toJson(config)) }
            val uploadedConfig = drive.files().create(com.google.api.services.drive.model.File().apply { 
                name = "config.json"; parents = listOf(configFolderId) 
            }, FileContent("application/json", configFile))
                .setSupportsAllDrives(true)
                .setFields("id")
                .execute()
            return@withContext uploadedConfig.id
        } catch (e: Exception) { 
            Log.e("DriveSync", "createInitialFiles failed", e)
            null 
        }
    }

    suspend fun exportLocalBackup(): File? = withContext(Dispatchers.IO) {
        try {
            val members = db.memberDao().getAllMembersList()
            val workbook = XSSFWorkbook()
            val sheet = workbook.createSheet("MemberDirectory")
            val header = sheet.createRow(0)
            EXCEL_COLUMNS.forEachIndexed { i, t -> header.createCell(i).setCellValue(t) }
            members.forEachIndexed { i, m ->
                val row = sheet.createRow(i + 1)
                row.createCell(0).setCellValue(m.memberId); row.createCell(1).setCellValue(m.fullName); row.createCell(2).setCellValue(m.status); row.createCell(3).setCellValue(m.qrCodeHash); row.createCell(4).setCellValue(m.lastUpdated); row.createCell(5).setCellValue(m.phone ?: ""); row.createCell(6).setCellValue(m.email ?: ""); row.createCell(7).setCellValue(m.address ?: ""); row.createCell(8).setCellValue(m.notes ?: "")
            }
            val file = File(context.cacheDir, "EasyPass_Backup.xlsx")
            FileOutputStream(file).use { workbook.write(it) }
            return@withContext file
        } catch (e: Exception) { 
            Log.e("DriveSync", "exportLocalBackup failed", e)
            null 
        }
    }

    suspend fun importLocalSheet(inputStream: java.io.InputStream): ImportResult? = withContext(Dispatchers.IO) {
        try {
            val result = WorkbookFactory.create(inputStream).use { workbook ->
                val sheet = workbook.getSheetAt(0)
                val formatter = DataFormatter(Locale.US)
                val parsed = mutableListOf<Member>()
                val seenIds = mutableSetOf<String>()
                val skippedRows = mutableListOf<Int>()
                val headerRow = sheet.getRow(0)

                fun normalizedHeader(value: String): String =
                    value.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")

                fun headerIndex(vararg names: String): Int? {
                    if (headerRow == null) return null
                    val wanted = names.map(::normalizedHeader).toSet()
                    for (cellIndex in 0 until headerRow.lastCellNum.coerceAtLeast(0)) {
                        val header = normalizedHeader(formatter.formatCellValue(headerRow.getCell(cellIndex)))
                        if (header in wanted) return cellIndex
                    }
                    return null
                }

                val nameIndex = headerIndex("Name", "FullName", "Full Name", "Member Name")
                val phoneIndex = headerIndex("Phone", "Phone Number", "Mobile", "Mobile Number")
                val emailIndex = headerIndex("Email", "Email Address")
                val addressIndex = headerIndex("Address")
                val notesIndex = headerIndex("Notes", "Note", "Comments")
                val statusIndex = headerIndex("Status")
                val memberIdIndex = headerIndex("MemberID", "Member ID")
                val qrTokenIndex = headerIndex("QRCodeHash", "QR Code Hash", "QR Token")
                val lastUpdatedIndex = headerIndex("LastUpdated", "Last Updated")

                fun uniqueMemberId(): String {
                    while (true) {
                        val candidate = "M-${UUID.randomUUID().toString().replace("-", "").take(24).uppercase(Locale.US)}"
                        if (seenIds.add(candidate)) return candidate
                    }
                }

                for (i in 1..sheet.lastRowNum) {
                    val row = sheet.getRow(i) ?: continue
                    fun cell(index: Int?): String =
                        index?.let { formatter.formatCellValue(row.getCell(it)).trim() }.orEmpty()

                    val fullName = if (nameIndex != null) cell(nameIndex) else cell(1)
                    val phone = if (nameIndex != null) cell(phoneIndex) else cell(5)
                    val email = if (nameIndex != null) cell(emailIndex) else cell(6)
                    val address = if (nameIndex != null) cell(addressIndex) else cell(7)
                    val notes = if (nameIndex != null) cell(notesIndex) else cell(8)

                    if (fullName.isBlank() && phone.isBlank() && email.isBlank() && address.isBlank() && notes.isBlank()) continue
                    if (fullName.isBlank()) {
                        skippedRows.add(i + 1)
                        continue
                    }

                    val importedId = cell(memberIdIndex)
                    val memberId = if (importedId.isNotBlank()) {
                        if (!seenIds.add(importedId)) {
                            skippedRows.add(i + 1)
                            continue
                        }
                        importedId
                    } else {
                        uniqueMemberId()
                    }

                    parsed += Member(
                        memberId = memberId,
                        fullName = fullName,
                        status = cell(statusIndex).ifBlank { "Active" },
                        qrCodeHash = cell(qrTokenIndex).ifBlank { SecurityUtils.generateSecureQrToken() },
                        lastUpdated = cell(lastUpdatedIndex).takeIf(String::isNotBlank)?.let(::parseExcelDate) ?: Instant.now().toString(),
                        phone = phone.takeIf(String::isNotBlank),
                        email = email.takeIf(String::isNotBlank),
                        address = address.takeIf(String::isNotBlank),
                        notes = notes.takeIf(String::isNotBlank)
                    )
                }
                ImportResult(parsed, skippedRows)
            }
            db.memberDao().replaceAllMembers(result.members)
            result
        } catch (e: Exception) {
            Log.e("DriveSync", "importLocalSheet failed", e)
            null
        }
    }

    suspend fun relocateFolder(oldConfigId: String, targetFolderId: String): String? = withContext(Dispatchers.IO) {
        try {
            val oldConfig = downloadConfig(oldConfigId) ?: return@withContext null
            val configFolderId = getOrCreateConfigFolder(targetFolderId) ?: return@withContext null
            
            val newDb = drive.files().copy(oldConfig.activeDatabaseId, com.google.api.services.drive.model.File().apply { 
                name = "database.xlsx"; parents = listOf(configFolderId) 
            }).setSupportsAllDrives(true).execute()
            var newLogoId: String? = null
            oldConfig.branding.logoFileId?.let { 
                try { 
                    newLogoId = drive.files().copy(it, com.google.api.services.drive.model.File().apply { 
                        name = "logo.png"; parents = listOf(configFolderId) 
                    }).setSupportsAllDrives(true).execute().id 
                } catch (e: Exception) { 
                    Log.e("DriveSync", "Failed to copy logo file $it to new folder", e)
                } 
            }
            val newConfig = oldConfig.copy(activeDatabaseId = newDb.id, branding = oldConfig.branding.copy(logoFileId = newLogoId))
            val tempConfigFile = File(context.cacheDir, "new_config.json").apply { writeText(gson.toJson(newConfig)) }
            val uploadedNewConfigId = drive.files().create(com.google.api.services.drive.model.File().apply { 
                name = "config.json"; parents = listOf(configFolderId) 
            }, FileContent("application/json", tempConfigFile))
                .setSupportsAllDrives(true)
                .setFields("id")
                .execute().id
            try { drive.files().delete(oldConfigId).setSupportsAllDrives(true).execute() } catch(e: Exception) {
                Log.e("DriveSync", "Failed to delete old config file $oldConfigId", e)
            }
            try { drive.files().delete(oldConfig.activeDatabaseId).setSupportsAllDrives(true).execute() } catch(e: Exception) {
                Log.e("DriveSync", "Failed to delete old database file ${oldConfig.activeDatabaseId}", e)
            }
            oldConfig.branding.logoFileId?.let { 
                try { drive.files().delete(it).setSupportsAllDrives(true).execute() } catch(e: Exception) {
                    Log.e("DriveSync", "Failed to delete old logo file $it", e)
                }
            }
            return@withContext uploadedNewConfigId
        } catch (e: Exception) { 
            Log.e("DriveSync", "relocateFolder failed for config $oldConfigId to folder $targetFolderId", e)
            null 
        }
    }

    suspend fun getParentId(fileId: String): String? = withContext(Dispatchers.IO) {
        try {
            val file = drive.files().get(fileId).setFields("parents").execute()
            return@withContext file.parents?.firstOrNull()
        } catch (e: Exception) { 
            Log.e("DriveSync", "getParentId failed for file $fileId", e)
            null 
        }
    }

    suspend fun uploadLogo(folderId: String, file: File, existingFileId: String? = null): String? = withContext(Dispatchers.IO) {
        try {
            val content = FileContent("image/png", file)
            if (existingFileId != null) { 
                try { 
                    return@withContext drive.files().update(existingFileId, null, content).setFields("id").execute().id 
                } catch (e: Exception) { 
                    Log.e("DriveSync", "Failed to update existing logo file $existingFileId", e)
                } 
            }
            return@withContext drive.files().create(com.google.api.services.drive.model.File().apply { name = "logo.png"; parents = listOf(folderId) }, content).setFields("id").execute().id
        } catch (e: Exception) { 
            Log.e("DriveSync", "uploadLogo failed for folder $folderId", e)
            null 
        }
    }

    companion object {
        fun createAnonymous(context: Context): DriveSyncManager {
            val drive = Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), null).setApplicationName("EasyPass").build()
            return DriveSyncManager(context, drive, isAnonymous = true)
        }
        
        fun createWithCredential(context: Context, credential: GoogleAccountCredential): DriveSyncManager {
            val drive = Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential).setApplicationName("EasyPass").build()
            return DriveSyncManager(context, drive)
        }
    }
}
