package com.blokqr.app.ui.tools
/**
 * Constructeurs de charges utiles (payloads) du générateur — logique PURE
 * (aucune dépendance Android/Compose), extraite de CreateScreen pour être
 * testable en JVM et réutilisable. Chaque fonction renvoie la chaîne à encoder
 * (round-trip génération -> re-scan) ou null si les champs obligatoires manquent.
 *
 * Note : les payloads utilisant Uri.encode (URL/SMS/e-mail/WhatsApp) restent
 * dans CreateScreen car ils dépendent d'Android ; ici, seule la logique pure.
 */
object CodePayloads {
    /** vCard 3.0. Renvoie null si ni nom ni société. Échappe ; , \ et retours ligne. */
    fun vCard(
        firstName: String, lastName: String, company: String, jobTitle: String,
        phoneE164: String?, email: String, address: String, postal: String,
        city: String, region: String, country: String, website: String,
    ): String? {
        if (firstName.isBlank() && lastName.isBlank() && company.isBlank()) return null
        val sb = StringBuilder()
        sb.append("BEGIN:VCARD\n")
        sb.append("VERSION:3.0\n")
        sb.append("N:${esc(lastName)};${esc(firstName)};;;\n")
        val fn = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
        if (fn.isNotBlank()) sb.append("FN:${esc(fn)}\n")
        if (company.isNotBlank()) sb.append("ORG:${esc(company)}\n")
        if (jobTitle.isNotBlank()) sb.append("TITLE:${esc(jobTitle)}\n")
        if (!phoneE164.isNullOrBlank()) sb.append("TEL:${esc(phoneE164)}\n")
        if (email.isNotBlank()) sb.append("EMAIL:${esc(email)}\n")
        if (listOf(address, city, region, postal, country).any { it.isNotBlank() }) {
            sb.append("ADR:;;${esc(address)};${esc(city)};${esc(region)};${esc(postal)};${esc(country)}\n")
        }
        if (website.isNotBlank()) sb.append("URL:${esc(website)}\n")
        sb.append("END:VCARD")
        return sb.toString()
    }
    /**
     * WIFI:S:<ssid>;T:<securityTag>;P:<pass>;H:true;;
     * securityTag suit la convention standard : "WPA", "WEP" ou "nopass".
     */
    fun wifi(ssid: String, securityTag: String, password: String, hidden: Boolean): String? {
        if (ssid.isBlank()) return null
        val sb = StringBuilder("WIFI:S:${wifiEsc(ssid)};T:$securityTag;")
        if (securityTag != "nopass" && password.isNotBlank()) {
            sb.append("P:${wifiEsc(password)};")
        }
        if (hidden) sb.append("H:true;")
        sb.append(";")
        return sb.toString()
    }
    /** geo:lat,lng — accepte la virgule décimale (convertie en point). */
    fun geo(lat: String, lng: String): String? {
        val la = lat.trim().replace(',', '.').toDoubleOrNull() ?: return null
        val lo = lng.trim().replace(',', '.').toDoubleOrNull() ?: return null
        return "geo:$la,$lo"
    }
    /** vEvent (iCalendar), heure LOCALE (pas de Z). Null sans titre ni date début. */
    fun vEvent(
        title: String, allDay: Boolean,
        startDate: String, startTime: String,
        endDate: String, endTime: String,
        location: String, description: String,
    ): String? {
        if (title.isBlank() || startDate.isBlank()) return null
        val dtStart = formatDateTime(startDate, startTime, allDay) ?: return null
        val sb = StringBuilder()
        sb.append("BEGIN:VEVENT\n")
        sb.append("SUMMARY:${esc(title)}\n")
        if (allDay) sb.append("DTSTART;VALUE=DATE:$dtStart\n") else sb.append("DTSTART:$dtStart\n")
        if (endDate.isNotBlank()) {
            formatDateTime(endDate, endTime, allDay)?.let { dtEnd ->
                if (allDay) sb.append("DTEND;VALUE=DATE:$dtEnd\n") else sb.append("DTEND:$dtEnd\n")
            }
        }
        if (location.isNotBlank()) sb.append("LOCATION:${esc(location)}\n")
        if (description.isNotBlank()) sb.append("DESCRIPTION:${esc(description)}\n")
        sb.append("END:VEVENT")
        return sb.toString()
    }
    /** Formate date (8 chiffres) + heure (HHMM) en AAAAMMJJ ou AAAAMMJJTHHmmSS. */
    fun formatDateTime(date: String, time: String, allDay: Boolean): String? {
        val d = date.filter { it.isDigit() }
        if (d.length != 8) return null
        if (allDay) return d
        val t = time.filter { it.isDigit() }
        val hhmm = if (t.length >= 4) t.substring(0, 4) else t.padEnd(4, '0')
        return "${d}T${hhmm}00"
    }
    /** Échappement vCard/vEvent : \ ; , et retours à la ligne. */
    private fun esc(v: String): String = v
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\n", "\\n")
    /** Échappement WIFI: (\ ; , : "). */
    private fun wifiEsc(v: String): String = v
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace(":", "\\:")
        .replace("\"", "\\\"")
}
