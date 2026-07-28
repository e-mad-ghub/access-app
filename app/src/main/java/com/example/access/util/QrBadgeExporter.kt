package com.example.access.util

import android.content.Context
import android.content.Intent
import android.graphics.*
import androidx.core.content.FileProvider
import com.example.access.data.Member
import java.io.File
import java.io.FileOutputStream

object QrBadgeExporter {

    /**
     * Generates a "Digital Member Pass" and opens the share sheet.
     */
    fun exportAndSharePass(context: Context, member: Member, orgName: String, logoBitmap: Bitmap?) {
        val width = 800
        val height = 1200
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        
        // Pass Frame
        paint.color = Color.parseColor("#F5F5F5")
        canvas.drawRoundRect(20f, 20f, width - 20f, height - 20f, 40f, 40f, paint)

        // Header Background
        paint.color = Color.parseColor("#006064") // Org Primary
        canvas.drawRoundRect(20f, 20f, width - 20f, 300f, 40f, 40f, paint)

        // Logo
        logoBitmap?.let {
            val scaledLogo = Bitmap.createScaledBitmap(it, 120, 120, true)
            canvas.drawBitmap(scaledLogo, 50f, 50f, null)
        }

        // Business Name
        paint.color = Color.WHITE
        paint.textSize = 45f
        paint.textAlign = Paint.Align.LEFT
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText(orgName.uppercase(), 190f, 130f, paint)

        // Digital Pass Title
        paint.textSize = 25f
        paint.alpha = 180
        canvas.drawText("OFFICIAL DIGITAL PASS", 190f, 170f, paint)

        // Main QR Code
        val qrBitmap = SecurityUtils.generateQRCodeBitmap(member.qrCodeHash, 550)
        canvas.drawBitmap(qrBitmap, (width - 550f) / 2, 350f, null)

        // Member Details
        paint.color = Color.BLACK
        paint.alpha = 255
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 55f
        canvas.drawText(member.fullName, width / 2f, 980f, paint)
        
        paint.textSize = 30f
        paint.color = Color.GRAY
        canvas.drawText("Pass Key ID: ${member.memberId}", width / 2f, 1040f, paint)

        // Footer Bar
        paint.color = Color.parseColor("#EEEEEE")
        canvas.drawRect(20f, 1100f, width - 20f, 1180f, paint)
        paint.color = Color.DKGRAY
        paint.textSize = 28f
        canvas.drawText("THIS PASS IS FOR AUTHORIZED ACCESS ONLY", width / 2f, 1150f, paint)

        // Save to cache and trigger Android Share Sheet
        val file = File(context.cacheDir, "DigitalPass_${member.memberId}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        val uri = FileProvider.getUriForFile(context, "com.example.access.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Digital Pass"))
    }
}
