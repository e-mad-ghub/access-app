package com.example.access.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.gson.Gson
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object SecurityUtils {
    private val gson = Gson()

    private const val PASSWORD_SCHEME = "pbkdf2-sha256"
    private const val PASSWORD_ITERATIONS = 120_000
    private const val PASSWORD_KEY_BITS = 256
    private const val PASSWORD_SALT_BYTES = 16

    private const val INVITE_VERSION = "v4"
    private const val PREVIOUS_ACCESS_CODE_INVITE_VERSION = "v3"
    private const val LEGACY_GCM_INVITE_VERSION = "v2"
    private const val INVITE_ITERATIONS = 150_000
    private const val INVITE_SALT_BYTES = 16
    private const val INVITE_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    private const val LEGACY_INVITE_ALGORITHM = "AES/CBC/PKCS5Padding"
    private const val INVITE_ACCESS_CODE_LENGTH = 16
    private const val INVITE_ACCESS_CODE_GROUP_SIZE = 4
    private const val INVITE_ACCESS_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    private val secureRandom = SecureRandom()
    private val urlEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val urlDecoder: Base64.Decoder = Base64.getUrlDecoder()
    private val legacyInvitePinRegex = Regex("\\d{4}")
    private val inviteAccessCodeRegex = Regex("[A-Z2-9]{$INVITE_ACCESS_CODE_LENGTH}")

    enum class InviteCredentialType {
        PIN,
        ACCESS_CODE
    }

    data class InvitePayload(
        val configId: String = "",
        val invitedEmail: String? = null,
        val invitedRole: String? = null
    )

    private data class EncryptedInvitePayload(
        val configId: String,
        val invitedEmail: String,
        val invitedRole: String,
        val issuedAt: String
    )

    /**
     * Creates a self-contained, salted password verifier:
     * pbkdf2-sha256$iterations$salt$derivedKey
     *
     * A fresh random salt is generated for every password, so equal passwords do
     * not produce equal values and precomputed/rainbow-table attacks are defeated.
     */
    fun hashPassword(password: String): String {
        require(password.isNotEmpty()) { "Password must not be empty" }
        val salt = ByteArray(PASSWORD_SALT_BYTES).also(secureRandom::nextBytes)
        val hash = deriveKey(password, salt, PASSWORD_ITERATIONS, PASSWORD_KEY_BITS)
        return listOf(
            PASSWORD_SCHEME,
            PASSWORD_ITERATIONS.toString(),
            urlEncoder.encodeToString(salt),
            urlEncoder.encodeToString(hash)
        ).joinToString("$")
    }

    /**
     * Verifies both production PBKDF2 hashes and legacy unsalted SHA-256 hashes.
     * Legacy support prevents existing organizations from being locked out; any
     * key updated through the owner screen is automatically upgraded to PBKDF2.
     */
    fun verifyPassword(password: String, storedHash: String?): Boolean {
        if (storedHash.isNullOrBlank()) return false

        if (storedHash.startsWith("$PASSWORD_SCHEME$")) {
            return try {
                val parts = storedHash.split('$')
                if (parts.size != 4) return false
                val iterations = parts[1].toInt()
                if (iterations < 10_000 || iterations > 2_000_000) return false
                val salt = urlDecoder.decode(parts[2])
                val expected = urlDecoder.decode(parts[3])
                val actual = deriveKey(password, salt, iterations, expected.size * 8)
                MessageDigest.isEqual(expected, actual)
            } catch (_: Exception) {
                false
            }
        }

        // Backward compatibility for organizations created before PBKDF2 support.
        val legacyActual = legacySha256(password)
        return MessageDigest.isEqual(
            storedHash.lowercase().toByteArray(StandardCharsets.US_ASCII),
            legacyActual.toByteArray(StandardCharsets.US_ASCII)
        )
    }

    /**
     * QR credentials are opaque random 256-bit tokens. They are intentionally not
     * derived from a member ID, so knowing an ID or reverse-engineering the app
     * cannot be used to forge a valid pass.
     */
    fun generateSecureQrToken(): String {
        val token = ByteArray(32).also(secureRandom::nextBytes)
        return urlEncoder.encodeToString(token)
    }

    /**
     * Kept as a source-compatible bridge for old call sites. The member ID is
     * deliberately ignored; newly generated credentials must be random.
     */
    @Deprecated(
        message = "Use generateSecureQrToken(); QR credentials must not be derived from member IDs.",
        replaceWith = ReplaceWith("generateSecureQrToken()")
    )
    fun generateSecureQRHash(@Suppress("UNUSED_PARAMETER") memberId: String): String =
        generateSecureQrToken()

    fun generateQRCode(content: String, size: Int): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Encrypts an invite using an access-code-derived key and authenticated AES-GCM.
     * Payload format: v3.<base64url(salt || iv || ciphertext+tag)>.
     */
    fun encryptInvite(folderId: String, accessCode: String): String {
        return encryptInvite(folderId, accessCode, invitedEmail = "", invitedRole = "")
    }

    fun encryptInvite(
        folderId: String,
        accessCode: String,
        invitedEmail: String,
        invitedRole: String
    ): String {
        val normalizedAccessCode = normalizeInviteAccessCode(accessCode)
        require(isValidInviteAccessCode(normalizedAccessCode)) {
            "Invite access code must contain exactly 16 valid characters"
        }
        require(folderId.isNotBlank()) { "Folder ID must not be blank" }
        require(invitedEmail.isNotBlank()) { "Invited email must not be blank" }
        require(invitedRole.isNotBlank()) { "Invited role must not be blank" }

        val salt = ByteArray(INVITE_SALT_BYTES).also(secureRandom::nextBytes)
        val iv = ByteArray(INVITE_IV_BYTES).also(secureRandom::nextBytes)
        val key = deriveKey(normalizedAccessCode, salt, INVITE_ITERATIONS, PASSWORD_KEY_BITS)
        val invitePayload = EncryptedInvitePayload(
            configId = folderId,
            invitedEmail = invitedEmail.trim().lowercase(Locale.US),
            invitedRole = invitedRole,
            issuedAt = java.time.Instant.now().toString()
        )

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, iv)
        )
        cipher.updateAAD(INVITE_VERSION.toByteArray(StandardCharsets.UTF_8))
        val ciphertext = cipher.doFinal(gson.toJson(invitePayload).toByteArray(StandardCharsets.UTF_8))

        val encryptedPayload = salt + iv + ciphertext
        return "$INVITE_VERSION.${urlEncoder.encodeToString(encryptedPayload)}"
    }

    fun generateInviteAccessCode(): String {
        val chars = CharArray(INVITE_ACCESS_CODE_LENGTH) {
            INVITE_ACCESS_CODE_ALPHABET[secureRandom.nextInt(INVITE_ACCESS_CODE_ALPHABET.length)]
        }
        return String(chars)
    }

    fun formatInviteAccessCode(accessCode: String): String {
        return normalizeInviteAccessCode(accessCode)
            .chunked(INVITE_ACCESS_CODE_GROUP_SIZE)
            .joinToString("-")
    }

    fun normalizeInviteAccessCode(value: String): String {
        return value
            .uppercase(Locale.US)
            .filterNot { it == '-' || it.isWhitespace() }
    }

    fun isValidInviteAccessCode(value: String): Boolean {
        return inviteAccessCodeRegex.matches(normalizeInviteAccessCode(value))
    }

    fun getInviteCredentialType(payload: String): InviteCredentialType {
        return if (payload.startsWith("$INVITE_VERSION.") || payload.startsWith("$PREVIOUS_ACCESS_CODE_INVITE_VERSION.")) {
            InviteCredentialType.ACCESS_CODE
        } else {
            InviteCredentialType.PIN
        }
    }

    /**
     * Decrypts current authenticated invites and legacy invite formats so links
     * generated by older app versions continue to work after deployment.
     */
    fun decryptInvite(payload: String, secret: String): String? {
        return decryptInvitePayload(payload, secret)?.configId
    }

    fun decryptInvitePayload(payload: String, secret: String): InvitePayload? {
        return when {
            payload.startsWith("$INVITE_VERSION.") -> {
                val normalizedAccessCode = normalizeInviteAccessCode(secret)
                if (!isValidInviteAccessCode(normalizedAccessCode)) return null
                decryptCurrentInvitePayload(payload, normalizedAccessCode)
            }
            payload.startsWith("$PREVIOUS_ACCESS_CODE_INVITE_VERSION.") -> {
                val normalizedAccessCode = normalizeInviteAccessCode(secret)
                if (!isValidInviteAccessCode(normalizedAccessCode)) return null
                decryptPreviousAccessCodeInvite(payload, normalizedAccessCode)?.let { configId ->
                    InvitePayload(configId = configId)
                }
            }
            payload.startsWith("$LEGACY_GCM_INVITE_VERSION.") -> {
                if (!legacyInvitePinRegex.matches(secret)) return null
                decryptLegacyGcmInvite(payload, secret)?.let { configId ->
                    InvitePayload(configId = configId)
                }
            }
            else -> {
                if (!legacyInvitePinRegex.matches(secret)) return null
                decryptLegacyInvite(payload, secret)?.let { configId ->
                    InvitePayload(configId = configId)
                }
            }
        }
    }

    private fun decryptCurrentInvitePayload(payload: String, accessCode: String): InvitePayload? {
        return try {
            val encoded = payload.substringAfter("$INVITE_VERSION.", missingDelimiterValue = "")
            val bytes = urlDecoder.decode(encoded)
            val minimumSize = INVITE_SALT_BYTES + INVITE_IV_BYTES + (GCM_TAG_BITS / 8)
            if (bytes.size <= minimumSize) return null

            val salt = bytes.copyOfRange(0, INVITE_SALT_BYTES)
            val iv = bytes.copyOfRange(INVITE_SALT_BYTES, INVITE_SALT_BYTES + INVITE_IV_BYTES)
            val ciphertext = bytes.copyOfRange(INVITE_SALT_BYTES + INVITE_IV_BYTES, bytes.size)
            val key = deriveKey(accessCode, salt, INVITE_ITERATIONS, PASSWORD_KEY_BITS)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            cipher.updateAAD(INVITE_VERSION.toByteArray(StandardCharsets.UTF_8))
            val plaintext = String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
            val invite = gson.fromJson(plaintext, EncryptedInvitePayload::class.java)
            InvitePayload(
                configId = invite.configId,
                invitedEmail = invite.invitedEmail.trim().lowercase(Locale.US),
                invitedRole = invite.invitedRole
            ).takeIf { it.configId.isNotBlank() && !it.invitedEmail.isNullOrBlank() && !it.invitedRole.isNullOrBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun decryptPreviousAccessCodeInvite(payload: String, accessCode: String): String? {
        return try {
            val encoded = payload.substringAfter("$PREVIOUS_ACCESS_CODE_INVITE_VERSION.", missingDelimiterValue = "")
            val bytes = urlDecoder.decode(encoded)
            val minimumSize = INVITE_SALT_BYTES + INVITE_IV_BYTES + (GCM_TAG_BITS / 8)
            if (bytes.size <= minimumSize) return null

            val salt = bytes.copyOfRange(0, INVITE_SALT_BYTES)
            val iv = bytes.copyOfRange(INVITE_SALT_BYTES, INVITE_SALT_BYTES + INVITE_IV_BYTES)
            val ciphertext = bytes.copyOfRange(INVITE_SALT_BYTES + INVITE_IV_BYTES, bytes.size)
            val key = deriveKey(accessCode, salt, INVITE_ITERATIONS, PASSWORD_KEY_BITS)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            cipher.updateAAD(PREVIOUS_ACCESS_CODE_INVITE_VERSION.toByteArray(StandardCharsets.UTF_8))
            val plaintext = String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
            plaintext.takeIf { it.isNotBlank() && it.length <= 256 }
        } catch (_: Exception) {
            null
        }
    }

    private fun decryptLegacyGcmInvite(payload: String, pin: String): String? {
        return try {
            val encoded = payload.substringAfter("$LEGACY_GCM_INVITE_VERSION.", missingDelimiterValue = "")
            val bytes = urlDecoder.decode(encoded)
            val minimumSize = INVITE_SALT_BYTES + INVITE_IV_BYTES + (GCM_TAG_BITS / 8)
            if (bytes.size <= minimumSize) return null

            val salt = bytes.copyOfRange(0, INVITE_SALT_BYTES)
            val iv = bytes.copyOfRange(INVITE_SALT_BYTES, INVITE_SALT_BYTES + INVITE_IV_BYTES)
            val ciphertext = bytes.copyOfRange(INVITE_SALT_BYTES + INVITE_IV_BYTES, bytes.size)
            val key = deriveKey(pin, salt, INVITE_ITERATIONS, PASSWORD_KEY_BITS)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            cipher.updateAAD(LEGACY_GCM_INVITE_VERSION.toByteArray(StandardCharsets.UTF_8))
            val plaintext = String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
            plaintext.takeIf { it.isNotBlank() && it.length <= 256 }
        } catch (_: Exception) {
            null
        }
    }

    private fun decryptLegacyInvite(payload: String, pin: String): String? {
        return try {
            val keyBytes = pin.padEnd(32, '0').toByteArray(StandardCharsets.UTF_8)
            val cipher = Cipher.getInstance(LEGACY_INVITE_ALGORITHM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                IvParameterSpec(ByteArray(16))
            )
            val plaintext = String(cipher.doFinal(urlDecoder.decode(payload)), StandardCharsets.UTF_8)
            plaintext.takeIf { it.isNotBlank() && it.length <= 256 }
        } catch (_: Exception) {
            null
        }
    }

    private fun deriveKey(
        password: String,
        salt: ByteArray,
        iterations: Int,
        keyBits: Int
    ): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, keyBits)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun legacySha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
