package com.yann.nowbarmirror.wear

import android.content.ComponentName
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

/**
 * Reçoit les mises à jour de "dernière notification" envoyées par l'app téléphone via la Wear
 * Data Layer API (chemin "/notification" — voir mobile/WatchNotificationSync.kt), alimente
 * NotificationInfoStore (décodage partagé, voir NotificationDataCodec), puis force un
 * rafraîchissement immédiat de NotificationComplicationService plutôt que d'attendre le prochain
 * cycle système.
 *
 * Service séparé de MatchListenerService (même principe que côté téléphone : deux
 * NotificationListenerServices indépendants, mirroring générique vs score sportif — voir
 * README, section "Deux accès notifications séparés"), plutôt que d'étendre celui-ci à un second
 * chemin de données.
 *
 * Ne couvre que les mises à jour REÇUES pendant que ce service tourne — si le processus watch a
 * été tué entre-temps (fréquent sur Wear OS), NotificationInfoStore repart de `null` et cet
 * onDataChanged ne se redéclenche pas tout seul pour un DataItem déjà synchronisé avant le
 * redémarrage. C'est NotificationComplicationService.fetchPersistedNotification (repli actif,
 * lit directement le DataItem déjà persistant côté Data Layer) qui couvre ce cas-là — voir sa
 * doc, ajouté 20/09/2026 (Yann : "il ne faut pas afficher que les dernières notifications reçues
 * depuis l'installation [...] c'est rare de se retrouver sans rien à afficher").
 *
 * onDataChanged tourne déjà sur un thread de fond fourni par le système (pas le thread
 * principal), donc les appels bloquants comme ceux de NotificationDataCodec sont sans risque ici
 * (même raisonnement que MatchListenerService).
 */
class NotificationDataListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        try {
            for (event in dataEvents) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                if (event.dataItem.uri.path != NOTIFICATION_PATH) continue

                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                NotificationInfoStore.current = NotificationDataCodec.decode(applicationContext, dataMap)
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
                NotificationComplicationService::class.java
            )
        )
        requester.requestUpdateAll()
    }

    companion object {
        private const val NOTIFICATION_PATH = "/notification"
    }
}
