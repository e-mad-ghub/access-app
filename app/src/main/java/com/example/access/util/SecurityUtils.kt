package com.example.access.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object SecurityUtils {

    private const val PASSWORD_SCHEME = "pbkdf2-sha256"
    private const val PASSWORD_ITERATIONS = 120_000
    private const val PASSWORD_KEY_BITS = 256
    private const val PASSWORD_SALT_BYTES = 16

    private const val INVITE_VERSION = "v2"
    private const val INVITE_ITERATIONS = 150_000
    private const val INVITE_SALT_BYTES = 16
    private const val INVITE_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    private const val LEGACY_INVITE_ALGORITHM = "AES/CBC/PKCS5Padding"

    private val secureRandom = SecureRandom()
    private val urlEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val urlDecoder: Base64.Decoder = Base64.getUrlDecoder()

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
     * Encrypts an invite using a PIN-derived key and authenticated AES-GCM.
     * Payload format: v2.<base64url(salt || iv || ciphertext+tag)>.
     *
     * The random salt prevents precomputation, the random IV prevents deterministic
     * links, and the GCM tag rejects incorrect PINs/tampered links.
     */
    fun encryptInvite(folderId: String, pin: String): String {
        require(pin.matches(Regex("\\d{4}"))) { "Invite PIN must contain exactly four digits" }
        require(folderId.isNotBlank()) { "Folder ID must not be blank" }

        val salt = ByteArray(INVITE_SALT_BYTES).also(secureRandom::nextBytes)
        val iv = ByteArray(INVITE_IV_BYTES).also(secureRandom::nextBytes)
        val key = deriveKey(pin, salt, INVITE_ITERATIONS, PASSWORD_KEY_BITS)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, iv)
        )
        cipher.updateAAD(INVITE_VERSION.toByteArray(StandardCharsets.UTF_8))
        val ciphertext = cipher.doFinal(folderId.toByteArray(StandardCharsets.UTF_8))

        val payload = salt + iv + ciphertext
        return "$INVITE_VERSION.${urlEncoder.encodeToString(payload)}"
    }

    /**
     * Decrypts current authenticated invites and legacy CBC invites so links
     * generated by an older app version do not stop working after deployment.
     */
    fun decryptInvite(payload: String, pin: String): String? {
        if (!pin.matches(Regex("\\d{4}"))) return null
        return if (payload.startsWith("$INVITE_VERSION.")) {
            decryptCurrentInvite(payload, pin)
        } else {
            decryptLegacyInvite(payload, pin)
        }
    }

    private fun decryptCurrentInvite(payload: String, pin: String): String? {
        return try {
            val encoded = payload.substringAfter("$INVITE_VERSION.", missingDelimiterValue = "")
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
            cipher.updateAAD(INVITE_VERSION.toByteArray(StandardCharsets.UTF_8))
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