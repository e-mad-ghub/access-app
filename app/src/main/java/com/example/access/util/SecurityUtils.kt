package com.example.access.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object SecurityUtils {

    fun hashPassword(password: String): String {
        val bytes = password.toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.fold("") { str, it -> str + "%02x".format(it) }
    }

    fun generateSecureQRHash(memberId: String): String {
        val salt = "EasyPass_Secure_2026"
        return hashPassword("$memberId$salt")
    }

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
        } catch (e: Exception) {
            null
        }
    }

    // --- AES-256 ENCRYPTION FOR INVITE LINKS ---
    
    private const val ALGORITHM = "AES/CBC/PKCS5Padding"

    fun encryptInvite(folderId: String, pin: String): String {
        try {
            // Derive a 32-byte key from the 4-digit PIN (Padded)
            val keyBytes = pin.padEnd(32, '0').toByteArray()
            val secretKey = SecretKeySpec(keyBytes, "AES")
            
            // Fixed IV for simplicity in deep linking (or could be derived)
            val iv = IvParameterSpec(ByteArray(16))
            
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, iv)
            
            val encrypted = cipher.doFinal(folderId.toByteArray())
            return Base64.getUrlEncoder().encodeToString(encrypted)
        } catch (e: Exception) {
            return ""
        }
    }

    fun decryptInvite(payload: String, pin: String): String? {
        return try {
            val keyBytes = pin.padEnd(32, '0').toByteArray()
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val iv = IvParameterSpec(ByteArray(16))
            
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, iv)
            
            val decoded = Base64.getUrlDecoder().decode(payload)
            val decrypted = cipher.doFinal(decoded)
            String(decrypted)
        } catch (e: Exception) {
            null // Wrong PIN
        }
    }
}
