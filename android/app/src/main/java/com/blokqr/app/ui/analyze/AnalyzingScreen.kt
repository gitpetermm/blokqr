package com.blokqr.app.ui.analyze

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blokqr.app.ui.theme.Blue
import com.blokqr.app.ui.theme.OnSurfaceDim

@Composable
fun AnalyzingScreen(rawPreview: String) {
    val transition = rememberInfiniteTransition(label = "analyze")
    val angle by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "angle",
    )

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(96.dp)) {
            val stroke = 8f
            drawArc(
                color = OnSurfaceDim.copy(alpha = 0.2f),
                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(stroke, stroke),
                size = Size(size.width - 2 * stroke, size.height - 2 * stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = Blue,
                startAngle = angle, sweepAngle = 90f, useCenter = false,
                topLeft = Offset(stroke, stroke),
                size = Size(size.width - 2 * stroke, size.height - 2 * stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Spacer(Modifier.height(28.dp))
        Text("Analyse en cours…", color = OnSurfaceDim, fontSize = 18.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Suivi des redirections et inspection en bac à sable.\n" +
                "Le lien n'est jamais ouvert sur votre téléphone.",
            color = OnSurfaceDim.copy(alpha = 0.7f),
            textAlign = TextAlign.Center, fontSize = 13.sp,
        )
        Spacer(Modifier.height(16.dp))
        Text(rawPreview, color = OnSurfaceDim.copy(alpha = 0.5f),
            fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}
