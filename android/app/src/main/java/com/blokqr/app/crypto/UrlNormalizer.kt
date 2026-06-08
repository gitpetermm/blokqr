package com.blokqr.app.crypto

import android.net.Uri
import java.security.MessageDigest
import java.util.Locale

/**
 * Normalisation d'URL et génération de préfixes de hash, EN LOCAL sur l'appareil.
 *
 * Reproduit fidèlement la logique du backend (app/security/url_normalize.py) afin
 * que les préfixes calculés ici correspondent à ceux de la base de réputation.
 *
 * Pour le palier de réputation k-anonyme : on n'envoie au serveur que des
 * préfixes de 4 octets ; l'URL ne quitte jamais l'appareil.
 */
object UrlNormalizer {

    private const val HASH_PREFIX_BYTES = 4

    private val TRACKING_PARAMS = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "gclid", "fbclid", "mc_eid", "mc_cid", "igshid", "ref", "ref_src",
    )

    data class Fingerprint(
        val normalized: String,
        val host: String,
        val expressionPrefixes: List<String>,
    )

    fun canonicalize(url: String): String {
        val uri = Uri.parse(url.trim())
        val scheme = (uri.scheme ?: "http").lowercase(Locale.ROOT)
        val host = (uri.host ?: "").lowercase(Locale.ROOT).trimEnd('.')
        val port = uri.port
        val netloc = if (port > 0 &&
            !((scheme == "http" && port == 80) || (scheme == "https" && port == 443))
        ) "$host:$port" else host

        val path = uri.path?.ifEmpty { "/" } ?: "/"

        val kept = uri.encodedQuery
            ?.split("&")
            ?.filter { it.isNotEmpty() }
            ?.filter { pair -> pair.substringBefore("=").lowercase(Locale.ROOT) !in TRACKING_PARAMS }
            ?.sorted()
            ?.joinToString("&")
            ?: ""

        val query = if (kept.isEmpty()) "" else "?$kept"
        return "$scheme://$netloc$path$query"
    }

    private fun hostPathExpressions(normalized: String): List<String> {
        val uri = Uri.parse(normalized)
        val host = uri.host ?: ""
        val path = uri.path?.ifEmpty { "/" } ?: "/"

        val labels = host.split(".")
        val hosts = LinkedHashSet<String>()
        hosts.add(host)
        if (labels.size > 2) hosts.add(host)
        val start = maxOf(0, labels.size - 5)
        for (i in start until labels.size - 1) {
            hosts.add(labels.subList(i, labels.size).joinToString("."))
        }

        val segments = path.split("/").filter { it.isNotEmpty() }
        val paths = LinkedHashSet<String>()
        paths.add("/")
        var acc = ""
        for (seg in segments.take(5)) {
            acc += "/$seg"
            paths.add(acc)
        }
        paths.add(path)

        val expressions = sortedSetOf<String>()
        for (h in hosts) for (p in paths) expressions.add("$h$p")
        return expressions.toList()
    }

    fun fingerprint(url: String): Fingerprint {
        val normalized = canonicalize(url)
        val host = (Uri.parse(normalized).host ?: "").lowercase(Locale.ROOT)
        val sha = MessageDigest.getInstance("SHA-256")
        val prefixes = sortedSetOf<String>()
        for (expr in hostPathExpressions(normalized)) {
            sha.reset()
            val digest = sha.digest(expr.toByteArray(Charsets.UTF_8))
            prefixes.add(digest.copyOfRange(0, HASH_PREFIX_BYTES).toHex())
        }
        return Fingerprint(normalized, host, prefixes.toList())
    }

    /** SHA-256 complet (hex) d'une expression — pour la correspondance finale locale. */
    fun fullExpressionHash(expression: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(expression.toByteArray(Charsets.UTF_8))
        return digest.toHex()
    }

    fun expressionsOf(url: String): List<String> = hostPathExpressions(canonicalize(url))

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
