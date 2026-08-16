package com.blokqr.app.ui.result
import androidx.annotation.StringRes
import com.blokqr.app.R
import com.blokqr.app.model.VerifiedResult
/**
 * Catégorisation LISIBLE d'une menace : à partir des codes de signaux déjà
 * calculés par le serveur, on déduit LOCALEMENT une catégorie unique et
 * compréhensible (ex. « Hameçonnage bancaire », « Faux colis / livraison »).
 * 100 % local, aucun appel réseau, aucun changement serveur.
 *
 * Principe : chaque catégorie est décrite par un ensemble de codes déclencheurs.
 * On parcourt les catégories par ORDRE DE PRIORITÉ (de la plus spécifique/grave
 * à la plus générale) et on retourne la première dont au moins un code
 * déclencheur est présent dans les signaux. Si rien ne correspond, on retourne
 * null (aucune étiquette affichée — on ne sur-interprète pas).
 *
 * L'étiquette n'est proposée que pour un contenu réellement à risque : la
 * décision d'affichage (verdict dangereux/suspect) est prise par l'appelant.
 */
enum class ThreatCategory(@param:StringRes val labelRes: Int, val triggers: Set<String>) {
    // Hameçonnage de connexion / usurpation de marque (le plus parlant en premier).
    CREDENTIAL_PHISHING(
        R.string.threat_cat_credential_phishing,
        setOf("login_impersonation", "password_form", "qr_login_endpoint", "embedded_credentials"),
    ),
    BRAND_IMPERSONATION(
        R.string.threat_cat_brand_impersonation,
        setOf("brand_combosquatting", "lookalike_domain", "idn_homoglyph", "punycode_idn", "non_ascii_host"),
    ),
    // Arnaques thématiques fréquentes (paiement, colis).
    FAKE_PAYMENT(
        R.string.threat_cat_fake_payment,
        setOf("crypto_payment"),
    ),
    // Redirection trompeuse / masquage.
    DECEPTIVE_REDIRECT(
        R.string.threat_cat_deceptive_redirect,
        setOf(
            "open_redirect_param", "destination_changed", "destination_diverges_consensus",
            "cloaking_detected", "antibot_gating", "js_redirect", "meta_refresh_redirect",
            "url_shortener", "long_redirect_chain", "too_many_redirects", "redirect_loop",
        ),
    ),
    // Cible dangereuse (fichier exécutable, TLS affaibli, port inhabituel).
    DANGEROUS_TARGET(
        R.string.threat_cat_dangerous_target,
        setOf("executable_target", "tls_downgrade", "unusual_port", "ip_literal_host", "capability_url"),
    ),
    // Domaine douteux (jeune, entropie élevée, TLD à risque).
    SUSPICIOUS_DOMAIN(
        R.string.threat_cat_suspicious_domain,
        setOf("young_domain", "newly_seen_domain", "high_entropy_domain", "high_risk_tld", "excessive_subdomains"),
    ),
    // Wi-Fi piégé (QR de configuration réseau).
    RISKY_WIFI(
        R.string.threat_cat_risky_wifi,
        setOf("open_wifi", "hidden_wifi"),
    ) ;
    companion object {
        /**
         * Catégorie déduite des signaux du rapport, ou null si aucun code connu.
         * La menace « réputation malveillante » (codes ti_ et google_web_risk) n'est
         * pas une catégorie de MODE OPÉRATOIRE : on ne l'affiche pas ici (le
         * verdict s'en charge déjà). L'ordre de l'enum sert de priorité.
         */
        fun of(result: VerifiedResult): ThreatCategory? {
            val codes = result.report.signals.map { it.code }.toSet()
            if (codes.isEmpty()) return null
            return entries.firstOrNull { cat -> cat.triggers.any { it in codes } }
        }
    }
}
