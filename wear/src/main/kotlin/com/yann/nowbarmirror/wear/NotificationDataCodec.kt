package com.yann.nowbarmirror.wear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.Wearable

/**
 * Décodage partagé du DataMap envoyé par le téléphone sur le chemin "/notification" (voir
 * mobile/WatchNotificationSync.kt) — utilisé à la fois par NotificationDataListenerService
 * (réception en direct via onDataChanged) et par NotificationComplicationService.
 * fetchPersistedNotification (repli quand NotificationInfoStore est vide juste après un
 * redémarrage du processus watch, voir sa doc), pour ne décoder ce format qu'à un seul endroit.
 *
 * [decode] renvoie `null` pour un DataMap "cleared=true" (voir mobile/
 * WatchNotificationSync.sendCleared) — même sémantique que NotificationInfoStore.current == null,
 * donc les deux appelants peuvent juste affecter le résultat directement.
 */
object NotificationDataCodec {

    fun decode(context: Context, dataMap: DataMap): NotificationInfo? {
        if (dataMap.getBoolean("cleared", false)) return null

        return NotificationInfo(
            title = dataMap.getString("title").orEmpty(),
            text = dataMap.getString("text").orEmpty(),
            packageName = dataMap.getString("packageName").orEmpty(),
            image = decodeImageAsset(context, dataMap, "notifImage"),
            appIcon = decodeImageAsset(context, dataMap, "appIcon"),
            // NEW 21/09/2026, écran de détail — voir NotificationInfo's doc.
            detailLines = dataMap.getStringArrayList("detailLines") ?: emptyList(),
            actionLabels = dataMap.getStringArrayList("actionLabels") ?: emptyList(),
            entryKey = dataMap.getString("entryKey").orEmpty(),
            entryPostTimeMillis = dataMap.getLong("entryPostTimeMillis", -1L),
            kind = dataMap.getString("kind").orEmpty()
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
