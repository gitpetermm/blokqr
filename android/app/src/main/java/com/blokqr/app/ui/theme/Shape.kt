package com.blokqr.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Coins arrondis cohérents sur toute l'app. */
val BlokQrShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Échelle d'espacement (rythme vertical/horizontal homogène) et dimensions
 * clés. Centraliser ces valeurs évite les « nombres magiques » dans les écrans
 * et garantit une mise en page régulière.
 */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

object Dimens {
    /** Largeur maximale du contenu : au-delà, on centre (tablette/paysage). */
    val contentMaxWidth = 560.dp
    /** Marge latérale de l'écran. */
    val screenPadding = 20.dp
    /** Hauteur des boutons d'action principaux. */
    val actionHeight = 52.dp
    /** Diamètre de la pastille d'icône de verdict. */
    val verdictBadge = 96.dp
}
