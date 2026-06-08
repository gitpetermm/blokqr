package com.blokqr.app.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.blokqr.app.scanner.BarcodeAnalyzer
import com.blokqr.app.ui.theme.Blue
import com.blokqr.app.ui.theme.NavyDeep
import java.util.concurrent.Executors

@Composable
fun ScannerScreen(onScanned: (String, String) -> Unit) {
    val context = LocalContext.current
    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCamera = granted }

    LaunchedEffect(Unit) {
        if (!hasCamera) launcher.launch(Manifest.permission.CAMERA)
    }

    Box(Modifier.fillMaxSize()) {
        if (hasCamera) {
            CameraPreview(onScanned)
            AnimatedViewfinder()
            Text(
                text = "Visez un QR code ou un code-barres",
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 64.dp)
                    .fillMaxWidth()
            )
        } else {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "BlokQR a besoin de la caméra pour scanner les codes. " +
                        "Aucune image n'est conservée ni transmise.",
                    color = Color.White, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                    Text("Autoriser la caméra")
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(onScanned: (String, String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(
                            analyzerExecutor,
                            BarcodeAnalyzer { raw, symbology -> onScanned(raw, symbology) }
                        )
                    }
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        }
    )
}

@Composable
private fun AnimatedViewfinder() {
    val transition = rememberInfiniteTransition(label = "viewfinder")
    val sweep by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sweep",
    )
    Canvas(Modifier.fillMaxSize()) {
        val side = size.minDimension * 0.66f
        val left = (size.width - side) / 2
        val top = (size.height - side) / 2
        // Cadre.
        drawRoundRect(
            color = Blue,
            topLeft = Offset(left, top),
            size = Size(side, side),
            cornerRadius = CornerRadius(28f, 28f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f),
        )
        // Ligne de balayage animée.
        val y = top + side * sweep
        drawLine(
            color = Blue.copy(alpha = 0.8f),
            start = Offset(left + 8, y),
            end = Offset(left + side - 8, y),
            strokeWidth = 4f,
        )
        // Voile sombre autour du cadre.
        drawRect(color = NavyDeep.copy(alpha = 0.55f), size = Size(size.width, top))
        drawRect(
            color = NavyDeep.copy(alpha = 0.55f),
            topLeft = Offset(0f, top + side),
            size = Size(size.width, size.height - top - side),
        )
    }
}
