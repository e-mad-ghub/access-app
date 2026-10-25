package com.example.access.util

import android.accounts.Account
import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Register as an Activity property, before onStart. The app does not persist OAuth tokens. */
class DriveFilePicker(private val activity: ComponentActivity) {
    // Activity properties are constructed before Android attaches their base Context.
    private val client by lazy { Identity.getAuthorizationClient(activity) }
    private var pendingResult: CancellableContinuation<AuthorizationResult?>? = null
    private var returningFromAuthorization = false

    fun consumeActivityReturn(): Boolean = returningFromAuthorization.also { returningFromAuthorization = false }
    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val continuation = pendingResult ?: return@registerForActivityResult
        pendingResult = null
        if (!continuation.isActive) return@registerForActivityResult
        if (result.resultCode != Activity.RESULT_OK) {
            continuation.resume(null)
        } else {
            try {
                continuation.resume(client.getAuthorizationResultFromIntent(result.data))
            } catch (error: Exception) {
                continuation.resumeWithException(error)
            }
        }
    }

    suspend fun authorize(account: Account): Boolean = request(account) != null

    suspend fun select(
        account: Account,
        fileIds: List<String> = emptyList(),
        folders: Boolean = false,
        mimeTypes: String? = null
    ): List<String>? {
        val result = request(account, pick = true, fileIds, folders, mimeTypes) ?: return null
        return parsePickedFileIds(result.tokenResponseParams?.getString("picked_file_ids"))
            .takeIf { it.isNotEmpty() }
    }

    suspend fun selectFolder(account: Account, folderId: String): String? =
        select(account, fileIds = listOf(folderId), folders = true)?.singleOrNull()

    suspend fun ensureFolderAccess(drive: Drive, account: Account, folderId: String): Boolean =
        ensureDriveFileAccess(
            listOf(folderId),
            canOpen = { withContext(Dispatchers.IO) { canOpen(drive, it) } },
            select = { selectFolder(account, folderId)?.let(::listOf) }
        )

    /** A Drive sharing permission and authorization for this app are separate grants. */
    suspend fun ensureAccess(drive: Drive, account: Account, fileIds: List<String>): Boolean {
        return ensureDriveFileAccess(
            fileIds,
            canOpen = { withContext(Dispatchers.IO) { canOpen(drive, it) } },
            select = { select(account, it) }
        )
    }

    private fun canOpen(drive: Drive, fileId: String): Boolean {
        return try {
            drive.files().get(fileId).setSupportsAllDrives(true).setFields("id").execute()
            true
        } catch (error: GoogleJsonResponseException) {
            if (needsDriveFileGrant(error.statusCode, error.details?.errors.orEmpty().mapNotNull { it.reason })) {
                false
            } else {
                throw error
            }
        }
    }

    private suspend fun request(
        account: Account,
        pick: Boolean = false,
        fileIds: List<String> = emptyList(),
        folders: Boolean = false,
        mimeTypes: String? = null
    ): AuthorizationResult? = suspendCancellableCoroutine { continuation ->
        check(pendingResult == null) { "A Drive authorization request is already open." }
        pendingResult = continuation
        continuation.invokeOnCancellation {
            if (pendingResult === continuation) pendingResult = null
        }
        val builder = AuthorizationRequest.builder()
            .setAccount(account)
            .setRequestedScopes(listOf(Scope(DriveScopes.DRIVE_FILE)))
            .setOptOutIncludingGrantedScopes(true)
        if (pick) {
            builder.setPrompt(AuthorizationRequest.Prompt.CONSENT)
            for ((parameter, value) in drivePickerParameters(fileIds, folders, mimeTypes)) {
                builder.addResourceParameter(parameter, value)
            }
        }
        client.authorize(builder.build())
            .addOnSuccessListener { result ->
                if (!continuation.isActive) return@addOnSuccessListener
                if (result.hasResolution()) {
                    try {
                        returningFromAuthorization = true
                        launcher.launch(IntentSenderRequest.Builder(result.pendingIntent!!.intentSender).build())
                    } catch (error: Exception) {
                        pendingResult = null
                        continuation.resumeWithException(error)
                    }
                } else {
                    pendingResult = null
                    continuation.resume(result)
                }
            }
            .addOnFailureListener { error ->
                if (continuation.isActive) {
                    pendingResult = null
                    continuation.resumeWithException(error)
                }
            }
    }
}

/** A destination folder is selected from its parent, never opened as a file-ID view. */
internal fun drivePickerParameters(
    fileIds: List<String>,
    folders: Boolean,
    mimeTypes: String? = null
): Map<AuthorizationRequest.ResourceParameter, String> = buildMap {
    put(AuthorizationRequest.ResourceParameter.PICKER_OAUTH_TRIGGER, "true")
    put(AuthorizationRequest.ResourceParameter.PICKER_ALLOW_MULTIPLE, (!folders).toString())
    put(AuthorizationRequest.ResourceParameter.PICKER_ALLOW_FOLDER_SELECTION, folders.toString())
    if (fileIds.isNotEmpty()) {
        put(AuthorizationRequest.ResourceParameter.PICKER_FILE_IDS, fileIds.joinToString(","))
    }
    if (folders) {
        // Restrict the view to folders; the prefilled ID takes the user to the
        // linked destination. They can navigate up one level to select that folder.
        put(AuthorizationRequest.ResourceParameter.PICKER_MIMETYPES, "application/vnd.google-apps.folder")
    } else {
        if (mimeTypes != null) put(AuthorizationRequest.ResourceParameter.PICKER_MIMETYPES, mimeTypes)
    }
}

internal fun parsePickedFileIds(value: String?): List<String> =
    value.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).distinct()

internal fun needsDriveFileGrant(status: Int, reasons: List<String>): Boolean =
    status == 404 || (status == 403 && reasons.any {
        it in setOf("appNotAuthorizedToFile", "insufficientFilePermissions", "insufficientPermissions")
    })

/** Check specific IDs, request only missing grants, then verify access before any sync writes. */
internal suspend fun ensureDriveFileAccess(
    fileIds: List<String>,
    canOpen: suspend (String) -> Boolean,
    select: suspend (List<String>) -> List<String>?
): Boolean {
    val missing = fileIds.filter(String::isNotBlank).distinct().filterNot { canOpen(it) }
    if (missing.isEmpty()) return true
    val selected = select(missing) ?: return false
    if (!selected.containsAll(missing)) return false
    return missing.all { canOpen(it) }
}
