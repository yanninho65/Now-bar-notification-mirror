package com.yann.nowbarmirror.wear

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.wearable.DataMap

/**
 * NEW 25/09/2026 — one active Sofascore notification as pushed on "/sport" by
 * mobile/sport/SportWatchSync.kt: [key] = the phone's StatusBarNotification key (addresses
 * follow/dismiss/open, see [PhoneRelay]), [title] = the notification's own title, [match] = the same
 * fields "Score en direct" gets.
 */
data class SportMatch(
    val key: String,
    val postTimeMillis: Long,
    val title: String,
    val match: MatchScore,
    // Raw score strings as sent (e.g. "1 ([4])" after a shoot-out), which the Int fields of [match] can't hold.
    val homeScoreText: String,
    val awayScoreText: String,
    // 25/09/2026: the notification has Sofascore's mute action → bell-off button.
    val canMute: Boolean = false
)

/** The whole "/sport" push: matches most recent first, the sport-app row (same [MessageApp] as Messages), the followed match key. */
class SportList(
    val matches: List<SportMatch>,
    val apps: List<MessageApp>,
    val followedKey: String?,
    val syncTimestamp: Long
)

/** Same freshness rules as the other stores; [onChanged] lets an open SportActivity re-render live. */
object SportStore : FreshStore<SportList>() {
    @Volatile
    var onChanged: (() -> Unit)? = null
}

object SportDataCodec {

    /** [reuse] returned as-is (no asset decoding) when built from this exact send — same trick as MessagesDataCodec. */
    fun decode(context: Context, dataMap: DataMap, reuse: SportList? = null): SportList {
        val syncTimestamp = PhoneDataLayer.timestampOf(dataMap)
        if (reuse != null && syncTimestamp != 0L && reuse.syncTimestamp == syncTimestamp) return reuse

        val matches = dataMap.getDataMapArrayList("matches").orEmpty().mapIndexedNotNull { index, item ->
            val key = item.getString("key").orEmpty().takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            val match = MatchDataCodec.matchFrom(context, item, dataMap, "img_$index", syncTimestamp) ?: return@mapIndexedNotNull null
            SportMatch(
                key = key,
                postTimeMillis = item.getLong("postTimeMillis", 0L),
                title = item.getString("title").orEmpty().ifBlank { "${match.homeTeam} - ${match.awayTeam}" },
                match = match,
                homeScoreText = item.getString("homeScore").orEmpty().trim(),
                awayScoreText = item.getString("awayScore").orEmpty().trim(),
                canMute = item.getBoolean("canMute", false)
            )
        }
        return SportList(
            matches = matches,
            apps = MessagesDataCodec.decodeApps(context, dataMap, HashMap()),
            followedKey = dataMap.getString("followedKey")?.takeIf { it.isNotBlank() },
            syncTimestamp = syncTimestamp
        )
    }
}
