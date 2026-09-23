package com.yann.nowbarmirror.wear

import android.content.Context
import com.google.android.gms.wearable.DataMap

/**
 * Décodage partagé du DataMap envoyé par le téléphone sur le chemin "/notification" (voir
 * mobile/WatchNotificationSync.kt) — utilisé à la fois par PhoneDataListenerService
 * (réception en direct via onDataChanged) et par NotificationComplicationService.
 * via PhoneDataLayer.readNotification (relecture de l'item persistant, quand NotificationInfoStore est vide ou périmé après un
 * redémarrage du processus watch, voir sa doc), pour ne décoder ce format qu'à un seul endroit.
 *
 * [decode] renvoie `null` pour un DataMap "cleared=true" (voir mobile/
 * WatchNotificationSync.sendCleared) — même sémantique que NotificationInfoStore.current == null,
 * donc les deux appelants peuvent juste affecter le résultat directement.
 */
object NotificationDataCodec {

    /**
     * [reuse] (AUDIT 23/09/2026): the value already in memory — returned as-is, without decoding
     * any image asset again, when it was built from this exact send (same phone timestamp). That's
     * the common case for the complication's re-read of the persisted item on every request.
     */
    fun decode(context: Context, dataMap: DataMap, reuse: NotificationInfo? = null): NotificationInfo? {
        if (dataMap.getBoolean("cleared", false)) return null
        val syncTimestamp = PhoneDataLayer.timestampOf(dataMap)
        if (reuse != null && syncTimestamp != 0L && reuse.syncTimestamp == syncTimestamp) return reuse

        return NotificationInfo(
            title = dataMap.getString("title").orEmpty(),
            text = dataMap.getString("text").orEmpty(),
            packageName = dataMap.getString("packageName").orEmpty(),
            image = PhoneDataLayer.decodeImageAsset(context, dataMap, "notifImage"),
            appIcon = PhoneDataLayer.decodeImageAsset(context, dataMap, "appIcon"),
            // NEW 21/09/2026, écran de détail — voir NotificationInfo's doc.
            detailLines = dataMap.getStringArrayList("detailLines") ?: emptyList(),
            actionLabels = dataMap.getStringArrayList("actionLabels") ?: emptyList(),
            entryKey = dataMap.getString("entryKey").orEmpty(),
            entryPostTimeMillis = dataMap.getLong("entryPostTimeMillis", -1L),
            kind = dataMap.getString("kind").orEmpty(),
            syncTimestamp = syncTimestamp
        )
    }
}
