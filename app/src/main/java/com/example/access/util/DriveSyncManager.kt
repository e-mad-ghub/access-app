package com.example.access.util

import android.content.Context
import android.util.Log
import com.example.access.data.AppDatabase
import com.example.access.data.AccessRosterEntry
import com.example.access.data.Config
import com.example.access.data.Member
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.services.drive.model.Permission
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
import java.io.IOException
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.*

const val FREE_TIER_MEMBER_LIMIT = 15
private const val PREFS_NAME = "easypass_prefs"
private const val FREE_LIMIT_TRIMMED_KEY = "free_limit_trimmed_database"

enum class ImportMode { ADD, OVERWRITE }

data class ImportResult(
    val members: List<Member>,
    val skippedRows: List<Int>,
    val duplicateRows: List<Int> = emptyList(),
    val originalValidMemberCount: Int = members.size,
    val limitExceededCount: Int = 0
) {
    val hasFreeLimitWarning: Boolean
        get() = limitExceededCount > 0
}

sealed interface FileDownloadResult {
    data class Success(val bytes: ByteArray) : FileDownloadResult
    data class HttpError(val statusCode: Int) : FileDownloadResult
    data class NetworkError(val message: String? = null) : FileDownloadResult
    data class UnexpectedError(val message: String? = null) : FileDownloadResult
}

sealed interface ConfigLoadResult {
    data class Success(val config: Config) : ConfigLoadResult
    data class DownloadFailure(val error: FileDownloadResult) : ConfigLoadResult
    data class ParseFailure(val message: String? = null) : ConfigLoadResult
}

sealed interface DatabaseLoadResult {
    data class Success(val result: ImportResult) : DatabaseLoadResult
    data class DownloadFailure(val error: FileDownloadResult) : DatabaseLoadResult
    data class ParseFailure(val message: String? = null) : DatabaseLoadResult
}

sealed interface RevokeOrganizationAccessResult {
    data class Success(
        val newConfigId: String,
        val newConfig: Config,
        val deletedConfigId: String,
        val deletedDatabaseId: String
    ) : RevokeOrganizationAccessResult

    data class CleanupPending(
        val newConfigId: String,
        val newConfig: Config,
        val oldConfigId: String,
        val oldDatabaseId: String
    ) : RevokeOrganizationAccessResult

    data class Failure(val message: String) : RevokeOrganizationAccessResult
}

data class OrganizationAccessCapabilities(
    val canManageAccess: Boolean,
    val restrictionMessage: String? = null
)

private data class PermissionMutation(
    val fileId: String,
    val permissionId: String,
    val previousRole: String?,
    val created: Boolean
)

class DriveSyncManager(
    private val context: Context,
    val drive: Drive
) {

    private val db = AppDatabase.getDatabase(context)
    private val gson = Gson()

    private fun downloadFileBytes(fileId: String): FileDownloadResult {
        return try {
            val outputStream = ByteArrayOutputStream()
            drive.files().get(fileId).setSupportsAllDrives(true).executeMediaAndDownloadTo(outputStream)
            FileDownloadResult.Success(outputStream.toByteArray())
        } catch (e: GoogleJsonResponseException) {
            Log.e("DriveSync", "Authenticated download failed for $fileId with ${e.statusCode}", e)
            FileDownloadResult.HttpError(e.statusCode)
        } catch (e: IOException) {
            Log.e("DriveSync", "Authenticated download network failure for $fileId", e)
            FileDownloadResult.NetworkError(e.message)
        } catch (e: Exception) {
            Log.e("DriveSync", "Authenticated download unexpected failure for $fileId", e)
            FileDownloadResult.UnexpectedError(e.message)
        }
    }

    private val EXCEL_COLUMNS = listOf("MemberID", "FullName", "Status", "QRCodeHash", "LastUpdated", "Phone", "Email", "Address", "Notes")
    private val IMPORT_TEMPLATE_COLUMNS = listOf("Name", "Phone", "Email", "Address", "Notes")
    private val CONFIG_FOLDER_NAME = "EasyPass-configs"

    private fun normalizeEmail(email: String?): String =
        email.orEmpty().trim().lowercase(Locale.US)

    private fun canonicalRole(role: String?): String = SessionManager.canonicalRole(role)

    private fun readerOrWriterForRole(role: String, writable: Boolean): String {
        if (!writable) return "reader"
        return when (canonicalRole(role)) {
            SessionManager.ROLE_MANAGER,
            SessionManager.ROLE_DIRECTOR -> "writer"
            else -> "reader"
        }
    }

    private fun canonicalRoleHashes(
        roleHashes: Map<String, String>,
        managerHashOverride: String? = null,
        directorHashOverride: String? = null
    ): Map<String, String> {
        val managerHash = managerHashOverride
            ?: roleHashes[SessionManager.ROLE_MANAGER]
            ?: roleHashes[SessionManager.LEGACY_ROLE_ADMIN]
        val directorHash = directorHashOverride
            ?: roleHashes[SessionManager.ROLE_DIRECTOR]
            ?: roleHashes[SessionManager.LEGACY_ROLE_OWNER]

        return buildMap {
            managerHash?.let { put(SessionManager.ROLE_MANAGER, it) }
            directorHash?.let { put(SessionManager.ROLE_DIRECTOR, it) }
        }
    }

    private suspend fun createFolder(parentId: String, name: String): String? {
        return try {
            val folder = com.google.api.services.drive.model.File().apply {
                this.name = name
                mimeType = "application/vnd.google-apps.folder"
                parents = listOf(parentId)
            }
            drive.files().create(folder)
                .setSupportsAllDrives(true)
                .setFields("id")
                .execute()
                .id
        } catch (e: Exception) {
            Log.e("DriveSync", "createFolder failed for parent $parentId / $name", e)
            null
        }
    }

    private suspend fun renameFile(fileId: String, newName: String): Boolean {
        return try {
            drive.files().update(
                fileId,
                com.google.api.services.drive.model.File().apply { name = newName }
            )
                .setSupportsAllDrives(true)
                .setFields("id,name")
                .execute()
            true
        } catch (e: Exception) {
            Log.e("DriveSync", "renameFile failed for $fileId to $newName", e)
            false
        }
    }

    private suspend fun resolveStorageFolderId(configId: String, config: Config): String? {
        return config.storageFolderId ?: getParentId(configId)
    }

    private suspend fun createEmailPermission(fileId: String, email: String, role: String): String? {
        return try {
            val permission = Permission().apply {
                type = "user"
                this.role = role
                emailAddress = email
            }
            drive.permissions().create(fileId, permission)
                .setSupportsAllDrives(true)
                .setSendNotificationEmail(false)
                .setFields("id")
                .execute()
                .id
        } catch (e: GoogleJsonResponseException) {
            Log.e("DriveSync", "createEmailPermission failed for $fileId / $email with ${e.statusCode}", e)
            null
        } catch (e: Exception) {
            Log.e("DriveSync", "createEmailPermission failed for $fileId / $email", e)
            null
        }
    }

    private suspend fun findPermissionForEmail(fileId: String, email: String): Permission? {
        val normalizedEmail = normalizeEmail(email)
        return try {
            drive.permissions().list(fileId)
                .setSupportsAllDrives(true)
                .setFields("permissions(id,emailAddress,role,type)")
                .execute()
                .permissions
                ?.firstOrNull { permission ->
                    permission.type == "user" && normalizeEmail(permission.emailAddress) == normalizedEmail
                }
        } catch (e: Exception) {
            Log.e("DriveSync", "findPermissionForEmail failed for $fileId / $normalizedEmail", e)
            null
        }
    }

    private suspend fun getPermission(fileId: String, permissionId: String): Permission? {
        return try {
            drive.permissions().get(fileId, permissionId)
                .setSupportsAllDrives(true)
                .setFields("id,emailAddress,role,type")
                .execute()
        } catch (e: GoogleJsonResponseException) {
            if (e.statusCode == 404 || e.statusCode == 410) {
                null
            } else {
                Log.e("DriveSync", "getPermission failed for $fileId / $permissionId with ${e.statusCode}", e)
                null
            }
        } catch (e: Exception) {
            Log.e("DriveSync", "getPermission failed for $fileId / $permissionId", e)
            null
        }
    }

    private suspend fun updatePermissionRole(fileId: String, permissionId: String, role: String): Boolean {
        return try {
            drive.permissions().update(fileId, permissionId, Permission().apply { this.role = role })
                .setSupportsAllDrives(true)
                .setFields("id,role")
                .execute()
            true
        } catch (e: Exception) {
            Log.e("DriveSync", "updatePermissionRole failed for $fileId / $permissionId", e)
            false
        }
    }

    private suspend fun deletePermission(fileId: String, permissionId: String?): Boolean {
        if (permissionId.isNullOrBlank()) return true
        return try {
            drive.permissions().delete(fileId, permissionId)
                .setSupportsAllDrives(true)
                .execute()
            true
        } catch (e: GoogleJsonResponseException) {
            if (e.statusCode == 404 || e.statusCode == 410) {
                true
            } else {
                Log.e("DriveSync", "deletePermission failed for $fileId / $permissionId with ${e.statusCode}", e)
                false
            }
        } catch (e: Exception) {
            Log.e("DriveSync", "deletePermission failed for $fileId / $permissionId", e)
            false
        }
    }

    private suspend fun deletePermissionForEmail(fileId: String, email: String): Boolean {
        val permission = findPermissionForEmail(fileId, email) ?: return true
        return deletePermission(fileId, permission.id)
    }

    private suspend fun rollbackPermissionMutations(mutations: List<PermissionMutation>) {
        mutations.asReversed().forEach { mutation ->
            if (mutation.created) {
                deletePermission(mutation.fileId, mutation.permissionId)
            } else if (mutation.previousRole != null) {
                updatePermissionRole(mutation.fileId, mutation.permissionId, mutation.previousRole)
            }
        }
    }

    private suspend fun upsertFilePermission(
        fileId: String,
        email: String,
        role: String,
        writable: Boolean,
        previousPermissionId: String? = null
    ): Pair<String, PermissionMutation?>? {
        val normalizedEmail = normalizeEmail(email)
        val targetRole = readerOrWriterForRole(role, writable)
        val existingPermission = previousPermissionId
            ?.let { getPermission(fileId, it) }
            ?.takeIf { normalizeEmail(it.emailAddress) == normalizedEmail }
            ?: findPermissionForEmail(fileId, normalizedEmail)

        if (existingPermission != null) {
            if (existingPermission.role == targetRole) {
                return existingPermission.id to null
            }
            if (!updatePermissionRole(fileId, existingPermission.id, targetRole)) {
                return null
            }
            return existingPermission.id to PermissionMutation(
                fileId = fileId,
                permissionId = existingPermission.id,
                previousRole = existingPermission.role,
                created = false
            )
        }

        val createdId = createEmailPermission(fileId, normalizedEmail, targetRole) ?: return null
        return createdId to PermissionMutation(
            fileId = fileId,
            permissionId = createdId,
            previousRole = null,
            created = true
        )
    }

    private suspend fun setWritersCanShareDisabled(fileId: String): Boolean {
        return try {
            drive.files().update(
                fileId,
                com.google.api.services.drive.model.File().apply {
                    writersCanShare = false
                }
            )
                .setSupportsAllDrives(true)
                .setFields("id,writersCanShare")
                .execute()
            true
        } catch (e: Exception) {
            Log.e("DriveSync", "setWritersCanShareDisabled failed for $fileId", e)
            false
        }
    }

    private suspend fun buildRosterEntryForFiles(
        configFileId: String,
        databaseFileId: String,
        logoFileId: String?,
        email: String,
        role: String,
        previousEntry: AccessRosterEntry? = null
    ): AccessRosterEntry? {
        val normalizedEmail = normalizeEmail(email)
        if (normalizedEmail.isBlank()) return null
        val createdAt = previousEntry?.createdAt ?: Instant.now().toString()
        val mutations = mutableListOf<PermissionMutation>()
        val databasePermission = upsertFilePermission(
            fileId = databaseFileId,
            email = normalizedEmail,
            role = role,
            writable = canonicalRole(role) in setOf(SessionManager.ROLE_MANAGER, SessionManager.ROLE_DIRECTOR),
            previousPermissionId = previousEntry?.databasePermissionId
        ) ?: return null
        databasePermission.second?.let(mutations::add)
        val configPermission = upsertFilePermission(
            fileId = configFileId,
            email = normalizedEmail,
            role = role,
            writable = canonicalRole(role) == SessionManager.ROLE_DIRECTOR,
            previousPermissionId = previousEntry?.configPermissionId
        ) ?: run {
            rollbackPermissionMutations(mutations)
            return null
        }
        configPermission.second?.let(mutations::add)
        val logoPermissionId = if (logoFileId != null) {
            val logoPermission = upsertFilePermission(
                fileId = logoFileId,
                email = normalizedEmail,
                role = role,
                writable = canonicalRole(role) == SessionManager.ROLE_DIRECTOR,
                previousPermissionId = previousEntry?.logoPermissionId
            )
            if (logoPermission == null) {
                rollbackPermissionMutations(mutations)
                return null
            }
            logoPermission.second?.let(mutations::add)
            logoPermission.first
        } else {
            null
        }
        return AccessRosterEntry(
            email = normalizedEmail,
            role = canonicalRole(role),
            isActive = true,
            createdAt = createdAt,
            configPermissionId = configPermission.first,
            databasePermissionId = databasePermission.first,
            logoPermissionId = logoPermissionId
        )
    }

    private fun normalizeForDuplicate(value: String?): String =
        value.orEmpty().trim().lowercase(Locale.US).replace(Regex("[^a-z0-9@+]"), "")

    private fun duplicateKeysFor(member: Member): List<String> {
        val keys = mutableListOf<String>()
        val email = normalizeForDuplicate(member.email)
        if (email.isNotBlank()) keys += "email:$email"
        val phone = normalizeForDuplicate(member.phone)
        if (phone.isNotBlank()) keys += "phone:$phone"
        return keys
    }

    private fun applyFreeLimit(result: ImportResult, isPro: Boolean): ImportResult {
        if (isPro || result.members.size <= FREE_TIER_MEMBER_LIMIT) return result
        return result.copy(
            members = result.members.take(FREE_TIER_MEMBER_LIMIT),
            originalValidMemberCount = result.members.size,
            limitExceededCount = result.members.size - FREE_TIER_MEMBER_LIMIT
        )
    }

    private fun usableMembersForPlan(members: List<Member>, isPro: Boolean): List<Member> =
        if (isPro) members else members.sortedBy { it.fullName.lowercase(Locale.US) }.take(FREE_TIER_MEMBER_LIMIT)

    private fun setFreeLimitTrimmed(trimmed: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(FREE_LIMIT_TRIMMED_KEY, trimmed)
            .apply()
    }

    private fun hasFreeLimitTrimmedDatabase(): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(FREE_LIMIT_TRIMMED_KEY, false)
    
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
                    if (date != null) {
                        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(date)
                    }
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

    suspend fun getDriveItemName(fileId: String): String = withContext(Dispatchers.IO) {
        try {
            val file = drive.files().get(fileId).setFields("name, trashed").setSupportsAllDrives(true).execute()
            if (file.trashed == true) return@withContext "Deleted"
            return@withContext file.name ?: "Unnamed"
        } catch (e: Exception) {
            Log.e("DriveSync", "getDriveItemName failed for $fileId", e)
            "Unknown"
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

    suspend fun getOrganizationAccessCapabilities(
        config: Config,
        configId: String,
        signedInEmail: String?
    ): OrganizationAccessCapabilities = withContext(Dispatchers.IO) {
        try {
            val normalizedSignedInEmail = normalizeEmail(signedInEmail)
            val normalizedOwnerEmail = normalizeEmail(config.storageOwnerEmail)
            if (normalizedSignedInEmail.isBlank() || normalizedSignedInEmail != normalizedOwnerEmail) {
                val ownerEmail = config.storageOwnerEmail?.takeIf { it.isNotBlank() } ?: "the storage owner"
                return@withContext OrganizationAccessCapabilities(
                    canManageAccess = false,
                    restrictionMessage = "Only the storage-owning Director can manage organization access for this storage. Ask $ownerEmail to manage invitations or permissions."
                )
            }
            val storageFolderId = config.storageFolderId ?: getParentId(configId)
            if (storageFolderId.isNullOrBlank()) {
                return@withContext OrganizationAccessCapabilities(
                    canManageAccess = false,
                    restrictionMessage = "easyPass could not verify the private storage folder for this organization."
                )
            }
            val ids = buildList {
                add(storageFolderId)
                add(configId)
                if (config.activeDatabaseId.isNotBlank()) add(config.activeDatabaseId)
                config.branding.logoFileId?.takeIf { it.isNotBlank() }?.let(::add)
            }
            val canManageAll = ids.all { fileId ->
                val file = drive.files().get(fileId)
                    .setFields("capabilities")
                    .setSupportsAllDrives(true)
                    .execute()
                when (fileId) {
                    storageFolderId -> file.capabilities?.canAddChildren == true && file.capabilities?.canDelete == true
                    else -> file.capabilities?.canShare == true && file.capabilities?.canDelete == true
                }
            }
            if (canManageAll) {
                OrganizationAccessCapabilities(canManageAccess = true)
            } else {
                val ownerEmail = config.storageOwnerEmail?.takeIf { it.isNotBlank() } ?: "the storage owner"
                OrganizationAccessCapabilities(
                    canManageAccess = false,
                    restrictionMessage = "This signed-in Google account cannot manage organization access for this storage. Ask $ownerEmail to manage invitations or permissions."
                )
            }
        } catch (e: Exception) {
            Log.e("DriveSync", "getOrganizationAccessCapabilities failed", e)
            OrganizationAccessCapabilities(
                canManageAccess = false,
                restrictionMessage = "easyPass could not verify Google Drive access-management permissions for this organization."
            )
        }
    }

    suspend fun inviteOrganizationUser(
        configId: String,
        currentConfig: Config,
        email: String,
        role: String
    ): Config? = withContext(Dispatchers.IO) {
        val normalizedEmail = normalizeEmail(email)
        if (normalizedEmail.isBlank()) return@withContext null

        val existingEntry = currentConfig.accessRoster.firstOrNull { normalizeEmail(it.email) == normalizedEmail }
        val newEntry = buildRosterEntryForFiles(
            configFileId = configId,
            databaseFileId = currentConfig.activeDatabaseId,
            logoFileId = currentConfig.branding.logoFileId,
            email = normalizedEmail,
            role = canonicalRole(role),
            previousEntry = existingEntry
        ) ?: return@withContext null

        val updatedRoster = currentConfig.accessRoster
            .filterNot { normalizeEmail(it.email) == normalizedEmail }
            .plus(newEntry)
            .sortedBy { it.email.lowercase(Locale.US) }
        val updatedConfig = currentConfig.copy(
            accessRoster = updatedRoster,
            lastUpdated = Instant.now().toString()
        )
        if (updateConfigOnDrive(configId, updatedConfig)) updatedConfig else null
    }

    suspend fun removeOrganizationUser(
        configId: String,
        currentConfig: Config,
        email: String
    ): Config? = withContext(Dispatchers.IO) {
        val normalizedEmail = normalizeEmail(email)
        if (normalizedEmail == normalizeEmail(currentConfig.storageOwnerEmail)) return@withContext null
        val entry = currentConfig.accessRoster.firstOrNull { normalizeEmail(it.email) == normalizedEmail }
            ?: return@withContext null

        val configRemoved = deletePermission(configId, entry.configPermissionId)
        val databaseRemoved = deletePermission(currentConfig.activeDatabaseId, entry.databasePermissionId)
        val logoRemoved = currentConfig.branding.logoFileId?.let { deletePermission(it, entry.logoPermissionId) } ?: true
        if (!configRemoved || !databaseRemoved || !logoRemoved) {
            return@withContext null
        }

        val updatedConfig = currentConfig.copy(
            accessRoster = currentConfig.accessRoster.filterNot { normalizeEmail(it.email) == normalizedEmail },
            lastUpdated = Instant.now().toString()
        )
        if (updateConfigOnDrive(configId, updatedConfig)) updatedConfig else null
    }

    suspend fun applyRosterToLogo(
        configId: String,
        currentConfig: Config,
        logoFileId: String?
    ): Config? = withContext(Dispatchers.IO) {
        logoFileId?.let {
            if (!setWritersCanShareDisabled(it)) return@withContext null
        }
        val updatedRoster = currentConfig.accessRoster.map { entry ->
            if (!entry.isActive || normalizeEmail(entry.email) == normalizeEmail(currentConfig.storageOwnerEmail)) {
                entry.copy(logoPermissionId = null)
            } else {
                val permissionId = logoFileId?.let {
                    upsertFilePermission(
                        fileId = it,
                        email = entry.email,
                        role = entry.role,
                        writable = canonicalRole(entry.role) == SessionManager.ROLE_DIRECTOR,
                        previousPermissionId = entry.logoPermissionId
                    )?.first
                }
                if (logoFileId != null && permissionId == null) return@withContext null
                entry.copy(logoPermissionId = permissionId)
            }
        }
        val updatedConfig = currentConfig.copy(
            branding = currentConfig.branding.copy(logoFileId = logoFileId),
            accessRoster = updatedRoster,
            lastUpdated = Instant.now().toString()
        )
        if (updateConfigOnDrive(configId, updatedConfig)) updatedConfig else null
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

    suspend fun downloadConfigResult(configId: String): ConfigLoadResult = withContext(Dispatchers.IO) {
        try {
            when (val bytes = downloadFileBytes(configId)) {
                is FileDownloadResult.Success -> {
                    try {
                        ConfigLoadResult.Success(gson.fromJson(String(bytes.bytes), Config::class.java))
                    } catch (e: Exception) {
                        Log.e("DriveSync", "Config parse failed for $configId", e)
                        ConfigLoadResult.ParseFailure(e.message)
                    }
                }
                else -> ConfigLoadResult.DownloadFailure(bytes)
            }
        } catch (e: Exception) { 
            Log.e("DriveSync", "downloadConfigResult failed for $configId", e)
            ConfigLoadResult.ParseFailure(e.message)
        }
    }

    suspend fun downloadConfig(configId: String): Config? {
        return when (val result = downloadConfigResult(configId)) {
            is ConfigLoadResult.Success -> result.config
            else -> null
        }
    }

    suspend fun updateConfigOnDrive(configId: String, config: Config): Boolean = withContext(Dispatchers.IO) {
        try {
            val configFile = File(context.cacheDir, "config.json").apply { writeText(gson.toJson(config)) }
            val content = FileContent("application/json", configFile)
            drive.files().update(configId, null, content)
                .setSupportsAllDrives(true)
                .execute()
            true
        } catch (e: Exception) { 
            Log.e("DriveSync", "updateConfigOnDrive failed", e)
            false
        }
    }

    private fun parseMembersWorkbook(inputStream: java.io.InputStream): ImportResult {
        return WorkbookFactory.create(inputStream).use { workbook ->
            val sheet = workbook.getSheetAt(0)
            val formatter = DataFormatter(Locale.US)
            val parsed = mutableListOf<Member>()
            val seenKeys = mutableSetOf<String>()
            val seenGeneratedIds = mutableSetOf<String>()
            val skippedRows = mutableListOf<Int>()
            val duplicateRows = mutableListOf<Int>()
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
                    if (seenGeneratedIds.add(candidate)) return candidate
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
                val rowNumber = i + 1

                if (fullName.isBlank() && phone.isBlank() && email.isBlank() && address.isBlank() && notes.isBlank()) continue
                if (fullName.isBlank()) {
                    skippedRows.add(rowNumber)
                    continue
                }

                val member = Member(
                    memberId = cell(memberIdIndex).ifBlank { uniqueMemberId() },
                    fullName = fullName,
                    status = cell(statusIndex).ifBlank { "Active" },
                    qrCodeHash = cell(qrTokenIndex).ifBlank { SecurityUtils.generateSecureQrToken() },
                    lastUpdated = cell(lastUpdatedIndex).takeIf(String::isNotBlank)?.let(::parseExcelDate) ?: Instant.now().toString(),
                    phone = phone.takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) },
                    email = email.takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) },
                    address = address.takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) },
                    notes = notes.takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
                )

                val duplicateKeys = duplicateKeysFor(member)
                if (duplicateKeys.any { it in seenKeys }) {
                    duplicateRows.add(rowNumber)
                    continue
                }
                seenKeys.addAll(duplicateKeys)

                parsed += member
            }

            ImportResult(
                members = parsed,
                skippedRows = skippedRows,
                duplicateRows = duplicateRows,
                originalValidMemberCount = parsed.size
            )
        }
    }

    suspend fun downloadAndParseExcelResult(fileId: String, isPro: Boolean = true): DatabaseLoadResult = withContext(Dispatchers.IO) {
        try {
            when (val bytes = downloadFileBytes(fileId)) {
                is FileDownloadResult.Success -> {
                    try {
                        val result = applyFreeLimit(parseMembersWorkbook(ByteArrayInputStream(bytes.bytes)), isPro)
                        setFreeLimitTrimmed(!isPro && result.hasFreeLimitWarning)

                        // Cloud Excel is authoritative. A transaction removes members no
                        // longer present, preventing revoked/deleted badges from staying valid.
                        db.memberDao().replaceAllMembers(result.members)
                        DatabaseLoadResult.Success(result)
                    } catch (e: Exception) {
                        Log.e("DriveSync", "Excel parse failed for $fileId", e)
                        DatabaseLoadResult.ParseFailure(e.message)
                    }
                }
                else -> DatabaseLoadResult.DownloadFailure(bytes)
            }
        } catch (e: Exception) {
            Log.e("DriveSync", "downloadAndParseExcelResult failed", e)
            DatabaseLoadResult.ParseFailure(e.message)
        }
    }

    suspend fun downloadAndParseExcel(fileId: String, isPro: Boolean = true): ImportResult? {
        return when (val result = downloadAndParseExcelResult(fileId, isPro)) {
            is DatabaseLoadResult.Success -> result.result
            else -> null
        }
    }

    suspend fun exportRoomToExcelAndUpload(fileId: String, isPro: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isPro && hasFreeLimitTrimmedDatabase()) {
                Log.w("DriveSync", "Skipping Drive upload because free plan loaded a trimmed over-limit database.")
                return@withContext false
            }
            val members = usableMembersForPlan(db.memberDao().getAllMembersList(), isPro)
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

    suspend fun createInitialFiles(folderId: String, managerHash: String, directorHash: String, ownerEmail: String): String? = withContext(Dispatchers.IO) {
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
            if (!setWritersCanShareDisabled(uploadedExcel.id)) return@withContext null
            val normalizedOwnerEmail = normalizeEmail(ownerEmail)
            val config = Config(
                activeDatabaseId = uploadedExcel.id,
                storageFolderId = configFolderId,
                roleHashes = canonicalRoleHashes(
                    roleHashes = emptyMap(),
                    managerHashOverride = managerHash,
                    directorHashOverride = directorHash
                ),
                accessRoster = listOf(
                    AccessRosterEntry(
                        email = normalizedOwnerEmail,
                        role = SessionManager.ROLE_DIRECTOR,
                        isActive = true,
                        createdAt = Instant.now().toString()
                    )
                ),
                storageOwnerEmail = normalizedOwnerEmail,
                tier = "pro",
                lastUpdated = Instant.now().toString()
            )
            val configFile = File(context.cacheDir, "initial_config.json").apply { writeText(gson.toJson(config)) }
            val uploadedConfig = drive.files().create(com.google.api.services.drive.model.File().apply { 
                name = "config.json"; parents = listOf(configFolderId) 
            }, FileContent("application/json", configFile))
                .setSupportsAllDrives(true)
                .setFields("id")
                .execute()
            if (!setWritersCanShareDisabled(uploadedConfig.id)) {
                permanentlyDeleteFile(uploadedConfig.id)
                permanentlyDeleteFile(uploadedExcel.id)
                return@withContext null
            }
            return@withContext uploadedConfig.id
        } catch (e: Exception) { 
            Log.e("DriveSync", "createInitialFiles failed", e)
            null 
        }
    }

    suspend fun exportLocalBackup(isPro: Boolean = true): File? = withContext(Dispatchers.IO) {
        try {
            val members = usableMembersForPlan(db.memberDao().getAllMembersList(), isPro)
            val workbook = XSSFWorkbook()
            val sheet = workbook.createSheet("MemberDirectory")
            val header = sheet.createRow(0)
            EXCEL_COLUMNS.forEachIndexed { i, t -> header.createCell(i).setCellValue(t) }
            members.forEachIndexed { i, m ->
                val row = sheet.createRow(i + 1)
                row.createCell(0).setCellValue(m.memberId); row.createCell(1).setCellValue(m.fullName); row.createCell(2).setCellValue(m.status); row.createCell(3).setCellValue(m.qrCodeHash); row.createCell(4).setCellValue(m.lastUpdated); row.createCell(5).setCellValue(m.phone ?: ""); row.createCell(6).setCellValue(m.email ?: ""); row.createCell(7).setCellValue(m.address ?: ""); row.createCell(8).setCellValue(m.notes ?: "")
            }
            val file = File(context.cacheDir, "easyPass_Backup.xlsx")
            FileOutputStream(file).use { workbook.write(it) }
            return@withContext file
        } catch (e: Exception) { 
            Log.e("DriveSync", "exportLocalBackup failed", e)
            null 
        }
    }

    suspend fun exportImportTemplate(): File? = withContext(Dispatchers.IO) {
        try {
            val workbook = XSSFWorkbook()
            val sheet = workbook.createSheet("easyPass Import Template")
            val header = sheet.createRow(0)
            IMPORT_TEMPLATE_COLUMNS.forEachIndexed { i, title -> header.createCell(i).setCellValue(title) }
            val file = File(context.cacheDir, "easyPass_Import_Template.xlsx")
            FileOutputStream(file).use { workbook.write(it) }
            return@withContext file
        } catch (e: Exception) {
            Log.e("DriveSync", "exportImportTemplate failed", e)
            null
        }
    }

    suspend fun previewLocalSheet(inputStream: java.io.InputStream): ImportResult? = withContext(Dispatchers.IO) {
        try {
            parseMembersWorkbook(inputStream)
        } catch (e: Exception) {
            Log.e("DriveSync", "previewLocalSheet failed", e)
            null
        }
    }

    suspend fun applyImportResult(result: ImportResult, mode: ImportMode, isPro: Boolean = true): ImportResult = withContext(Dispatchers.IO) {
        val existingMembers = db.memberDao().getAllMembersList()
        val duplicateRows = result.duplicateRows.toMutableList()
        val membersToApply = when (mode) {
            ImportMode.OVERWRITE -> result.members
            ImportMode.ADD -> {
                val existingKeys = existingMembers.flatMap(::duplicateKeysFor).toMutableSet()
                result.members.filterIndexed { index, member ->
                    val keys = duplicateKeysFor(member)
                    if (keys.any { it in existingKeys }) {
                        duplicateRows.add(index + 2)
                        false
                    } else {
                        existingKeys.addAll(keys)
                        true
                    }
                }
            }
        }
        val finalMembers = if (mode == ImportMode.ADD) existingMembers + membersToApply else membersToApply
        require(isPro || finalMembers.size <= FREE_TIER_MEMBER_LIMIT) {
            "Free plan limit exceeded"
        }
        db.memberDao().replaceAllMembers(finalMembers)
        if (!isPro && mode == ImportMode.OVERWRITE) {
            setFreeLimitTrimmed(false)
        }
        result.copy(
            members = membersToApply,
            duplicateRows = duplicateRows.distinct().sorted(),
            originalValidMemberCount = membersToApply.size
        )
    }

    suspend fun relocateFolder(oldConfigId: String, targetFolderId: String): String? = withContext(Dispatchers.IO) {
        try {
            val oldConfig = downloadConfig(oldConfigId) ?: return@withContext null
            val oldStorageFolderId = resolveStorageFolderId(oldConfigId, oldConfig)
            val configFolderId = createFolder(targetFolderId, CONFIG_FOLDER_NAME) ?: return@withContext null

            val newDb = drive.files().copy(oldConfig.activeDatabaseId, com.google.api.services.drive.model.File().apply { 
                name = "database.xlsx"; parents = listOf(configFolderId) 
            }).setSupportsAllDrives(true).setFields("id").execute()
            if (!setWritersCanShareDisabled(newDb.id)) {
                cleanupReplacementArtifacts(null, newDb.id, null, configFolderId)
                return@withContext null
            }
            var newLogoId: String? = null
            oldConfig.branding.logoFileId?.let { 
                try { 
                    newLogoId = drive.files().copy(it, com.google.api.services.drive.model.File().apply { 
                        name = "logo.png"; parents = listOf(configFolderId) 
                    }).setSupportsAllDrives(true).setFields("id").execute().id
                    if (newLogoId != null && !setWritersCanShareDisabled(newLogoId!!)) {
                        throw IllegalStateException("Could not secure replacement logo permissions.")
                    }
                } catch (e: Exception) { 
                    Log.e("DriveSync", "Failed to copy logo file $it to new folder", e)
                    cleanupReplacementArtifacts(null, newDb.id, newLogoId, configFolderId)
                    return@withContext null
                } 
            }
            var newConfig = oldConfig.copy(
                activeDatabaseId = newDb.id,
                storageFolderId = configFolderId,
                roleHashes = canonicalRoleHashes(oldConfig.roleHashes),
                branding = oldConfig.branding.copy(logoFileId = newLogoId)
            )
            val tempConfigFile = File(context.cacheDir, "new_config.json").apply { writeText(gson.toJson(newConfig)) }
            val uploadedNewConfigId = drive.files().create(com.google.api.services.drive.model.File().apply { 
                name = "config.json"; parents = listOf(configFolderId) 
            }, FileContent("application/json", tempConfigFile))
                .setSupportsAllDrives(true)
                .setFields("id")
                .execute().id
            if (!setWritersCanShareDisabled(uploadedNewConfigId)) {
                cleanupReplacementArtifacts(uploadedNewConfigId, newDb.id, newLogoId, configFolderId)
                return@withContext null
            }
            val ownerEmail = normalizeEmail(oldConfig.storageOwnerEmail)
            val refreshedRoster = oldConfig.accessRoster.mapNotNull { entry ->
                if (normalizeEmail(entry.email) == ownerEmail) {
                    entry.copy(
                        role = canonicalRole(entry.role),
                        configPermissionId = null,
                        databasePermissionId = null,
                        logoPermissionId = null
                    )
                } else {
                    buildRosterEntryForFiles(
                        configFileId = uploadedNewConfigId,
                        databaseFileId = newDb.id,
                        logoFileId = newLogoId,
                        email = entry.email,
                        role = entry.role,
                        previousEntry = null
                    )
                }
            }
            newConfig = newConfig.copy(accessRoster = refreshedRoster)
            if (!updateConfigOnDrive(uploadedNewConfigId, newConfig)) {
                cleanupReplacementArtifacts(uploadedNewConfigId, newDb.id, newLogoId, configFolderId)
                return@withContext null
            }
            if (!permanentlyDeleteFile(oldConfigId) || !permanentlyDeleteFile(oldConfig.activeDatabaseId)) {
                return@withContext null
            }
            oldConfig.branding.logoFileId?.let { if (!permanentlyDeleteFile(it)) return@withContext null }
            oldStorageFolderId?.let { permanentlyDeleteFile(it) }
            return@withContext uploadedNewConfigId
        } catch (e: Exception) { 
            Log.e("DriveSync", "relocateFolder failed for config $oldConfigId to folder $targetFolderId", e)
            null 
        }
    }

    suspend fun getParentId(fileId: String): String? = withContext(Dispatchers.IO) {
        try {
            val file = drive.files().get(fileId).setFields("parents").setSupportsAllDrives(true).execute()
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
                    val updatedId = drive.files().update(existingFileId, null, content)
                        .setSupportsAllDrives(true)
                        .setFields("id")
                        .execute()
                        .id
                    if (!setWritersCanShareDisabled(updatedId)) return@withContext null
                    return@withContext updatedId
                } catch (e: Exception) { 
                    Log.e("DriveSync", "Failed to update existing logo file $existingFileId", e)
                } 
            }
            val createdId = drive.files().create(
                com.google.api.services.drive.model.File().apply { name = "logo.png"; parents = listOf(folderId) },
                content
            )
                .setSupportsAllDrives(true)
                .setFields("id")
                .execute()
                .id
            if (!setWritersCanShareDisabled(createdId)) {
                permanentlyDeleteFile(createdId)
                return@withContext null
            }
            return@withContext createdId
        } catch (e: Exception) { 
            Log.e("DriveSync", "uploadLogo failed for folder $folderId", e)
            null 
        }
    }

    suspend fun validateRevocationCapabilities(configId: String, databaseId: String, storageFolderId: String?): Boolean = withContext(Dispatchers.IO) {
        try {
            val effectiveFolderId = storageFolderId ?: getParentId(configId) ?: return@withContext false
            val folder = drive.files().get(effectiveFolderId)
                .setFields("capabilities")
                .setSupportsAllDrives(true)
                .execute()
            val configFile = drive.files().get(configId)
                .setFields("capabilities")
                .setSupportsAllDrives(true)
                .execute()
            val dbFile = drive.files().get(databaseId)
                .setFields("capabilities")
                .setSupportsAllDrives(true)
                .execute()
            (folder.capabilities?.canAddChildren == true) &&
                (folder.capabilities?.canDelete == true) &&
                (configFile.capabilities?.canDelete == true) &&
                (dbFile.capabilities?.canDelete == true)
        } catch (e: Exception) {
            Log.e("DriveSync", "validateRevocationCapabilities failed", e)
            false
        }
    }

    suspend fun permanentlyDeleteFile(fileId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            drive.files().delete(fileId).setSupportsAllDrives(true).execute()
            true
        } catch (e: GoogleJsonResponseException) {
            if (e.statusCode == 404 || e.statusCode == 410) {
                true
            } else {
                Log.e("DriveSync", "permanentlyDeleteFile failed for $fileId with ${e.statusCode}", e)
                false
            }
        } catch (e: Exception) {
            Log.e("DriveSync", "permanentlyDeleteFile failed for $fileId", e)
            false
        }
    }

    suspend fun revokeAllUsersAccess(
        oldConfigId: String,
        currentConfig: Config,
        newManagerHash: String,
        newDirectorHash: String,
        initiatingDirectorEmail: String
    ): RevokeOrganizationAccessResult = withContext(Dispatchers.IO) {
        val oldDatabaseId = currentConfig.activeDatabaseId
        if (oldDatabaseId.isBlank()) {
            return@withContext RevokeOrganizationAccessResult.Failure("Current organization database is missing.")
        }

        val oldStorageFolderId = resolveStorageFolderId(oldConfigId, currentConfig)
            ?: return@withContext RevokeOrganizationAccessResult.Failure("Could not locate the current easyPass storage folder.")

        if (!validateRevocationCapabilities(oldConfigId, oldDatabaseId, oldStorageFolderId)) {
            return@withContext RevokeOrganizationAccessResult.Failure("Google Drive permissions are not sufficient to replace and delete the current organization files.")
        }

        val parentId = getParentId(oldStorageFolderId)
            ?: return@withContext RevokeOrganizationAccessResult.Failure("Could not locate the current easyPass storage folder.")

        var newConfigId: String? = null
        var newDatabaseId: String? = null
        var newLogoId: String? = null
        var newStorageFolderId: String? = null

        try {
            newStorageFolderId = createFolder(parentId, "$CONFIG_FOLDER_NAME-${System.currentTimeMillis()}")
                ?: return@withContext RevokeOrganizationAccessResult.Failure("easyPass could not create a replacement storage folder.")

            val copiedDatabase = drive.files().copy(
                oldDatabaseId,
                com.google.api.services.drive.model.File().apply {
                    name = "database.xlsx"
                    parents = listOf(newStorageFolderId)
                }
            )
                .setSupportsAllDrives(true)
                .setFields("id")
                .execute()
            newDatabaseId = copiedDatabase.id
            if (!setWritersCanShareDisabled(copiedDatabase.id)) {
                cleanupReplacementArtifacts(newConfigId, newDatabaseId, null, newStorageFolderId)
                return@withContext RevokeOrganizationAccessResult.Failure("easyPass could not secure the replacement organization database.")
            }

            currentConfig.accessRoster
                .filter { normalizeEmail(it.email) != normalizeEmail(initiatingDirectorEmail) }
                .forEach { entry ->
                    deletePermissionForEmail(copiedDatabase.id, entry.email)
                }

            currentConfig.branding.logoFileId?.let { logoId ->
                if (!moveFileToFolder(logoId, newStorageFolderId)) {
                    cleanupReplacementArtifacts(newConfigId, newDatabaseId, null, newStorageFolderId)
                    return@withContext RevokeOrganizationAccessResult.Failure("easyPass could not move the organization logo into the replacement storage folder.")
                }
                newLogoId = logoId
                if (!setWritersCanShareDisabled(logoId)) {
                    cleanupReplacementArtifacts(newConfigId, newDatabaseId, null, newStorageFolderId)
                    return@withContext RevokeOrganizationAccessResult.Failure("easyPass could not secure the replacement organization logo.")
                }
                currentConfig.accessRoster
                    .filter { normalizeEmail(it.email) != normalizeEmail(initiatingDirectorEmail) }
                    .forEach { entry ->
                        deletePermissionForEmail(logoId, entry.email)
                    }
            }

            val updatedConfig = currentConfig.copy(
                activeDatabaseId = copiedDatabase.id,
                storageFolderId = newStorageFolderId,
                roleHashes = canonicalRoleHashes(
                    currentConfig.roleHashes,
                    managerHashOverride = newManagerHash,
                    directorHashOverride = newDirectorHash
                ),
                branding = currentConfig.branding.copy(logoFileId = newLogoId),
                accessRoster = listOf(
                    AccessRosterEntry(
                        email = normalizeEmail(initiatingDirectorEmail),
                        role = SessionManager.ROLE_DIRECTOR,
                        isActive = true,
                        createdAt = Instant.now().toString()
                    )
                ),
                storageOwnerEmail = normalizeEmail(initiatingDirectorEmail),
                lastUpdated = Instant.now().toString()
            )

            val configFile = File(context.cacheDir, "revoked_config.json").apply {
                writeText(gson.toJson(updatedConfig))
            }

            val uploadedConfig = drive.files().create(
                com.google.api.services.drive.model.File().apply {
                    name = "config.json"
                    parents = listOf(newStorageFolderId)
                },
                FileContent("application/json", configFile)
            )
                .setSupportsAllDrives(true)
                .setFields("id")
                .execute()
            newConfigId = uploadedConfig.id
            if (!setWritersCanShareDisabled(uploadedConfig.id)) {
                cleanupReplacementArtifacts(newConfigId, newDatabaseId, null, newStorageFolderId)
                return@withContext RevokeOrganizationAccessResult.Failure("easyPass could not secure the replacement organization connection.")
            }

            val verifiedConfig = when (val configResult = downloadConfigResult(uploadedConfig.id)) {
                is ConfigLoadResult.Success -> configResult.config
                else -> {
                    cleanupReplacementArtifacts(newConfigId, newDatabaseId, null, newStorageFolderId)
                    return@withContext RevokeOrganizationAccessResult.Failure("easyPass could not verify the replacement organization connection.")
                }
            }

            when (downloadFileBytes(verifiedConfig.activeDatabaseId)) {
                is FileDownloadResult.Success -> Unit
                else -> {
                    cleanupReplacementArtifacts(newConfigId, newDatabaseId, null, newStorageFolderId)
                    return@withContext RevokeOrganizationAccessResult.Failure("easyPass could not verify the replacement organization database.")
                }
            }

            val configDeleted = permanentlyDeleteFile(oldConfigId)
            val databaseDeleted = permanentlyDeleteFile(oldDatabaseId)
            val oldFolderDeleted = permanentlyDeleteFile(oldStorageFolderId)
            if (configDeleted && databaseDeleted && oldFolderDeleted) {
                renameFile(newStorageFolderId, CONFIG_FOLDER_NAME)
            }

            if (configDeleted && databaseDeleted && oldFolderDeleted) {
                RevokeOrganizationAccessResult.Success(
                    newConfigId = uploadedConfig.id,
                    newConfig = verifiedConfig,
                    deletedConfigId = oldConfigId,
                    deletedDatabaseId = oldDatabaseId
                )
            } else {
                RevokeOrganizationAccessResult.CleanupPending(
                    newConfigId = uploadedConfig.id,
                    newConfig = verifiedConfig,
                    oldConfigId = oldConfigId,
                    oldDatabaseId = oldDatabaseId
                )
            }
        } catch (e: Exception) {
            Log.e("DriveSync", "revokeAllUsersAccess failed", e)
            cleanupReplacementArtifacts(newConfigId, newDatabaseId, null, newStorageFolderId)
            RevokeOrganizationAccessResult.Failure("easyPass could not complete the organization access reset.")
        }
    }

    suspend fun revokeAllUsersPermission(
        oldConfigId: String,
        currentConfig: Config,
        newManagerHash: String,
        newDirectorHash: String,
        initiatingDirectorEmail: String
    ): RevokeOrganizationAccessResult = revokeAllUsersAccess(
        oldConfigId = oldConfigId,
        currentConfig = currentConfig,
        newManagerHash = newManagerHash,
        newDirectorHash = newDirectorHash,
        initiatingDirectorEmail = initiatingDirectorEmail
    )

    private suspend fun cleanupReplacementArtifacts(
        newConfigId: String?,
        newDatabaseId: String?,
        newLogoId: String? = null,
        newStorageFolderId: String? = null
    ) {
        newConfigId?.let { permanentlyDeleteFile(it) }
        newDatabaseId?.let { permanentlyDeleteFile(it) }
        newLogoId?.let { permanentlyDeleteFile(it) }
        newStorageFolderId?.let { permanentlyDeleteFile(it) }
    }

    companion object {
        fun createWithCredential(context: Context, credential: GoogleAccountCredential): DriveSyncManager {
            val drive = Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential).setApplicationName("easyPass").build()
            return DriveSyncManager(context, drive)
        }
    }
}
