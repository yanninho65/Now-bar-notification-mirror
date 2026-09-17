package com.yann.nowbarmirror.sport

import android.content.Context

/**
 * Stocke la clé Live Tennis API en SharedPreferences LOCALES au
 * téléphone, saisie directement dans l'app (écran principal, visible
 * une fois "Live Tennis API" sélectionné) — plus jamais en dur dans le
 * code source, puisque le dépôt GitHub est PUBLIC. Remplace la
 * constante `API_KEY` qui vivait avant dans `LiveTennisApi.kt`.
 *
 * Pas de chiffrement ici (SharedPreferences classiques, pas
 * EncryptedSharedPreferences) : suffisant pour l'objectif visé (ne plus
 * exposer la clé dans le code source public), mais un téléphone rooté
 * pourrait encore la lire sur le disque. Étape suivante si besoin d'aller
 * plus loin : androidx.security (EncryptedSharedPreferences), pas fait
 * ici pour ne pas ajouter une dépendance supplémentaire sans demande
 * explicite.
 */
object TennisApiKeyPrefs {

    private const val PREFS_NAME = "tennis_api_key"
    private const val KEY_API_KEY = "api_key"

    /** null si aucune clé n'a encore été enregistrée (ou si elle est vide). */
    fun get(context: Context): String? =
        prefs(context).getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }

    fun save(context: Context, apiKey: String) {
        prefs(context).edit().putString(KEY_API_KEY, apiKey.trim()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
