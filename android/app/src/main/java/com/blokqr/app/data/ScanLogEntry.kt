package com.blokqr.app.data
import kotlinx.serialization.Serializable
/**
 * Entrée d'historique d'un scan utilisateur — STOCKÉE LOCALEMENT uniquement
 * (fichier JSON dans filesDir). Cette donnée ne quitte jamais le téléphone :
 * pas de transmission serveur, donc pas de « collecte » au sens Google
 * (cohérent avec la politique de confidentialité et la Data Safety).
 *
 * NB : nommé « ScanLog… » et non « ScanHistory… » pour ne PAS entrer en conflit
 * avec la classe existante ScanHistoryStore (mémoire des empreintes de
 * destination, qui sert à détecter destination_changed).
 *
 * Le verdict est stocké sous forme de NOM d'enum (SAFE, DANGEROUS, …). La
 * capture d'écran n'est volontairement PAS conservée (volume + sensibilité).
 *
 * @param value     valeur scannée (URL finale ou contenu décodé)
 * @param verdict   nom du Verdict (Verdict.name)
 * @param score     score de risque 0..100
 * @param symbology type de code (QR_CODE, EAN_13, url, …)
 * @param timestamp date du scan en epoch millis (horloge locale)
 */
@Serializable
data class ScanLogEntry(
    val value: String,
    val verdict: String,
    val score: Int,
    val symbology: String,
    val timestamp: Long,
)
