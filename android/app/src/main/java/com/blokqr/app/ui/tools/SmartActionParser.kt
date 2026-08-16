package com.blokqr.app.ui.tools
import java.text.SimpleDateFormat
import java.util.Locale
/**
 * Cœur PUR (aucune dépendance Android) des « actions intelligentes » : détecte le
 * type d'un contenu scanné et en extrait les informations utiles. Séparé de
 * SmartActions (qui, lui, construit les Intents Android) pour être testable en JVM.
 *
 * Sécurité : seuls des schémas NON web déclenchent un transfert direct (geo/tel/
 * sms/mailto/vCard/vEvent). Les liens http(s) — Zoom/Teams inclus — ne sont PAS
 * traités ici : ils restent soumis au verdict d'analyse. `isMeetingLink` sert
 * seulement à proposer un libellé plus parlant, sans contourner la sécurité.
 */
object SmartActionParser {
    /** Nature du contenu, quand une action de transfert direct s'applique. */
    enum class SmartKind { MAPS, CALL, SMS, EMAIL, CONTACT, EVENT }
    private val MEETING_HOSTS = listOf(
        "zoom.us", "teams.microsoft.com", "teams.live.com", "meet.google.com", "webex.com",
    )
    /** Type d'action directe applicable au contenu, ou null (ex. lien web). */
    fun kindOf(raw: String): SmartKind? {
        val c = raw.trim().lowercase()
        return when {
            c.startsWith("geo:") -> SmartKind.MAPS
            c.startsWith("tel:") -> SmartKind.CALL
            c.startsWith("sms:") || c.startsWith("smsto:") -> SmartKind.SMS
            c.startsWith("mailto:") -> SmartKind.EMAIL
            c.startsWith("begin:vcard") -> SmartKind.CONTACT
            c.startsWith("begin:vevent") -> SmartKind.EVENT
            else -> null
        }
    }
    /** true si l'URL http(s) pointe vers un service de réunion connu. */
    fun isMeetingLink(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return MEETING_HOSTS.any { host == it || host.endsWith(".$it") }
    }
    /** Numéro + corps éventuel d'un contenu SMS (sms:/smsto:, ?body= ou :message). */
    fun smsParts(raw: String): Pair<String, String?> {
        val rest = raw.trim().substringAfter(":")
        val number = rest.substringBefore("?").substringBefore(":").trim()
        val body = when {
            "?body=" in raw -> decode(raw.substringAfter("?body="))
            rest.contains(":") -> rest.substringAfter(":").trim()   // SMSTO:num:message
            else -> ""
        }
        return number to body.ifBlank { null }
    }
    /**
     * Valeur d'un champ iCalendar/vCard (ex. "FN", "SUMMARY", "TEL") : première
     * ligne "CHAMP:" ou "CHAMP;PARAMS:", déséchappée (\\ \; \, \n).
     */
    fun icalField(text: String, field: String): String? {
        val f = field.uppercase()
        return text.lineSequence()
            .map { it.trimEnd('\r') }
            .firstOrNull {
                val u = it.trimStart().uppercase()
                u.startsWith("$f:") || u.startsWith("$f;")
            }
            ?.substringAfter(":")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.replace("\\n", "\n")
            ?.replace("\\,", ",")
            ?.replace("\\;", ";")
            ?.replace("\\\\", "\\")
    }
    /**
     * Début d'un vEvent en millisecondes (heure locale), ou null. Gère
     * DTSTART:AAAAMMJJTHHmmSS et DTSTART;VALUE=DATE:AAAAMMJJ (best-effort).
     */
    fun parseDtStartMillis(vevent: String): Long? {
        val value = icalField(vevent, "DTSTART") ?: return null
        return runCatching {
            val pattern = if (value.contains("T")) "yyyyMMdd'T'HHmmss" else "yyyyMMdd"
            SimpleDateFormat(pattern, Locale.US).parse(value)?.time
        }.getOrNull()
    }
    private fun hostOf(url: String): String? {
        val lower = url.trim().lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        val host = lower.substringAfter("://")
            .substringBefore("/")
            .substringBefore("?")
            .substringBefore("#")
            .substringBefore(":")
        return host.ifBlank { null }
    }
    /** Décodage %xx minimal (sans dépendance Android : évite java.net.URLDecoder+ et +espace). */
    private fun decode(s: String): String = runCatching {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            when {
                ch == '+' -> { sb.append(' '); i++ }
                ch == '%' && i + 2 < s.length -> {
                    sb.append(s.substring(i + 1, i + 3).toInt(16).toChar()); i += 3
                }
                else -> { sb.append(ch); i++ }
            }
        }
        sb.toString()
    }.getOrDefault(s)
}
