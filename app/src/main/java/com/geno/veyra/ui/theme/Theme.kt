package com.geno.veyra.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Veyra accent: a Gemini-adjacent blue → violet.
private val Indigo = Color(0xFF5B6CFF)
private val Violet = Color(0xFF8A5BFF)
private val Teal = Color(0xFF2DD4BF)

private val LightColors = lightColorScheme(
    primary = Indigo,
    secondary = Violet,
    tertiary = Teal,
)

private val DarkColors = darkColorScheme(
    primary = Indigo,
    secondary = Violet,
    tertiary = Teal,
)

@Composable
fun VeyraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content,
    )
}
