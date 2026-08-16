package com.blokqr.app.ui.tools
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.blokqr.app.R
/**
 * Aperçu d'un code généré. Rendu SANS interpolation (FilterQuality.None) pour
 * des bords nets — indispensable à la lisibilité d'un code. Fond blanc (quiet
 * zone) pour garantir le contraste au scan.
 */
@Composable
fun CodePreview(bitmap: Bitmap?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.tools_preview_desc),
                filterQuality = FilterQuality.None,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                text = stringResource(R.string.tools_preview_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
