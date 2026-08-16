package com.blokqr.app.ui.theme

import androidx.compose.ui.graphics.Color

// --- Identité visuelle (thème sombre d'origine, conservée) --------------- //
val Navy = Color(0xFF0B2545)
val NavyDeep = Color(0xFF06182E)
val Blue = Color(0xFF2E75B6)
val Surface = Color(0xFF11243B)
val OnSurfaceDim = Color(0xFFB8C4D4)

// --- Couleurs de verdict (cahier des charges, communes à tous les thèmes) - //
val SafeGreen = Color(0xFF00C853)
val DangerAmber = Color(0xFFFFAB00)
val MaliciousRed = Color(0xFFD50000)
val NeutralGrey = Color(0xFF5A6B7B)

// --- Tokens additionnels (design system) --------------------------------- //
val BlueBright = Color(0xFF4F9BE0)
val NavyMid = Color(0xFF0E1F37)
val SurfaceElevated = Color(0xFF16314F)
val SurfaceVariant = Color(0xFF1C3A5C)
val OutlineDim = Color(0xFF2C4A66)
val OnSurfaceStrong = Color(0xFFEAF1F8)

// --- Thème CLAIR --------------------------------------------------------- //
val LightBg = Color(0xFFF4F7FB)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFE7EEF6)
val LightOnBg = Color(0xFF0B2545)
val LightOnSurface = Color(0xFF14283F)
val LightOnSurfaceVar = Color(0xFF49617B)
val LightOutline = Color(0xFFC4D2E2)

// --- Thème CHAUD (faible lumière bleue, tons sépia) ---------------------- //
val WarmBg = Color(0xFF1C1611)
val WarmSurface = Color(0xFF2A2018)
val WarmSurfaceVariant = Color(0xFF392B20)
val WarmPrimary = Color(0xFFE0A458)
val WarmOnSurface = Color(0xFFEDE0D2)
val WarmOnSurfaceVar = Color(0xFFC9B49E)
val WarmOutline = Color(0xFF4A382A)

// --- Thème FROID (tons cyan/bleu profond) -------------------------------- //
val ColdBg = Color(0xFF07171F)
val ColdSurface = Color(0xFF0E2A36)
val ColdSurfaceVariant = Color(0xFF143A49)
val ColdPrimary = Color(0xFF36C5D6)
val ColdOnSurface = Color(0xFFD6EEF4)
val ColdOnSurfaceVar = Color(0xFF9CC2CE)
val ColdOutline = Color(0xFF1F4A59)
/* ---- Charte de marque BlokQR (branding uniquement, JAMAIS un verdict) ---- */
val BrandDeepBlue   = Color(0xFF0A111F) // fond principal (icone, mode sombre)
val BrandRoyalBlue  = Color(0xFF003399) // carre derriere le QR
val BrandLaserGreen = Color(0xFF00FF66) // ligne de scan + halo (branding)
val BrandBlueMid    = Color(0xFF3D7DCA) // debut degrade du mot "Blok"
val BrandTurquoise  = Color(0xFF00FFFF) // fin degrade du mot "QR"
val BrandOffWhite   = Color(0xFFE0E0E0) // motif QR, coins de cadrage
val BrandMidGrey    = Color(0xFF808080) // texte secondaire
val BrandSafeStatus = Color(0xFF33CC33) // statut "securise" (distinct du laser)
val BrandBorderGrey = Color(0xFF404040) // bordures champs/boutons