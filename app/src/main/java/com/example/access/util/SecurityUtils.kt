package com.example.access.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.security.MessageDigest
import java.security.SecureRandom

object SecurityUtils {

    fun hashPassword(plainText: String): String {
        val bytes = plainText.toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.fold("") { str, it -> str + "%02x".format(it) }
    }

    fun generateSecureQRHash(memberId: String): String {
        val random = SecureRandom()
        val randomBytes = ByteArray(16)
        random.nextBytes(randomBytes)
        
        val randomHex = randomBytes.joinToString("") { "%02x".format(it) }
        val input = randomHex + memberId + System.currentTimeMillis().toString()
        
        return hashPassword(input)
    }

    fun generateQRCodeBitmap(hashString: String, size: Int = 512): Bitmap {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(hashString, BarcodeFormat.QR_CODE, size, size)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }
}
