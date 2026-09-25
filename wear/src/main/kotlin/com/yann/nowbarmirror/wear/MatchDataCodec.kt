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

        return matchFrom(context, dataMap, dataMap, "notifImage", syncTimestamp)
    }

    /**
     * Match fields of [item] (keys written by mobile WatchSync.putMatch) — shared 25/09/2026 by
     * "/match" and each item of "/sport" (SportDataCodec), whose images are top-level assets of
     * [assetSource] under [imageKey]. Null when the team names are missing.
     */
    fun matchFrom(context: Context, item: DataMap, assetSource: DataMap, imageKey: String, syncTimestamp: Long): MatchScore? {
        val homeTeam = item.getString("homeTeam") ?: return null
        val awayTeam = item.getString("awayTeam") ?: return null
        return MatchScore(
            homeTeam = homeTeam,
            awayTeam = awayTeam,
            homeScore = item.getString("homeScore")?.toIntOrNull(),
            awayScore = item.getString("awayScore")?.toIntOrNull(),
            currentSetHomeGames = if (item.containsKey("currentSetHomeGames")) item.getInt("currentSetHomeGames") else null,
            currentSetAwayGames = if (item.containsKey("currentSetAwayGames")) item.getInt("currentSetAwayGames") else null,
            lastScorer = item.getString("lastScorer"),
            status = item.getString("status").orEmpty(),
            kickoffEpochMillis = if (item.containsKey("kickoffEpochMillis")) item.getLong("kickoffEpochMillis") else null,
            notifImage = PhoneDataLayer.decodeImageAsset(context, assetSource, imageKey),
            apiSource = item.getString("apiSource") ?: "SPORTS_DB",
            syncTimestamp = syncTimestamp
        )
    }
}
