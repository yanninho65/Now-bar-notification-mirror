package com.yann.nowbarmirror.wear

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

/**
 * Reçoit les mises à jour de match envoyées par l'app téléphone via la
 * Wear Data Layer API (chemin "/match"), met à jour MatchScoreStore, puis
 * force un rafraîchissement immédiat de la complication plutôt que
 * d'attendre le prochain cycle système.
 *
 * Un DataItem avec `cleared=true` (envoyé quand Yann supprime le match
 * suivi depuis le téléphone) réinitialise MatchScoreStore à `null` — la
 * complication réaffiche alors "Aucun match" au lieu de rester bloquée
 * sur le dernier score connu.
 *
 * onDataChanged tourne déjà sur un thread de fond fourni par le système
 * (pas le thread principal), donc les appels bloquants comme
 * Tasks.await(...) pour décoder les Assets sont sans risque ici.
 */
class MatchListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        try {
            for (event in dataEvents) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                if (event.dataItem.uri.path != MATCH_PATH) continue

                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap

                if (dataMap.getBoolean("cleared", false)) {
                    MatchScoreStore.current = null
                    requestComplicationRefresh()
                    continue
                }

                val homeTeam = dataMap.getString("homeTeam") ?: continue
                val awayTeam = dataMap.getString("awayTeam") ?: continue
                val homeScore = dataMap.getString("homeScore")?.toIntOrNull()
                val awayScore = dataMap.getString("awayScore")?.toIntOrNull()
                // Repli SPORTS_DB : un téléphone avec une version de l'app
                // antérieure à l'introduction du tennis n'enverrait pas
                // cette clé.
                val apiSource = dataMap.getString("apiSource") ?: "SPORTS_DB"
                val currentSetHomeGames = if (dataMap.containsKey("currentSetHomeGames")) {
                    dataMap.getInt("currentSetHomeGames")
                } else {
                    null
                }
                val currentSetAwayGames = if (dataMap.containsKey("currentSetAwayGames")) {
                    dataMap.getInt("currentSetAwayGames")
                } else {
                    null
                }
                // Absent (null) pour un match sans crochet connu (TheSportsDB/
                // Live Tennis API, ou pas encore de but/set) — voir MatchScore.kt/scoreText.
                val lastScorer = dataMap.getString("lastScorer")
                val status = dataMap.getString("status").orEmpty()
                val kickoff = if (dataMap.containsKey("kickoffEpochMillis")) {
                    dataMap.getLong("kickoffEpochMillis")
                } else {
                    null
                }

                MatchScoreStore.current = MatchScore(
                    homeTeam = homeTeam,
                    awayTeam = awayTeam,
                    homeScore = homeScore,
                    awayScore = awayScore,
                    currentSetHomeGames = currentSetHomeGames,
                    currentSetAwayGames = currentSetAwayGames,
                    lastScorer = lastScorer,
                    status = status,
                    kickoffEpochMillis = kickoff,
                    // homeLogo/awayLogo (logos séparés venant de l'API) ont
                    // été retirés le 16/09/2026 : Sofascore est TOUJOURS la
                    // source de cette image désormais — voir MatchScore.kt.
                    notifImage = decodeImageAsset(dataMap, "notifImage"),
                    apiSource = apiSource
                )

                requestComplicationRefresh()
            }
        } finally {
            dataEvents.release()
        }
    }

    // Renommé (était decodeLogo) : sert maintenant aussi à décoder
    // notifImage, qui n'est pas un logo d'équipe.
    private fun decodeImageAsset(dataMap: DataMap, key: String): Bitmap? {
        val asset: Asset = dataMap.getAsset(key) ?: return null
        return try {
            val response = Tasks.await(Wearable.getDataClient(this).getFdForAsset(asset))
            response.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun requestComplicationRefresh() {
        val requester = ComplicationDataSourceUpdateRequester.create(
            context = applicationContext,
            complicationDataSourceComponent = ComponentName(
                applicationContext,
                ScoreComplicationService::class.java
            )
        )
        requester.requestUpdateAll()
    }

    companion object {
        private const val MATCH_PATH = "/match"
    }
}
