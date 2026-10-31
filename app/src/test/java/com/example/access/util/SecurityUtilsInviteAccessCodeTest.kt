package com.example.access.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityUtilsInviteAccessCodeTest {
    @Test
    fun generatedAccessCodeUsesSixteenCharacters() {
        val accessCode = SecurityUtils.generateInviteAccessCode()

        assertEquals(16, accessCode.length)
        assertTrue(SecurityUtils.isValidInviteAccessCode(accessCode))
    }

    @Test
    fun invitationDecryptsWithItsSixteenCharacterAccessCode() {
        val accessCode = "ABCD-EFGH-JKLM-NPQR"
        val invitation = SecurityUtils.encryptInvite(
            folderId = "config-file-id",
            accessCode = accessCode,
            invitedEmail = "member@example.com",
            invitedRole = "scanner"
        )

        val decrypted = SecurityUtils.decryptInvitePayload(invitation, accessCode)

        assertNotNull(decrypted)
        assertEquals("config-file-id", decrypted?.configId)
        assertEquals("member@example.com", decrypted?.invitedEmail)
    }

    @Test
    fun fourDigitPinCannotUnlockAnInvitation() {
        val invitation = SecurityUtils.encryptInvite(
            folderId = "config-file-id",
            accessCode = "ABCD-EFGH-JKLM-NPQR",
            invitedEmail = "member@example.com",
            invitedRole = "scanner"
        )

        assertFalse(SecurityUtils.isValidInviteAccessCode("1234"))
        assertNull(SecurityUtils.decryptInvitePayload(invitation, "1234"))
    }

    @Test
    fun legacyPinInvitationFormatIsRejected() {
        assertNull(SecurityUtils.decryptInvitePayload("v2.not-a-current-invitation", "1234"))
        assertNull(SecurityUtils.decryptInvitePayload("legacy-payload", "ABCDEFGHIJKLMNOP"))
    }
}
