package com.yann.nowbarmirror.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives both Data Layer items the phone pushes and refreshes the matching complication right
 * away instead of waiting for the next system cycle:
 * - "/match" (mobile/sport/WatchSync.kt) -> [MatchScoreStore] -> ScoreComplicationService
 *   ("Score en direct");
 * - "/notification" (mobile/WatchNotificationSync.kt) -> [NotificationInfoStore] ->
 *   NotificationComplicationService ("Notification").
 *
 * MERGED 23/09/2026 (audit — "limiter les doublons") from MatchListenerService and
 * NotificationDataListenerService, which were the same 30 lines twice with a different path. Still
 * one decoder per path (MatchDataCodec / NotificationDataCodec) and one store per complication —
 * only the plumbing is shared. A `cleared=true` item decodes to null, which resets the store, so
 * the complication shows its "nothing" state instead of staying stuck on the last value.
 *
 * Live pushes are applied unconditionally ([FreshStore.setLive]); a missed push (watch process
 * killed at the time) is caught up by the complications' own re-read of the persisted item
 * (PhoneDataLayer.readMatch/readNotification) on their next request.
 *
 * onDataChanged runs on a system-provided background thread, so the blocking asset decoding done
 * by the codecs (Tasks.await) is safe here.
 */
class PhoneDataListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        try {
            for (event in dataEvents) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                val timestamp = PhoneDataLayer.timestampOf(dataMap)
                when (event.dataItem.uri.path) {
                    PhoneDataLayer.MATCH_PATH -> {
                        MatchScoreStore.setLive(MatchDataCodec.decode(applicationContext, dataMap), timestamp)
                        PhoneDataLayer.requestComplicationRefresh(applicationContext, ScoreComplicationService::class.java)
                    }
                    PhoneDataLayer.NOTIFICATION_PATH -> {
                        NotificationInfoStore.setLive(NotificationDataCodec.decode(applicationContext, dataMap), timestamp)
                        PhoneDataLayer.requestComplicationRefresh(applicationContext, NotificationComplicationService::class.java)
                        // "Messages" hides the message already shown here (24/09/2026) — must follow it.
                        PhoneDataLayer.requestComplicationRefresh(applicationContext, MessagesComplicationService::class.java)
                    }
                    PhoneDataLayer.MESSAGES_PATH -> {
                        MessagesStore.setLive(MessagesDataCodec.decode(applicationContext, dataMap), timestamp)
                        PhoneDataLayer.requestComplicationRefresh(applicationContext, MessagesComplicationService::class.java)
                        MessagesStore.onChanged?.invoke()
                    }
                    PhoneDataLayer.SPORT_PATH -> {
                        // AUDIT 26/09/2026 — only the Sport screen uses it, and it re-reads the
                        // persisted item on resume: don't decode 20 matches' images while it's closed.
                        val onChanged = SportStore.onChanged ?: continue
                        SportStore.setLive(SportDataCodec.decode(applicationContext, dataMap), timestamp)
                        onChanged.invoke()
                    }
                }
            }
        } finally {
            dataEvents.release()
        }
    }
}
