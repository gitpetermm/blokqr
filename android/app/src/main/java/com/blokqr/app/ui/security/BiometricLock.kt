package com.blokqr.app.ui.security
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
/* =====================================================================
 * Verrous biométriques — 100 % LOCAUX à l'appareil (BiometricPrompt).
 *
 * AUCUN compte, AUCUN réseau, AUCUNE donnée biométrique exposée : on demande
 * seulement à l'OS « est-ce bien le propriétaire de ce téléphone ? », vérifié
 * contre l'empreinte / le visage enregistrés, avec repli sur le code / schéma /
 * mot de passe de verrouillage de l'appareil. La biométrie ne quitte jamais
 * l'enclave sécurisée ; l'application ne la voit pas.
 *
 * Deux composables, activables INDÉPENDAMMENT et OPTIONNELLEMENT :
 *   - BiometricGate : verrouille un écran (l'Historique). Pas de re-verrouillage.
 *   - AppLockGate   : verrouille toute l'application, avec re-verrouillage à la
 *     mise en arrière-plan et délai de grâce paramétrable.
 *
 * Limite assumée : un verrou d'UI est une couche de CONFIDENTIALITÉ / dissuasion
 * (empêche un curieux d'ouvrir l'app sur un téléphone déjà déverrouillé). La
 * VRAIE protection des données reste le chiffrement au repos (AES-GCM) de
 * l'historique, qui tient même face au root/forensic.
 * ===================================================================== */
/** Authentificateurs autorisés : biométrie forte + code appareil (API 30+). */
internal fun biometricAuthenticators(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        BIOMETRIC_STRONG or DEVICE_CREDENTIAL
    } else {
        BIOMETRIC_STRONG
    }
/** Un moyen d'authentification est-il disponible (biométrie OU code) ? */
internal fun canAuthenticate(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(biometricAuthenticators()) ==
        BiometricManager.BIOMETRIC_SUCCESS
/** Remonte la chaîne de ContextWrapper jusqu'à la FragmentActivity hôte. */
internal fun Context.findFragmentActivity(): FragmentActivity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
/** Affiche le prompt système. onError = annulation / bouton négatif / erreur. */
private fun runBiometricPrompt(
    activity: FragmentActivity,
    context: Context,
    title: String,
    subtitle: String,
    cancelLabel: String,
    onSuccess: () -> Unit,
    onError: () -> Unit,
) {
    val executor = ContextCompat.getMainExecutor(context)
    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            onSuccess()
        }
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            onError()
        }
        override fun onAuthenticationFailed() {
            // Tentative non reconnue : le système laisse réessayer, on ne fait rien.
        }
    }
    val prompt = BiometricPrompt(activity, executor, callback)
    val builder = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setAllowedAuthenticators(biometricAuthenticators())
    // Avant Android 11, biométrie + code ne se combinent pas via
    // setAllowedAuthenticators -> un bouton négatif est obligatoire.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        builder.setNegativeButtonText(cancelLabel)
    }
    runCatching { prompt.authenticate(builder.build()) }.onFailure { onError() }
}
/**
 * Authentification ponctuelle pour AUTORISER UNE ACTION sensible hors écran
 * verrouillé — par ex. DÉSACTIVER le verrou de l'historique. Sans cela, on
 * pourrait lever le verrou puis consulter librement l'historique.
 *
 * Fail-open assumé : si aucun moyen d'authentification n'est configuré sur
 * l'appareil (ni biométrie ni code), l'action est autorisée directement,
 * de façon cohérente avec BiometricGate / AppLockGate.
 */
fun authenticateForAction(
    context: Context,
    title: String,
    subtitle: String,
    cancelLabel: String,
    onSuccess: () -> Unit,
    onCancel: () -> Unit = {},
) {
    val activity = context.findFragmentActivity()
    if (activity == null || !canAuthenticate(context)) {
        onSuccess()
        return
    }
    runBiometricPrompt(
        activity = activity,
        context = context,
        title = title,
        subtitle = subtitle,
        cancelLabel = cancelLabel,
        onSuccess = onSuccess,
        onError = onCancel,
    )
}
/** Écran de remplacement affiché tant que l'accès est verrouillé. */
@Composable
private fun LockedScreen(
    title: String,
    subtitle: String,
    unlockLabel: String,
    prompting: Boolean,
    onUnlock: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Rounded.Lock, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onUnlock, enabled = !prompting) {
            Icon(Icons.Rounded.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(unlockLabel)
        }
    }
}
/* ---------------------------------------------------------------------
 * 1) Verrou d'ÉCRAN (Historique) — pas de re-verrouillage.
 * ------------------------------------------------------------------- */
@Composable
fun BiometricGate(
    enabled: Boolean,
    title: String,
    subtitle: String,
    unlockLabel: String,
    cancelLabel: String,
    onCancel: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val available = remember(activity) { activity != null && canAuthenticate(context) }
    // Rien à verrouiller (pas d'auth configurée) -> accès direct.
    var authenticated by remember { mutableStateOf(!available) }
    var prompting by remember { mutableStateOf(false) }
    fun prompt() {
        val act = activity ?: return
        prompting = true
        runBiometricPrompt(
            act, context, title, subtitle, cancelLabel,
            onSuccess = { prompting = false; authenticated = true },
            onError = { prompting = false; onCancel() },
        )
    }
    LaunchedEffect(available) {
        if (available && !authenticated) prompt()
    }
    if (authenticated) {
        content()
    } else {
        LockedScreen(title, subtitle, unlockLabel, prompting, ::prompt)
    }
}
/* ---------------------------------------------------------------------
 * 2) Verrou d'APPLICATION — re-verrouillage à la mise en arrière-plan,
 *    avec délai de grâce (en secondes ; 0 = immédiat).
 * ------------------------------------------------------------------- */
@Composable
fun AppLockGate(
    enabled: Boolean,
    graceSeconds: Int,
    title: String,
    subtitle: String,
    unlockLabel: String,
    cancelLabel: String,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val available = remember(activity) { enabled && activity != null && canAuthenticate(context) }
    if (!available) {
        // Désactivé ou aucune auth disponible -> aucun verrou.
        content()
        return
    }
    var locked by remember { mutableStateOf(true) }      // verrouillé au lancement
    var prompting by remember { mutableStateOf(false) }
    var backgroundedAt by remember { mutableLongStateOf(0L) }
    fun prompt() {
        val act = activity ?: return
        prompting = true
        runBiometricPrompt(
            act, context, title, subtitle, cancelLabel,
            onSuccess = { prompting = false; locked = false },
            onError = { prompting = false /* reste verrouillé */ },
        )
    }
    // Observateur de cycle de vie attaché directement à l'activité (LifecycleOwner).
    DisposableEffect(activity, graceSeconds) {
        val act = activity!!
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    // On note l'heure de mise en arrière-plan (sauf pendant un prompt,
                    // ex. la saisie du code appareil qui ouvre une sous-activité).
                    if (!prompting) backgroundedAt = SystemClock.elapsedRealtime()
                }
                Lifecycle.Event.ON_START -> {
                    if (!prompting && backgroundedAt != 0L) {
                        val awayMs = SystemClock.elapsedRealtime() - backgroundedAt
                        if (awayMs >= graceSeconds * 1000L) locked = true
                    }
                }
                else -> Unit
            }
        }
        act.lifecycle.addObserver(observer)
        onDispose { act.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(locked) {
        if (locked) prompt()
    }
    // Le contenu reste composé (état préservé) ; un calque opaque le masque quand
    // c'est verrouillé. Pour masquer aussi la vignette du multitâche, activer
    // « Écran sécurisé » (FLAG_SECURE) dans les réglages.
    Box(Modifier.fillMaxSize()) {
        content()
        if (locked) {
            Surface(Modifier.fillMaxSize()) {
                LockedScreen(title, subtitle, unlockLabel, prompting, ::prompt)
            }
        }
    }
}
