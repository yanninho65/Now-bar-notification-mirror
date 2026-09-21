package com.yann.nowbarmirror.wear

import android.content.ComponentName
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

/**
 * Reçoit les mises à jour de match envoyées par l'app téléphone via la
 * Wear Data Layer API (chemin "/match"), met à jour MatchScoreStore
 * (décodage partagé, voir MatchDataCodec), puis force un rafraîchissement
 * immédiat de la complication plutôt que d'attendre le prochain cycle
 * système.
 *
 * Un DataItem avec `cleared=true` (envoyé quand Yann supprime le match
 * suivi depuis le téléphone) réinitialise MatchScoreStore à `null` — la
 * complication réaffiche alors "Aucun match" au lieu de rester bloquée
 * sur le dernier score connu.
 *
 * Ne couvre que les mises à jour REÇUES pendant que ce service tourne — si le processus watch a
 * été tué entre-temps (fréquent sur Wear OS, en particulier juste après un changement de cadran),
 * MatchScoreStore repart de `null` et cet onDataChanged ne se redéclenche pas tout seul pour un
 * DataItem déjà synchronisé avant le redémarrage. C'est ScoreComplicationService.fetchPersistedMatch
 * (repli actif, lit directement le DataItem déjà persistant côté Data Layer) qui couvre ce cas-là —
 * même principe que NotificationComplicationService.fetchPersistedNotification, voir sa doc.
 *
 * onDataChanged tourne déjà sur un thread de fond fourni par le système
 * (pas le thread principal), donc les appels bloquants comme ceux de
 * MatchDataCodec (Tasks.await(...) pour décoder les Assets) sont sans
 * risque ici.
 */
class MatchListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        try {
            for (event in dataEvents) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                if (event.dataItem.uri.path != MATCH_PATH) continue

                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                MatchScoreStore.current = MatchDataCodec.decode(applicationContext, dataMap)
                requestComplicationRefresh()
            }
        } finally {
            dataEvents.release()
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
