package com.example.access.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.example.access.data.BrandingConfig

val LocalBranding = staticCompositionLocalOf { BrandingConfig() }

@Composable
fun AccessTheme(
    branding: BrandingConfig = BrandingConfig(),
    content: @Composable () -> Unit
) {
    val primaryColor = try {
        Color(android.graphics.Color.parseColor(branding.primaryColor))
    } catch (e: Exception) {
        Color(0xFF006064)
    }

    val colorScheme = lightColorScheme(
        primary = primaryColor,
        onPrimary = Color.White,
        primaryContainer = primaryColor.copy(alpha = 0.08f),
        onPrimaryContainer = primaryColor,
        secondary = Color(0xFF455A64),
        surface = Color.White,
        background = Color(0xFFF8F9FB), // Premium soft gray
        outlineVariant = Color(0xFFE0E0E0),
        tertiary = Color(0xFF00BFA5)
    )

    CompositionLocalProvider(LocalBranding provides branding) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography, // Ensure you have standard Typography defined
            content = content
        )
    }
}
