package com.yann.nowbarmirror.wear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.Wearable

/**
 * Décodage partagé du DataMap envoyé par le téléphone sur le chemin "/match" (voir
 * mobile/sport/WatchSync.kt) — utilisé à la fois par MatchListenerService (réception en direct
 * via onDataChanged) et par ScoreComplicationService.fetchPersistedMatch (repli quand
 * MatchScoreStore est vide juste après un redémarrage du processus watch, voir sa doc — même
 * principe que NotificationDataCodec/NotificationComplicationService.fetchPersistedNotification),
 * pour ne décoder ce format qu'à un seul endroit.
 *
 * [decode] renvoie `null` pour un DataMap "cleared=true" (voir mobile/sport/WatchSync.sendCleared)
 * ou si les champs obligatoires (homeTeam/awayTeam) sont absents — même sémantique que
 * MatchScoreStore.current == null, donc les deux appelants peuvent juste affecter le résultat
 * directement.
 */
object MatchDataCodec {

    fun decode(context: Context, dataMap: DataMap): MatchScore? {
        if (dataMap.getBoolean("cleared", false)) return null

        val homeTeam = dataMap.getString("homeTeam") ?: return null
        val awayTeam = dataMap.getString("awayTeam") ?: return null

        return MatchScore(
            homeTeam = homeTeam,
            awayTeam = awayTeam,
            homeScore = dataMap.getString("homeScore")?.toIntOrNull(),
            awayScore = dataMap.getString("awayScore")?.toIntOrNull(),
            currentSetHomeGames = if (dataMap.containsKey("currentSetHomeGames")) {
                dataMap.getInt("currentSetHomeGames")
            } else {
                null
            },
            currentSetAwayGames = if (dataMap.containsKey("currentSetAwayGames")) {
                dataMap.getInt("currentSetAwayGames")
            } else {
                null
            },
            lastScorer = dataMap.getString("lastScorer"),
            status = dataMap.getString("status").orEmpty(),
            kickoffEpochMillis = if (dataMap.containsKey("kickoffEpochMillis")) {
                dataMap.getLong("kickoffEpochMillis")
            } else {
                null
            },
            notifImage = decodeImageAsset(context, dataMap, "notifImage"),
            // Repli SPORTS_DB : un téléphone avec une version de l'app antérieure à
            // l'introduction du tennis n'enverrait pas cette clé.
            apiSource = dataMap.getString("apiSource") ?: "SPORTS_DB"
        )
    }

    private fun decodeImageAsset(context: Context, dataMap: DataMap, key: String): Bitmap? {
        val asset: Asset = dataMap.getAsset(key) ?: return null
        return try {
            val response = Tasks.await(Wearable.getDataClient(context).getFdForAsset(asset))
            response.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
    }
}
