package com.yann.nowbarmirror.wear

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataItemBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.TimeUnit

/**
 * Everything the watch side shares about the two Data Layer items the phone pushes ("/match" from
 * mobile/sport/WatchSync.kt, "/notification" from mobile/WatchNotificationSync.kt) — AUDIT
 * 23/09/2026, harmonization + battery. Before this file, the same code existed in several copies:
 * - the "re-read the persisted DataItem" fallback: ScoreComplicationService,
 *   NotificationComplicationService and NotificationDetailActivity each had their own copy, each
 *   fetching EVERY DataItem on the watch (getDataItems()) and filtering by path afterwards;
 * - asset decoding: MatchDataCodec and NotificationDataCodec;
 * - "ask the watch face to refresh this complication": MatchListenerService and
 *   NotificationDataListenerService (now merged into PhoneDataListenerService).
 *
 * The "Score en direct" complication also didn't get the freshness fixes the "Notification" one
 * received (README "Known issue": it only re-read the persisted item when its in-memory cache was
 * empty, and could regress to an older value) — both now go through [readMatch]/[readNotification]
 * and [FreshStore], same rules for both.
 */
object PhoneDataLayer {

    const val MATCH_PATH = "/match"
    const val NOTIFICATION_PATH = "/notification"
    const val MESSAGES_PATH = "/messages"   // NEW 24/09/2026, "Messages" complication
    const val SPORT_PATH = "/sport"         // NEW 25/09/2026, "Sport" screen (tap on "Score en direct")
    private const val FETCH_TIMEOUT_SECONDS = 2L

    /** Result of re-reading a persisted item: [Success] (value may be null = cleared/never sent) or [Failed] (the local read itself failed — conclude nothing). */
    sealed class Read<out T> {
        data class Success<T>(val value: T?) : Read<T>()
        object Failed : Read<Nothing>()
    }

    /** The phone stamps every put with System.currentTimeMillis() ("timestamp") — used as the freshness key by [FreshStore]. */
    fun timestampOf(dataMap: DataMap): Long = dataMap.getLong("timestamp", 0L)

    /**
     * Re-reads the persisted "/notification" item and applies it through
     * [NotificationInfoStore.updateIfNotOlder] (never regresses to an older value). Images are only
     * decoded when the item differs from what's already in memory (see NotificationDataCodec).
     * Blocking but purely local (Play Services, no network) — call off the main thread.
     */
    fun readNotification(context: Context): Read<NotificationInfo> =
        read(context, NOTIFICATION_PATH) { dataMap ->
            val info = dataMap?.let { NotificationDataCodec.decode(context, it, reuse = NotificationInfoStore.current) }
            NotificationInfoStore.updateIfNotOlder(info, dataMap?.let(::timestampOf) ?: 0L)
        }

    /** Same as [readNotification], for "/match" and [MatchScoreStore]. */
    fun readMatch(context: Context): Read<MatchScore> =
        read(context, MATCH_PATH) { dataMap ->
            val match = dataMap?.let { MatchDataCodec.decode(context, it, reuse = MatchScoreStore.current) }
            MatchScoreStore.updateIfNotOlder(match, dataMap?.let(::timestampOf) ?: 0L)
        }

    /** Same as [readNotification], for "/messages" and [MessagesStore]. */
    fun readMessages(context: Context): Read<MessageList> =
        read(context, MESSAGES_PATH) { dataMap ->
            val list = dataMap?.let { MessagesDataCodec.decode(context, it, reuse = MessagesStore.current) }
            MessagesStore.updateIfNotOlder(list, dataMap?.let(::timestampOf) ?: 0L)
        }

    /** Same as [readNotification], for "/sport" and [SportStore]. */
    fun readSport(context: Context): Read<SportList> =
        read(context, SPORT_PATH) { dataMap ->
            val list = dataMap?.let { SportDataCodec.decode(context, it, reuse = SportStore.current) }
            SportStore.updateIfNotOlder(list, dataMap?.let(::timestampOf) ?: 0L)
        }

    private fun <T> read(context: Context, path: String, apply: (DataMap?) -> T?): Read<T> {
        return try {
            // Path-specific query (any node) instead of fetching every DataItem on the watch.
            val uri = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(path).build()
            val items: DataItemBuffer = Tasks.await(
                Wearable.getDataClient(context).getDataItems(uri),
                FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS
            )
            try {
                val item = items.firstOrNull { it.uri.path == path }
                Read.Success(apply(item?.let { DataMapItem.fromDataItem(it).dataMap }))
            } finally {
                items.release()
            }
        } catch (e: Exception) {
            Read.Failed
        }
    }

    /** Shared by MatchDataCodec/NotificationDataCodec. Blocking (Tasks.await) — only ever called off the main thread. */
    fun decodeImageAsset(context: Context, dataMap: DataMap, key: String): Bitmap? {
        val asset: Asset = dataMap.getAsset(key) ?: return null
        return try {
            val response = Tasks.await(Wearable.getDataClient(context).getFdForAsset(asset))
            response.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
    }

    /** Asks the watch face to re-request [serviceClass]'s complication(s) right away. */
    fun requestComplicationRefresh(context: Context, serviceClass: Class<*>) {
        ComplicationDataSourceUpdateRequester.create(
            context = context,
            complicationDataSourceComponent = ComponentName(context, serviceClass)
        ).requestUpdateAll()
    }
}

/**
 * In-memory "latest value received from the phone" with a freshness guard — shared by
 * [MatchScoreStore] and [NotificationInfoStore] (AUDIT 23/09/2026; the guard used to exist only
 * for notifications, comparing entryPostTimeMillis, which couldn't order a "cleared" signal).
 *
 * Ordered by the phone's own send timestamp ("timestamp" in every DataMap, cleared ones included):
 * - [setLive] — a live onDataChanged push, always applied (it IS the newest event this process has
 *   seen, and applying it unconditionally also recovers from a phone clock that went backwards);
 * - [updateIfNotOlder] — a re-read of the persisted item, applied only if it isn't older than what
 *   was last applied, so a re-read racing a not-yet-propagated sync can never regress the display.
 *
 * Still volatile memory: lost when the watch process dies, rebuilt by the next re-read.
 */
open class FreshStore<T : Any> {

    // Setter left public on purpose (only so a stale copy of the old MatchListenerService/
    // NotificationDataListenerService left in the repo still compiles) — always write through
    // setLive/updateIfNotOlder.
    @Volatile
    var current: T? = null

    @Volatile
    private var lastTimestamp: Long = 0L

    @Synchronized
    fun setLive(value: T?, timestamp: Long) {
        current = value
        lastTimestamp = timestamp
    }

    /** Returns what's retained afterwards: [value] if applied, otherwise the (newer) value already there. */
    @Synchronized
    fun updateIfNotOlder(value: T?, timestamp: Long): T? {
        if (timestamp >= lastTimestamp) {
            current = value
            lastTimestamp = timestamp
            return value
        }
        return current
    }
}

/**
 * Remembers the last bitmap composed for a complication (AUDIT 23/09/2026) — composing a 320 px
 * canvas with fitted text on every request is wasted work when the data is the SAME instance as
 * last time (the codecs reuse the in-memory instance when the persisted item hasn't changed).
 */
class ComposedImageCache {
    private var lastKey: Any? = null
    private var lastBitmap: Bitmap? = null

    @Synchronized
    fun get(key: Any?, compose: () -> Bitmap): Bitmap {
        val cached = lastBitmap
        if (cached != null && key === lastKey) return cached
        val bitmap = compose()
        lastKey = key
        lastBitmap = bitmap
        return bitmap
    }
}
