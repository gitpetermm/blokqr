package com.blokqr.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Échelle typographique de BlokQR.
 *
 * Police système (SansSerif) pour rester léger (aucun asset à embarquer) tout
 * en posant une hiérarchie nette : titres affirmés, corps lisible, libellés
 * compacts. Si une police de marque (ex. Inter) est ajoutée plus tard, il
 * suffit de remplacer `brand` ci-dessous.
 */
private val brand = FontFamily.SansSerif

val BlokQrTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = brand, fontWeight = FontWeight.Bold,
        fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = brand, fontWeight = FontWeight.Bold,
        fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = brand, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 26.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = brand, fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp, lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = brand, fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = brand, fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = brand, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = brand, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = brand, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = brand, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.3.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = brand, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = brand, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.5.sp,
    ),
)
