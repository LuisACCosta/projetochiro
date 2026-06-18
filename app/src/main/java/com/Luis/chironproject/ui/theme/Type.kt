package com.Luis.chironproject.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.Luis.chironproject.R

// Fonte Bauhaus (res/font/bauhaus.ttf) — usada nos títulos da identidade Chiron.
val Bauhaus = FontFamily(
    Font(R.font.bauhaus, FontWeight.Normal)
)

// Títulos usam Bauhaus; textos de corpo seguem na fonte padrão (legibilidade),
// como na identidade visual (títulos Bauhaus, textos Helvetica/sans-serif).
val Typography = Typography(
    headlineLarge = TextStyle(
        fontFamily = Bauhaus,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = Bauhaus,
        fontWeight = FontWeight.Normal,
        fontSize = 26.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = Bauhaus,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    labelLarge = TextStyle(
        fontFamily = Bauhaus,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.5.sp
    )
)