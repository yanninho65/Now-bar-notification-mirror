package com.yann.nowbarmirror.sport

import android.content.Context

/**
 * Persiste le choix de Yann pour le repli Sofascore (voir
 * SofascoreNotificationListenerService) : soit [Mode.LATEST] ("dernière
 * notif", comportement automatique par défaut), soit [Mode.CHOSEN] — un
 * match précis, choisi à la main parmi les notifications actives, identifié
 * par `StatusBarNotification.getKey` de sa notification (stable tant que la
 * notif reste active, y compris à travers ses mises à jour en place — voir
 * SofascoreNotificationListenerService pour pourquoi ce n'est PLUS le
 * `groupKey` système). [chosenLabel] n'est qu'un libellé pour l'affichage
 * (ex. "Real Madrid - Rayo Vallecano"), pas une clé.
 */
object SofascorePrefs {

    enum class Mode { LATEST, CHOSEN }

    private const val PREFS_NAME = "sofascore_fallback"

    fun saveLatest(context: Context) {
        prefs(context).edit().apply {
            putString("mode", Mode.LATEST.name)
            remove("chosenKey")
            remove("chosenLabel")
        }.apply()
    }

    fun saveChosen(context: Context, key: String, label: String) {
        prefs(context).edit().apply {
            putString("mode", Mode.CHOSEN.name)
            putString("chosenKey", key)
            putString("chosenLabel", label)
        }.apply()
    }

    fun loadMode(context: Context): Mode =
        prefs(context).getString("mode", null)?.let { name ->
            Mode.values().firstOrNull { it.name == name }
        } ?: Mode.LATEST

    fun loadChosenKey(context: Context): String? = prefs(context).getString("chosenKey", null)

    fun loadChosenLabel(context: Context): String? = prefs(context).getString("chosenLabel", null)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
