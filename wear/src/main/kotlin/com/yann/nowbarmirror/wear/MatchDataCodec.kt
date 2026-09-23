package com.yann.nowbarmirror.wear

import android.content.Context
import com.google.android.gms.wearable.DataMap

/**
 * Décodage partagé du DataMap envoyé par le téléphone sur le chemin "/match" (voir
 * mobile/sport/WatchSync.kt) — utilisé à la fois par PhoneDataListenerService (réception en direct
 * via onDataChanged) et par PhoneDataLayer.readMatch (relecture de l'item persistant, quand
 * MatchScoreStore est vide juste après un redémarrage du processus watch, voir sa doc — même
 * principe que NotificationDataCodec/PhoneDataLayer.readNotification),
 * pour ne décoder ce format qu'à un seul endroit.
 *
 * [decode] renvoie `null` pour un DataMap "cleared=true" (voir mobile/sport/WatchSync.sendCleared)
 * ou si les champs obligatoires (homeTeam/awayTeam) sont absents — même sémantique que
 * MatchScoreStore.current == null, donc les deux appelants peuvent juste affecter le résultat
 * directement.
 */
object MatchDataCodec {

    /**
     * [reuse] (AUDIT 23/09/2026): the value already in memory — returned as-is, without decoding
     * any image asset again, when it was built from this exact send (same phone timestamp). That's
     * the common case for the complication's re-read of the persisted item on every request.
     */
    fun decode(context: Context, dataMap: DataMap, reuse: MatchScore? = null): MatchScore? {
        if (dataMap.getBoolean("cleared", false)) return null
        val syncTimestamp = PhoneDataLayer.timestampOf(dataMap)
        if (reuse != null && syncTimestamp != 0L && reuse.syncTimestamp == syncTimestamp) return reuse

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
            notifImage = PhoneDataLayer.decodeImageAsset(context, dataMap, "notifImage"),
            // Repli SPORTS_DB : un téléphone avec une version de l'app antérieure à
            // l'introduction du tennis n'enverrait pas cette clé.
            apiSource = dataMap.getString("apiSource") ?: "SPORTS_DB",
            syncTimestamp = syncTimestamp
        )
    }
}
