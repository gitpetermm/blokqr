package com.blokqr.app.data
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
/**
 * Recherche OPT-IN du nom d'un produit via Open Food Facts (base ouverte, sans
 * clé). N'est appelée QUE si l'utilisateur a activé le réglage dédié.
 *
 * Seul le GTIN (identifiant produit, donnée NON personnelle) est transmis :
 * aucune donnée utilisateur, aucun traçage. TLS système (pas d'épinglage : la
 * racine de confiance de BlokQR ne concerne que l'API BlokQR). Tolérant aux
 * pannes : toute erreur/absence -> null (l'appelant n'affiche alors rien).
 *
 * CACHE MÉMOIRE : chaque GTIN interrogé (succès COMME échec) est mémorisé pour
 * la session, afin de ne PAS relancer d'appel réseau lorsqu'un même produit est
 * rescanné (rescans fréquents en rayon). Le cache est borné (MAX_CACHE) et vidé
 * en bloc au-delà — suffisant et sans dépendance (pas de LRU à maintenir). Il
 * ne persiste pas sur disque : il disparaît à la fermeture du processus.
 */
object OpenFoodFactsClient {
    /** Nom et marque du produit (l'un ou l'autre peut être null). */
    data class ProductName(val name: String?, val brand: String?)
    // Enveloppe permettant de mémoriser aussi les ABSENCES (valeur null) — un
    // ConcurrentHashMap n'accepte pas de valeur null, d'où ce petit conteneur.
    private class Cached(val value: ProductName?)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }
    private val json = Json { ignoreUnknownKeys = true }
    /**
     * Renvoie le nom/marque du produit, ou null (introuvable/erreur). Consulte
     * d'abord le cache mémoire ; en cas d'absence, interroge Open Food Facts puis
     * mémorise le résultat (succès ou échec) pour éviter tout réappel identique.
     */
    suspend fun lookup(gtin: String): ProductName? {
        val digits = gtin.filter { it.isDigit() }
        if (digits.length < 8) return null
        cache[digits]?.let { return it.value }      // succès rescan : zéro réseau
        val result = fetch(digits)
        remember(digits, result)
        return result
    }
    private fun remember(digits: String, value: ProductName?) {
        // Bornage simple sans LRU : au-delà du plafond, on repart à vide.
        if (cache.size >= MAX_CACHE) cache.clear()
        cache[digits] = Cached(value)
    }
    private suspend fun fetch(digits: String): ProductName? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://world.openfoodfacts.org/api/v2/product/$digits.json" +
                "?fields=product_name,brands"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "BlokQR-Android/2.0.0")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    null
                } else {
                    val payload = resp.body?.string()
                    if (payload == null) {
                        null
                    } else {
                        val parsed = json.decodeFromString<OffResponse>(payload)
                        if (parsed.status == 1) {
                            ProductName(
                                name = parsed.product?.productName?.takeIf { it.isNotBlank() },
                                brand = parsed.product?.brands?.takeIf { it.isNotBlank() },
                            )
                        } else {
                            null
                        }
                    }
                }
            }
        }.getOrNull()
    }
    private const val MAX_CACHE = 200
    @Serializable
    private data class OffResponse(
        val status: Int = 0,
        val product: OffProduct? = null,
    )
    @Serializable
    private data class OffProduct(
        @SerialName("product_name") val productName: String? = null,
        val brands: String? = null,
    )
}
