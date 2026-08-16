package com.blokqr.app.ui.scan
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.PointF
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeomSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.blokqr.app.R
import com.blokqr.app.billing.EntitlementUiState
import com.blokqr.app.scanner.BarcodeAnalyzer
import com.blokqr.app.scanner.ScanFrame
import com.blokqr.app.ui.EntitlementBadge
import com.blokqr.app.ui.theme.BrandLaserGreen
import com.blokqr.app.ui.theme.IconBadge
import com.blokqr.app.ui.theme.PrimaryButton
import com.blokqr.app.ui.theme.Spacing
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
// Verrouillage : durée avant l'analyse, et amplitude du zoom-grab (1f = désactivé).
private const val LOCK_HOLD_MS = 360L
private const val GRAB_SCALE = 1.35f
// Durée d'affichage du repère de mise au point (ms).
private const val FOCUS_RING_MS = 900L
@Composable
fun ScannerScreen(
    onScanned: (String, String) -> Unit,
    onShowInfo: () -> Unit,
    onShowSettings: () -> Unit,
    entitlement: EntitlementUiState,
    onUpgrade: () -> Unit,
    onAnalyzeUrl: () -> Unit,
) {
    val context = LocalContext.current
    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCamera = granted }
    LaunchedEffect(Unit) {
        if (!hasCamera) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    // Use case de CAPTURE (cliché pleine résolution depuis la caméra en direct).
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    // Mode capture (« Prendre une photo ») : met le scan live en pause et affiche
    // un déclencheur ; capturing = capture en cours (déclencheur désactivé).
    var captureMode by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    fun showToast(msgRes: Int) {
        Toast.makeText(context, context.getString(msgRes), Toast.LENGTH_SHORT).show()
    }
    // Déclencheur : capture in-app -> décodage (QR d'abord, sinon OCR).
    val capturePhoto: () -> Unit = {
        if (!capturing) {
            capturing = true
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            imageCapture.takePicture(
                mainExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(proxy: ImageProxy) {
                        val bitmap = imageProxyToUprightBitmap(proxy)
                        proxy.close()
                        scope.launch {
                            try {
                                val hit = decodeCapturedBitmap(bitmap)
                                if (hit != null) {
                                    // Trouvé -> analyse (on quitte le mode capture).
                                    captureMode = false
                                    capturing = false
                                    onScanned(hit.first, hit.second)
                                } else {
                                    // Rien -> on reste en capture pour réessayer.
                                    capturing = false
                                    showToast(R.string.image_scan_none)
                                }
                            } catch (e: Exception) {
                                capturing = false
                                showToast(R.string.image_scan_error)
                            }
                        }
                    }
                    override fun onError(exc: ImageCaptureException) {
                        capturing = false
                        showToast(R.string.image_scan_error)
                    }
                },
            )
        }
    }
    // Sélecteur Galerie / Photo : la photo bascule en mode capture.
    val openImageSourceSheet = rememberImageCodeScanner(
        onResult = onScanned,
        onNothingFound = { showToast(R.string.image_scan_none) },
        onError = { showToast(R.string.image_scan_error) },
        onTakePhoto = { captureMode = true },
    )
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it },
    ) {
        var torchOn by remember { mutableStateOf(false) }
        var torchAvailable by remember { mutableStateOf(false) }
        if (hasCamera) {
            var lockState by remember { mutableStateOf<QrLockState>(QrLockState.Idle) }
            var handled by remember { mutableStateOf(false) }
            var focusPoint by remember { mutableStateOf<Offset?>(null) }
            var focusTick by remember { mutableIntStateOf(0) }
            LifecycleResumeEffect(Unit) {
                handled = false
                lockState = QrLockState.Idle
                torchOn = false
                focusPoint = null
                captureMode = false
                capturing = false
                onPauseOrDispose { }
            }
            val locked = lockState as? QrLockState.Locked
            val grabScale by animateFloatAsState(
                targetValue = if (locked != null) GRAB_SCALE else 1f,
                animationSpec = tween(320, easing = FastOutSlowInEasing),
                label = "grabScale",
            )
            val pivotX: Float
            val pivotY: Float
            if (locked != null && viewSize != IntSize.Zero && locked.corners.size >= 4) {
                val cx = locked.corners.map { it.x }.average().toFloat()
                val cy = locked.corners.map { it.y }.average().toFloat()
                pivotX = (cx / viewSize.width).coerceIn(0f, 1f)
                pivotY = (cy / viewSize.height).coerceIn(0f, 1f)
            } else {
                pivotX = 0.5f
                pivotY = 0.5f
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = grabScale
                        scaleY = grabScale
                        transformOrigin = TransformOrigin(pivotX, pivotY)
                    },
            ) {
                CameraPreview(
                    imageCapture = imageCapture,
                    torchEnabled = torchOn,
                    onTorchAvailable = { torchAvailable = it },
                    onFocusRequested = { pos ->
                        focusPoint = pos
                        focusTick++
                    },
                    onResult = { scan ->
                        // Scan live ignoré en mode capture (photo manuelle).
                        if (!handled && !captureMode &&
                            viewSize != IntSize.Zero && scan.codes.isNotEmpty()
                        ) {
                            val vw = viewSize.width.toFloat()
                            val vh = viewSize.height.toFloat()
                            val first = scan.codes.first()
                            val corners = first.corners.map {
                                mapImageToView(it, scan.sourceWidth, scan.sourceHeight, vw, vh)
                            }
                            handled = true
                            lockState = QrLockState.Locked(corners)
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch {
                                delay(LOCK_HOLD_MS)
                                onScanned(first.raw, first.symbology)
                            }
                        }
                    },
                )
                if (locked != null) {
                    QrLockOverlay(
                        state = locked,
                        accent = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (locked == null) ScannerOverlay()
            FocusRing(point = focusPoint, tick = focusTick, accent = MaterialTheme.colorScheme.primary)
            if (captureMode) {
                // Contrôles de capture : Annuler (gauche) + indice + déclencheur.
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = 28.dp),
                ) {
                    CircleAction(
                        Icons.Rounded.Close,
                        stringResource(R.string.history_cancel),
                        onClick = {
                            captureMode = false
                            capturing = false
                        },
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 24.dp),
                    )
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        HintPill(text = stringResource(R.string.capture_hint))
                        Spacer(Modifier.height(Spacing.md))
                        ShutterButton(
                            capturing = capturing,
                            contentDescription = stringResource(R.string.capture_take),
                            onClick = capturePhoto,
                        )
                    }
                }
            } else {
                // Accroche discrete + actions alternatives (URL / image) presentees
                // en DEUX tuiles egales cote a cote. Le scan camera etant deja
                // actif, l'accroche n'est plus un bouton mais un simple texte
                // d'aide sous le viseur : on distingue l'INSTRUCTION (scanner) des
                // ACTIONS alternatives (analyser une URL, importer une image).
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.scan_hint),
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(Spacing.lg))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        ActionTile(
                            icon = Icons.Rounded.Public,
                            text = stringResource(R.string.url_analyze_title),
                            onClick = onAnalyzeUrl,
                            modifier = Modifier.weight(1f),
                        )
                        ActionTile(
                            icon = Icons.Rounded.Image,
                            text = stringResource(R.string.image_scan_title),
                            onClick = openImageSourceSheet,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        } else {
            CameraPermissionState(onAllow = { permissionLauncher.launch(Manifest.permission.CAMERA) })
        }
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            CircleAction(
                Icons.Rounded.HelpOutline, stringResource(R.string.action_guide), onShowInfo,
                Modifier.align(Alignment.CenterStart),
            )
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                Spacer(Modifier.height(4.dp))
                EntitlementBadge(state = entitlement, onUpgrade = onUpgrade)
            }
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasCamera && torchAvailable) {
                    CircleToggle(
                        iconOn = Icons.Rounded.FlashOn,
                        iconOff = Icons.Rounded.FlashOff,
                        on = torchOn,
                        contentDescription = stringResource(R.string.action_torch),
                        onToggle = { torchOn = !torchOn },
                    )
                    Spacer(Modifier.width(Spacing.xs))
                }
                CircleAction(
                    Icons.Rounded.Settings, stringResource(R.string.action_settings), onShowSettings,
                )
            }
        }
    }
}
/* ----------------------------- Déclencheur ------------------------------- */
@Composable
private fun ShutterButton(
    capturing: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(74.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .border(3.dp, Color.White, CircleShape)
            .clickable(
                enabled = !capturing,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (capturing) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 3.dp,
                modifier = Modifier.size(34.dp),
            )
        } else {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}
/**
 * Mappe un point du repère IMAGE redressée (srcW x srcH) vers le repère ÉCRAN
 * (viewW x viewH), pour un affichage FILL_CENTER, caméra arrière (pas de miroir).
 */
private fun mapImageToView(p: PointF, srcW: Int, srcH: Int, viewW: Float, viewH: Float): Offset {
    if (srcW <= 0 || srcH <= 0) return Offset(p.x, p.y)
    val scale = max(viewW / srcW.toFloat(), viewH / srcH.toFloat())
    val dx = (viewW - srcW * scale) / 2f
    val dy = (viewH - srcH * scale) / 2f
    return Offset(p.x * scale + dx, p.y * scale + dy)
}
/* ----------------------- Repère de mise au point ------------------------- */
@Composable
private fun FocusRing(point: Offset?, tick: Int, accent: Color) {
    if (point == null) return
    val progress = remember(tick) { Animatable(0f) }
    LaunchedEffect(tick) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = FOCUS_RING_MS.toInt(), easing = FastOutSlowInEasing))
    }
    Canvas(Modifier.fillMaxSize()) {
        val p = progress.value
        if (p >= 1f) return@Canvas
        val appear = (p / 0.25f).coerceIn(0f, 1f)
        val fade = 1f - ((p - 0.6f) / 0.4f).coerceIn(0f, 1f)
        val radius = 46f - 10f * appear
        drawCircle(
            color = accent.copy(alpha = 0.95f * fade),
            radius = radius,
            center = point,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
        )
        drawCircle(
            color = accent.copy(alpha = 0.18f * fade),
            radius = radius,
            center = point,
        )
    }
}
/* ----------------------------- Viseur (repos) ---------------------------- */
@Composable
private fun ScannerOverlay() {
    val accent = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "viewfinder")
    val sweep by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sweep",
    )
    Canvas(Modifier.fillMaxSize()) {
        val side = size.minDimension * 0.68f
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        val right = left + side
        val bottom = top + side
        val dim = Color.Black.copy(alpha = 0.5f)
        drawRect(dim, size = GeomSize(size.width, top))
        drawRect(dim, topLeft = Offset(0f, bottom), size = GeomSize(size.width, size.height - bottom))
        drawRect(dim, topLeft = Offset(0f, top), size = GeomSize(left, side))
        drawRect(dim, topLeft = Offset(right, top), size = GeomSize(size.width - right, side))
        val len = side * 0.12f
        val w = 8f
        val cap = StrokeCap.Round
        drawLine(accent, Offset(left, top), Offset(left + len, top), w, cap)
        drawLine(accent, Offset(left, top), Offset(left, top + len), w, cap)
        drawLine(accent, Offset(right, top), Offset(right - len, top), w, cap)
        drawLine(accent, Offset(right, top), Offset(right, top + len), w, cap)
        drawLine(accent, Offset(left, bottom), Offset(left + len, bottom), w, cap)
        drawLine(accent, Offset(left, bottom), Offset(left, bottom - len), w, cap)
        drawLine(accent, Offset(right, bottom), Offset(right - len, bottom), w, cap)
        drawLine(accent, Offset(right, bottom), Offset(right, bottom - len), w, cap)
        val y = top + side * sweep
        // Ligne de scan au VERT LASER de la charte (signature du logo). Le cadre
        // de visee ci-dessus reste sur `accent` (couleur du theme actif).
        drawLine(
            BrandLaserGreen.copy(alpha = 0.55f),
            Offset(left + 12, y), Offset(right - 12, y),
            strokeWidth = 10f, cap = StrokeCap.Round,
        )
        drawLine(
            BrandLaserGreen,
            Offset(left + 12, y), Offset(right - 12, y),
            strokeWidth = 4f, cap = StrokeCap.Round,
        )
    }
}
/* ----------------------------- Accroche ---------------------------------- */
@Composable
private fun HintPill(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = Spacing.lg, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.QrCodeScanner, null,
            tint = Color.White, modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}
/* ----------------------- Pilule d'action (bas d'écran) ------------------- */
@Composable
private fun ActionPill(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = Spacing.lg, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon, null,
            tint = accent, modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}
/* ------------------- Tuile d'action (bas d'ecran, cote a cote) ----------- */
@Composable
private fun ActionTile(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 76.dp)
            .padding(horizontal = Spacing.md, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon, null,
            tint = accent, modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
        )
    }
}
@Composable
private fun CameraPermissionState(onAllow: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(cs.background, cs.surface, cs.background)))
            .systemBarsPadding()
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconBadge(Icons.Rounded.PhotoCamera, cs.primary)
            Spacer(Modifier.height(Spacing.xl))
            Text(
                stringResource(R.string.camera_rationale),
                color = cs.onBackground,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.xl))
            PrimaryButton(
                text = stringResource(R.string.camera_allow),
                onClick = onAllow,
                icon = Icons.Rounded.PhotoCamera,
            )
        }
    }
}
/* ----------------------------- Bouton circulaire ------------------------- */
@Composable
private fun CircleAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f)),
    ) {
        Icon(icon, contentDescription, tint = Color.White)
    }
}
/**
 * Bouton circulaire à deux états (torche). Le fond passe à la couleur primaire
 * quand l'option est active, pour un retour visuel clair.
 */
@Composable
private fun CircleToggle(
    iconOn: ImageVector,
    iconOff: ImageVector,
    on: Boolean,
    contentDescription: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (on) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.90f)
    } else {
        Color.Black.copy(alpha = 0.35f)
    }
    IconButton(
        onClick = onToggle,
        modifier = modifier
            .clip(CircleShape)
            .background(bg),
    ) {
        Icon(if (on) iconOn else iconOff, contentDescription, tint = Color.White)
    }
}
/* ----------------------------- Caméra ------------------------------------ */
/**
 * Aperçu caméra : ProcessCameraProvider + Preview + ImageAnalysis + ImageCapture
 * (moteur de détection ÉPROUVÉ), avec ajouts découplés de l'analyse :
 *  - résolution d'analyse 1920x1080 (QR denses) ;
 *  - pinch-to-zoom matériel (deux doigts) ;
 *  - tap-to-focus (toucher simple) ;
 *  - torche (enableTorch) si un flash existe ;
 *  - ImageCapture pour la capture pleine résolution « Prendre une photo ».
 * PreviewView en mode COMPATIBLE pour que le zoom-grab s'applique au flux.
 *
 * Sécurité : on EXTRAIT seulement la valeur + la géométrie. Aucune ouverture,
 * aucun appel réseau vers la cible n'est déclenché ici.
 */
@Composable
private fun CameraPreview(
    imageCapture: ImageCapture,
    torchEnabled: Boolean,
    onTorchAvailable: (Boolean) -> Unit,
    onFocusRequested: (Offset) -> Unit,
    onResult: (ScanFrame) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    val cameraRef = remember { mutableStateOf<Camera?>(null) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    DisposableEffect(Unit) {
        onDispose { analyzerExecutor.shutdown() }
    }
    LaunchedEffect(cameraRef.value) {
        onTorchAvailable(cameraRef.value?.cameraInfo?.hasFlashUnit() == true)
    }
    LaunchedEffect(cameraRef.value, torchEnabled) {
        val cam = cameraRef.value ?: return@LaunchedEffect
        if (cam.cameraInfo.hasFlashUnit()) {
            cam.cameraControl.enableTorch(torchEnabled)
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { previewSize = it }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val cam = cameraRef.value ?: return@detectTapGestures
                    if (previewSize == IntSize.Zero) return@detectTapGestures
                    val factory = SurfaceOrientedMeteringPointFactory(
                        previewSize.width.toFloat(),
                        previewSize.height.toFloat(),
                    )
                    val meteringPoint = factory.createPoint(pos.x, pos.y)
                    val action = FocusMeteringAction.Builder(meteringPoint)
                        .setAutoCancelDuration(4, TimeUnit.SECONDS)
                        .build()
                    cam.cameraControl.startFocusAndMetering(action)
                    onFocusRequested(pos)
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoomChange, _ ->
                    val cam = cameraRef.value ?: return@detectTransformGestures
                    val zs = cam.cameraInfo.zoomState.value ?: return@detectTransformGestures
                    val target = (zs.zoomRatio * zoomChange)
                        .coerceIn(zs.minZoomRatio, zs.maxZoomRatio)
                    cam.cameraControl.setZoomRatio(target)
                }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    // Analyse live en 1920x1080 : plus de pixels pour décoder les
                    // QR denses/petits. Repli automatique vers la résolution
                    // disponible la plus proche si 1080p n'est pas supportée.
                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1920, 1080),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            )
                        )
                        .build()
                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(analyzerExecutor, BarcodeAnalyzer(onResult))
                        }
                    provider.unbindAll()
                    cameraRef.value = provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                        imageCapture,
                    )
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
        )
    }
}
