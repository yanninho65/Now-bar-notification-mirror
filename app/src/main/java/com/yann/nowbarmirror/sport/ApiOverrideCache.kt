package com.yann.nowbarmirror.sport

/**
 * Dernier résultat de sondage API pour l'override actuellement suivi par
 * [ApiOverrideFollowService] (un seul à la fois — voir sa doc). Un seul
 * emplacement (pas une Map) : seul l'override de la notification
 * actuellement active (repli Sofascore choisi ou dernière notif) est
 * jamais sondé, pour ne pas cumuler les quotas des API sondées en tâche
 * de fond (voir README, quotas Live Tennis API). [get] vérifie que la clé
 * de notif ET la source ET l'id de match correspondent encore à
 * l'override demandé, pour ne jamais appliquer un résultat obsolète
 * (ex. si l'override actif a changé entre deux appels à refresh()).
 */
object ApiOverrideCache {

    private data class Entry(
        val notifKey: String,
        val source: ApiSource,
        val matchId: String,
        val result: MatchResult
    )

    @Volatile
    private var entry: Entry? = null

    fun update(notifKey: String, source: ApiSource, matchId: String, result: MatchResult) {
        entry = Entry(notifKey, source, matchId, result)
    }

    fun get(override: SofascoreApiOverride): MatchResult? {
        val current = entry ?: return null
        return current.takeIf {
            it.notifKey == override.notifKey && it.source == override.source && it.matchId == override.matchId
        }?.result
    }

    fun clear() {
        entry = null
    }
}
