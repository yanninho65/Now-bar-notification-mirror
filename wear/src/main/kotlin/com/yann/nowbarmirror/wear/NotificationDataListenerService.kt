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
 * Reçoit les mises à jour de "dernière notification" envoyées par l'app téléphone via la Wear
 * Data Layer API (chemin "/notification" — voir mobile/WatchNotificationSync.kt), alimente
 * NotificationInfoStore, puis force un rafraîchissement immédiat de
 * NotificationComplicationService plutôt que d'attendre le prochain cycle système.
 *
 * Service séparé de MatchListenerService (même principe que côté téléphone : deux
 * NotificationListenerServices indépendants, mirroring générique vs score sportif — voir
 * README, section "Deux accès notifications séparés"), plutôt que d'étendre celui-ci à un second
 * chemin de données.
 *
 * Un DataItem avec `cleared=true` (envoyé quand plus aucune notification éligible n'est mirorée
 * côté téléphone, voir mobile/WatchNotificationSync.sendCleared) réinitialise
 * NotificationInfoStore à `null` — la complication réaffiche alors son état "aucune notification"
 * au lieu de rester bloquée sur la dernière connue.
 *
 * onDataChanged tourne déjà sur un thread de fond fourni par le système (pas le thread
 * principal), donc les appels bloquants comme Tasks.await(...) pour décoder les Assets sont sans
 * risque ici (même raisonnement que MatchListenerService).
 */
class NotificationDataListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        try {
            for (event in dataEvents) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                if (event.dataItem.uri.path != NOTIFICATION_PATH) continue

                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap

                if (dataMap.getBoolean("cleared", false)) {
                    NotificationInfoStore.current = null
                    requestComplicationRefresh()
                    continue
                }

                val title = dataMap.getString("title").orEmpty()
                val text = dataMap.getString("text").orEmpty()
                val packageName = dataMap.getString("packageName").orEmpty()

                NotificationInfoStore.current = NotificationInfo(
                    title = title,
                    text = text,
                    packageName = packageName,
                    image = decodeImageAsset(dataMap, "notifImage"),
                    appIcon = decodeImageAsset(dataMap, "appIcon")
                )

                requestComplicationRefresh()
            }
        } finally {
            dataEvents.release()
        }
    }

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
                NotificationComplicationService::class.java
            )
        )
        requester.requestUpdateAll()
    }

    companion object {
        private const val NOTIFICATION_PATH = "/notification"
    }
}
