package com.yann.nowbarmirror.sport

import android.content.Context
import android.service.notification.StatusBarNotification
import android.util.LruCache
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.yann.nowbarmirror.MessagesWatchSync
import com.yann.nowbarmirror.NotificationImageExtractor
import com.yann.nowbarmirror.settings.SportAppsPrefs

/**
 * NEW 25/09/2026 — feeds the watch "Sport" screen (wear/SportActivity, opened by a tap on "Score en
 * direct"), same model as MessagesWatchSync / "/messages":
 * - "matches": DataMap list, EVERY active Sofascore notification (no "Dernière notif" filtering),
 *   most recent first: key, postTimeMillis, title (the notification's own title) + the match fields
 *   of WatchSync.putMatch; image as top-level asset "img_<index>";
 * - "apps": the sport apps of SportAppsPrefs with their counts (MessagesWatchSync.appEntries/putApps
 *   — same format and rules as the Messages app row), icons "icon_<package>";
 * - "followedKey": the match followed by "Score en direct" (SofascorePrefs CHOSEN and still active), else absent;
 * - "timestamp".
 * Deduped by a text signature; image assets cached per posting.
 */
object SportWatchSync {

    const val PATH = "/sport"
    private const val MAX_MATCHES = 20

    class Item(val sbn: StatusBarNotification, val title: String, val match: MatchResult)

    @Volatile
    private var lastSignature: String? = null
    private val imageAssets = LruCache<String, Any>(MAX_MATCHES * 2)   // Asset, or NO_IMAGE
    private val NO_IMAGE = Any()

    // AUDIT 26/09/2026 — "/sport" only feeds the watch Sport screen, yet changes on EVERY
    // Sofascore event of ANY match (17 active on a busy evening), and was sent urgent (immediate
    // Bluetooth wake of both devices each time). Now non-urgent (batched by the Data Layer)
    // except while the watch screen is open: the watch sends "/sportdetail/watching" on
    // resume/pause, which opens/closes an urgent window (capped, in case "closed" is lost).
    private const val WATCHING_MAX_MILLIS = 10 * 60 * 1000L

    @Volatile
    private var watchingUntil = 0L

    fun setWatching(open: Boolean) {
        watchingUntil = if (open) System.currentTimeMillis() + WATCHING_MAX_MILLIS else 0L
        // Re-put on open even if unchanged: a pending non-urgent item must reach the watch now.
        if (open) lastSignature = null
    }

    private fun isWatching() = System.currentTimeMillis() < watchingUntil

    /** [items] = the active Sofascore matches; call on the listener's main thread. */
    fun sync(context: Context, active: Array<StatusBarNotification>?, items: List<Item>, followedKey: String?) {
        val matches = items.sortedByDescending { it.sbn.postTime }.take(MAX_MATCHES)
        val followed = followedKey?.takeIf { key -> matches.any { it.sbn.key == key } }
        val apps = MessagesWatchSync.appEntries(context, active, SportAppsPrefs.getOrdered(context))

        val signature = matches.joinToString("\u0001") {
            listOf(it.sbn.key, it.sbn.postTime, it.title, it.match, muteActionOf(it.sbn.notification) != null).joinToString("\u0003")
        } + "\u0004" + (followed ?: "") + "\u0004" + MessagesWatchSync.appsSignature(apps)
        if (signature == lastSignature) return

        try {
            val request = PutDataMapRequest.create(PATH).apply {
                val list = ArrayList<DataMap>()
                matches.forEachIndexed { index, item ->
                    list.add(DataMap().apply {
                        putString("key", item.sbn.key)
                        putLong("postTimeMillis", item.sbn.postTime)
                        putString("title", item.title)
                        // 25/09/2026: shows the watch's bell-off button ("/sportdetail/mute").
                        putBoolean("canMute", muteActionOf(item.sbn.notification) != null)
                        WatchSync.putMatch(this, item.match)
                    })
                    imageAsset(context, item.sbn)?.let { dataMap.putAsset("img_$index", it) }
                }
                dataMap.putDataMapArrayList("matches", list)
                followed?.let { dataMap.putString("followedKey", it) }
                val iconPackages = LinkedHashSet<String>()
                MessagesWatchSync.putApps(dataMap, apps, iconPackages)
                MessagesWatchSync.putIcons(context, dataMap, iconPackages)
                dataMap.putLong("timestamp", System.currentTimeMillis())
            }.asPutDataRequest().let { if (isWatching()) it.setUrgent() else it }
            Wearable.getDataClient(context).putDataItem(request)
            lastSignature = signature
        } catch (_: Throwable) {
            // Best effort, like the other watch syncs.
        }
    }

    /**
     * Sofascore's "Mettre l'événement en silencieux" action (semantic MUTE, else by label), or null.
     * Fired by SofascoreNotificationListenerService.muteFromWatch, which then deletes the notification.
     */
    fun muteActionOf(notification: android.app.Notification): android.app.Notification.Action? =
        notification.actions.orEmpty().firstOrNull { action ->
            action.actionIntent != null && (
                (android.os.Build.VERSION.SDK_INT >= 28 && action.semanticAction == android.app.Notification.Action.SEMANTIC_ACTION_MUTE) ||
                    action.title?.toString()?.lowercase()?.let { "silenc" in it || "mute" in it || "sourdine" in it } == true
                )
        }

    private fun imageAsset(context: Context, sbn: StatusBarNotification): Asset? {
        val cacheKey = "${sbn.key}@${sbn.postTime}"
        imageAssets.get(cacheKey)?.let { return it as? Asset }
        val asset = NotificationImageExtractor.extract(context, sbn)?.let { WatchSync.bitmapToAsset(it) }
        imageAssets.put(cacheKey, asset ?: NO_IMAGE)
        return asset
    }
}
