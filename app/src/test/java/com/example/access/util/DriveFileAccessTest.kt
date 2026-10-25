package com.example.access.util

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DriveFileAccessTest {
    @Test
    fun accessibleOrganizationDoesNotOpenPicker() = runBlocking {
        assertTrue(ensureDriveFileAccess(listOf("config", "database"), { true }) {
            error("Existing grants should be reused")
        })
    }

    @Test
    fun onlyMissingFilesAreRequestedAndCheckedAgain() = runBlocking {
        val allowed = mutableSetOf("config")
        val checked = mutableListOf<String>()
        val result = ensureDriveFileAccess(
            listOf("config", "database", "database"),
            canOpen = { checked.add(it); it in allowed },
            select = {
                assertEquals(listOf("database"), it)
                allowed.add("database")
                it
            }
        )
        assertTrue(result)
        assertEquals(listOf("config", "database", "database"), checked)
    }

    @Test
    fun cancellationDoesNotFinishConnecting() = runBlocking {
        assertFalse(ensureDriveFileAccess(listOf("config"), { false }) { null })
    }

    @Test
    fun choosingAnotherOrganizationDoesNotFinishConnecting() = runBlocking {
        assertFalse(ensureDriveFileAccess(listOf("expected-config"), { false }) { listOf("another-config") })
    }

    @Test
    fun partialGrantDoesNotFinishConnecting() = runBlocking {
        assertFalse(ensureDriveFileAccess(listOf("config", "database"), { false }) { listOf("config") })
    }

    @Test
    fun pickerResponseAloneIsInsufficientWithoutDriveAccess() = runBlocking {
        assertFalse(ensureDriveFileAccess(listOf("config"), { false }) { it })
    }

    @Test(expected = IOException::class)
    fun networkFailureDoesNotRequestMorePermissions() = runBlocking<Unit> {
        ensureDriveFileAccess(listOf("config"), { throw IOException("offline") }) {
            error("Network failures must not be treated as missing grants")
        }
    }

    @Test
    fun pickerIdsAreParsedWithoutEmptyOrDuplicateSelections() {
        assertEquals(listOf("config", "database"), parsePickedFileIds(" config, database,,config "))
        assertEquals(emptyList<String>(), parsePickedFileIds(null))
    }

    @Test
    fun authorizationErrorsRequestFileGrant() {
        assertTrue(needsDriveFileGrant(404, emptyList()))
        assertTrue(needsDriveFileGrant(403, listOf("appNotAuthorizedToFile")))
    }

    @Test
    fun quotaAndServiceErrorsDoNotOpenPicker() {
        assertFalse(needsDriveFileGrant(403, listOf("rateLimitExceeded")))
        assertFalse(needsDriveFileGrant(403, listOf("dailyLimitExceeded")))
        assertFalse(needsDriveFileGrant(503, emptyList()))
    }
}
