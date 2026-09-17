package com.yann.nowbarmirror.sport

import android.content.Context
import org.json.JSONObject

/**
 * Un override = pour UNE notification Sofascore précise (identifiée par
 * sa clé `StatusBarNotification.getKey()`, comme `SofascorePrefs.chosenKey`
 * — voir SofascoreNotificationListenerService), affiche le score et/ou la
 * période venant d'une autre API (TheSportsDB ou Live Tennis API) plutôt
 * que ceux déduits du texte de la notif Sofascore elle-même. [showScore]
 * et [showPeriod] sont indépendants (case à cocher chacun dans
 * MainActivity — Yann peut vouloir l'un, l'autre, ou les deux) : au moins
 * un des deux doit être vrai, sinon l'override n'a aucun effet (vérifié
 * côté MainActivity avant l'enregistrement). Les noms d'équipe et
 * l'image/logo restent TOUJOURS ceux de Sofascore, quel que soit cet
 * override — voir SofascoreNotificationListenerService.refresh()/
 * applyOverride. [label] est juste un libellé d'affichage (le titre du
 * match trouvé dans l'API, ex. "Alcaraz vs Sinner"), pas une clé.
 */
data class SofascoreApiOverride(
    val notifKey: String,
    val source: ApiSource,
    val matchId: String,
    val label: String,
    val showScore: Boolean,
    val showPeriod: Boolean
)

/**
 * Persiste, en SharedPreferences, la table notifKey -> [SofascoreApiOverride]
 * — un blob JSON unique (plutôt qu'une entrée par notifKey) car une clé de
 * notification (`StatusBarNotification.getKey()`, ex.
 * "0|com.sofascore.results|12345|null|10123") n'est pas un identifiant
 * simple à sûrement utiliser comme suffixe de nom de préférence.
 *
 * Nettoyage automatique : un override disparaît quand la notification
 * Sofascore correspondante disparaît (voir
 * SofascoreNotificationListenerService.onNotificationRemoved, demandé par
 * Yann le 16/09/2026) — rien d'autre ne nettoie cette table (pas de TTL,
 * pas de purge au démarrage).
 */
object SofascoreApiOverridePrefs {

    private const val PREFS_NAME = "sofascore_api_overrides"
    private const val KEY_BLOB = "overrides"

    fun get(context: Context, notifKey: String): SofascoreApiOverride? = readAll(context)[notifKey]

    fun save(context: Context, override: SofascoreApiOverride) {
        val all = readAll(context).toMutableMap()
        all[override.notifKey] = override
        writeAll(context, all)
    }

    fun remove(context: Context, notifKey: String) {
        val all = readAll(context).toMutableMap()
        if (all.remove(notifKey) != null) writeAll(context, all)
    }

    private fun readAll(context: Context): Map<String, SofascoreApiOverride> {
        val raw = prefs(context).getString(KEY_BLOB, null) ?: return emptyMap()
        return try {
            val json = JSONObject(raw)
            json.keys().asSequence().mapNotNull { key ->
                val o = json.optJSONObject(key) ?: return@mapNotNull null
                val source = ApiSource.values().firstOrNull { it.name == o.optString("source") }
                    ?: return@mapNotNull null
                key to SofascoreApiOverride(
                    notifKey = key,
                    source = source,
                    matchId = o.optString("matchId"),
                    label = o.optString("label"),
                    showScore = o.optBoolean("showScore", true),
                    showPeriod = o.optBoolean("showPeriod", true)
                )
            }.toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun writeAll(context: Context, all: Map<String, SofascoreApiOverride>) {
        val json = JSONObject()
        all.forEach { (key, o) ->
            json.put(
                key,
                JSONObject().apply {
                    put("source", o.source.name)
                    put("matchId", o.matchId)
                    put("label", o.label)
                    put("showScore", o.showScore)
                    put("showPeriod", o.showPeriod)
                }
            )
        }
        prefs(context).edit().putString(KEY_BLOB, json.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
