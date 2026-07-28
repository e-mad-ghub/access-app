package com.example.access.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils

object ThemeUtils {
    /**
     * Determines if a color is "Light" or "Dark" to ensure readable text contrast.
     * Returns Black for light backgrounds and White for dark backgrounds.
     */
    fun getContrastColor(hexColor: String): Color {
        return try {
            val argb = android.graphics.Color.parseColor(hexColor)
            val luminance = ColorUtils.calculateLuminance(argb)
            if (luminance > 0.5) Color(0xFF1A1C1E) else Color.White
        } catch (e: Exception) {
            Color.White
        }
    }

    fun parseHexColor(hex: String, default: Color = Color(0xFF006064)): Color {
        return try {
            Color(android.graphics.Color.parseColor(hex))
        } catch (e: Exception) {
            default
        }
    }
}
