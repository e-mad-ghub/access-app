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
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

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
            Log.d("DriveSync", "Searching for config.json in parent: $folderId")
            val result = drive.files().list()
                .setQ("name = 'config.json' and '$folderId' in parents and trashed = false")
                .setSpaces("drive")
                .setFields("files(id)")
                .execute()
            
            val foundId = result.files?.firstOrNull()?.id
            if (foundId == null) {
                Log.d("DriveSync", "Direct parent search failed, checking if $folderId is config.json itself")
                try {
                    val file = drive.files().get(folderId).setFields("name").execute()
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

    suspend fun downloadAndParseExcel(fileId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val bytes = downloadFileBytes(fileId) ?: return@withContext false
            val workbook = WorkbookFactory.create(ByteArrayInputStream(bytes))
            val sheet = workbook.getSheetAt(0)
            val members = mutableListOf<Member>()
            for (i in 1..sheet.lastRowNum) {
                val row = sheet.getRow(i) ?: continue
                members.add(Member(
                    memberId = row.getCell(0)?.toString()?.substringBefore(".") ?: "",
                    fullName = row.getCell(1)?.toString() ?: "",
                    status = row.getCell(2)?.toString() ?: "Active",
                    qrCodeHash = row.getCell(3)?.toString() ?: "",
                    lastUpdated = row.getCell(4)?.toString() ?: "",
                    phone = row.getCell(5)?.toString()?.takeIf { it != "null" && it.isNotBlank() },
                    email = row.getCell(6)?.toString()?.takeIf { it != "null" && it.isNotBlank() },
                    address = row.getCell(7)?.toString()?.takeIf { it != "null" && it.isNotBlank() },
                    notes = row.getCell(8)?.toString()?.takeIf { it != "null" && it.isNotBlank() }
                ))
            }
            members.forEach { db.memberDao().insertMember(it) }
            return@withContext true
        } catch (e: Exception) { 
            Log.e("DriveSync", "downloadAndParseExcel failed", e)
            return@withContext false 
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
            val uploadedExcel = drive.files().create(com.google.api.services.drive.model.File().apply { name = "database.xlsx"; parents = listOf(folderId) }, FileContent("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excelFile)).setFields("id").execute()
            val config = Config(activeDatabaseId = uploadedExcel.id, roleHashes = mapOf("admin" to adminHash, "owner" to ownerHash), lastUpdated = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()))
            val configFile = File(context.cacheDir, "initial_config.json").apply { writeText(gson.toJson(config)) }
            val uploadedConfig = drive.files().create(com.google.api.services.drive.model.File().apply { name = "config.json"; parents = listOf(folderId) }, FileContent("application/json", configFile)).setFields("id").execute()
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
        } catch (e: Exception) { null }
    }

    suspend fun importLocalSheet(inputStream: java.io.InputStream) = withContext(Dispatchers.IO) {
        try {
            val workbook = WorkbookFactory.create(inputStream)
            val sheet = workbook.getSheetAt(0)
            val members = mutableListOf<Member>()
            for (i in 1..sheet.lastRowNum) {
                val row = sheet.getRow(i) ?: continue
                members.add(Member(memberId = row.getCell(0)?.toString()?.substringBefore(".") ?: UUID.randomUUID().toString().take(8), fullName = row.getCell(1)?.toString() ?: "Imported User", status = row.getCell(2)?.toString() ?: "Active", qrCodeHash = row.getCell(3)?.toString() ?: SecurityUtils.generateSecureQRHash(UUID.randomUUID().toString()), lastUpdated = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()), phone = row.getCell(5)?.toString(), email = row.getCell(6)?.toString(), address = row.getCell(7)?.toString(), notes = row.getCell(8)?.toString()))
            }
            members.forEach { db.memberDao().insertMember(it) }
        } catch (e: Exception) { }
    }

    suspend fun relocateFolder(oldConfigId: String, targetFolderId: String): String? = withContext(Dispatchers.IO) {
        try {
            val oldConfig = downloadConfig(oldConfigId) ?: return@withContext null
            val newDb = drive.files().copy(oldConfig.activeDatabaseId, com.google.api.services.drive.model.File().apply { name = "database.xlsx"; parents = listOf(targetFolderId) }).execute()
            var newLogoId: String? = null
            oldConfig.branding.logoFileId?.let { try { newLogoId = drive.files().copy(it, com.google.api.services.drive.model.File().apply { name = "logo.png"; parents = listOf(targetFolderId) }).execute().id } catch (e: Exception) { } }
            val newConfig = oldConfig.copy(activeDatabaseId = newDb.id, branding = oldConfig.branding.copy(logoFileId = newLogoId))
            val tempConfigFile = File(context.cacheDir, "new_config.json").apply { writeText(gson.toJson(newConfig)) }
            val uploadedNewConfigId = drive.files().create(com.google.api.services.drive.model.File().apply { name = "config.json"; parents = listOf(targetFolderId) }, FileContent("application/json", tempConfigFile)).setFields("id").execute().id
            try { drive.files().delete(oldConfigId).execute() } catch(e: Exception) {}
            try { drive.files().delete(oldConfig.activeDatabaseId).execute() } catch(e: Exception) {}
            oldConfig.branding.logoFileId?.let { try { drive.files().delete(it).execute() } catch(e: Exception) {} }
            return@withContext uploadedNewConfigId
        } catch (e: Exception) { null }
    }

    suspend fun getParentId(fileId: String): String? = withContext(Dispatchers.IO) {
        try {
            val file = drive.files().get(fileId).setFields("parents").execute()
            return@withContext file.parents?.firstOrNull()
        } catch (e: Exception) { null }
    }

    suspend fun uploadLogo(folderId: String, file: File, existingFileId: String? = null): String? = withContext(Dispatchers.IO) {
        try {
            val content = FileContent("image/png", file)
            if (existingFileId != null) { try { return@withContext drive.files().update(existingFileId, null, content).setFields("id").execute().id } catch (e: Exception) { } }
            return@withContext drive.files().create(com.google.api.services.drive.model.File().apply { name = "logo.png"; parents = listOf(folderId) }, content).setFields("id").execute().id
        } catch (e: Exception) { null }
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
