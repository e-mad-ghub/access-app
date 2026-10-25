package com.example.access

import com.example.access.util.FileDownloadResult
import org.junit.Assert.*
import org.junit.Test

class DriveConnectionFailureTest {
    private fun state(error: FileDownloadResult, configFile: Boolean = true) =
        mapDownloadFailureToUiState(error, "Unavailable", "Try again", configFile)

    @Test
    fun unauthorizedOrInvisibleFileNeverTriggersConnectionRemoval() {
        for (status in listOf(401, 403, 404)) {
            assertTrue(state(FileDownloadResult.HttpError(status)) is ConnectionUiState.Unavailable)
        }
    }

    @Test
    fun networkAndServiceFailuresNeverTriggerConnectionRemoval() {
        assertTrue(state(FileDownloadResult.NetworkError()) is ConnectionUiState.Unavailable)
        assertTrue(state(FileDownloadResult.HttpError(503)) is ConnectionUiState.Unavailable)
    }

    @Test
    fun confirmedGoneConfigKeepsExistingRemovalBehavior() {
        assertTrue(state(FileDownloadResult.HttpError(410)) is ConnectionUiState.ConnectionRemoved)
    }

    @Test
    fun goneDatabaseDoesNotDiscardConfigConnection() {
        assertTrue(state(FileDownloadResult.HttpError(410), configFile = false) is ConnectionUiState.Unavailable)
    }
}
