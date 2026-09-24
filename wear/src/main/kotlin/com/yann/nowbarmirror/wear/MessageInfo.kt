package com.yann.nowbarmirror.wear

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.wearable.DataMap

/**
 * NEW 24/09/2026 — one message-app notification currently in the phone's notification center, as
 * pushed on "/messages" by mobile/MessagesWatchSync.kt (see its doc for the format). [key] is the
 * phone's StatusBarNotification key: the watch addresses actions/dismiss/open with it
 * ([PhoneRelay.sendMessageAction] …) and compares it with the "Notification" complication's
 * [NotificationInfo.entryKey] to avoid showing the same message twice.
 */
data class MessageInfo(
    val key: String,
    val postTimeMillis: Long,
    val packageName: String,
    val title: String,
    val text: String,
    val detailLines: List<String>,
    val actionLabels: List<String>,
    val image: Bitmap?,
    val appIcon: Bitmap?
)

/** A selected message app installed on the phone, with its unread count (see mobile MessagesWatchSync.unreadCount). */
data class MessageApp(val packageName: String, val label: String, val count: Int, val icon: Bitmap?)

/** The whole list from one phone push, most recent first; [syncTimestamp] = the phone's send time. */
class MessageList(val messages: List<MessageInfo>, val syncTimestamp: Long, val apps: List<MessageApp> = emptyList())

/**
 * Same freshness rules as the other stores (see [FreshStore]). [onChanged] lets an open
 * MessagesActivity re-render live when a push arrives (set in onResume, cleared in onPause).
 */
object MessagesStore : FreshStore<MessageList>() {
    @Volatile
    var onChanged: (() -> Unit)? = null
}

object MessagesDataCodec {

    /** [reuse] returned as-is (no asset decoding) when built from this exact send — same trick as NotificationDataCodec. */
    fun decode(context: Context, dataMap: DataMap, reuse: MessageList? = null): MessageList {
        val syncTimestamp = PhoneDataLayer.timestampOf(dataMap)
        if (reuse != null && syncTimestamp != 0L && reuse.syncTimestamp == syncTimestamp) return reuse

        val icons = HashMap<String, Bitmap?>()
        val items = dataMap.getDataMapArrayList("messages").orEmpty()
        val messages = items.mapIndexed { index, item ->
            val pkg = item.getString("packageName").orEmpty()
            MessageInfo(
                key = item.getString("key").orEmpty(),
                postTimeMillis = item.getLong("postTimeMillis", 0L),
                packageName = pkg,
                title = item.getString("title").orEmpty(),
                text = item.getString("text").orEmpty(),
                detailLines = item.getStringArrayList("detailLines") ?: emptyList(),
                actionLabels = item.getStringArrayList("actionLabels") ?: emptyList(),
                image = PhoneDataLayer.decodeImageAsset(context, dataMap, "img_$index"),
                appIcon = icons.getOrPut(pkg) { PhoneDataLayer.decodeImageAsset(context, dataMap, "icon_$pkg") }
            )
        }.filter { it.key.isNotBlank() }
        val apps = dataMap.getDataMapArrayList("apps").orEmpty().mapNotNull { item ->
            val pkg = item.getString("packageName").orEmpty().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MessageApp(
                packageName = pkg,
                label = item.getString("label").orEmpty(),
                count = item.getInt("count", 0),
                icon = icons.getOrPut(pkg) { PhoneDataLayer.decodeImageAsset(context, dataMap, "icon_$pkg") }
            )
        }
        return MessageList(messages, syncTimestamp, apps)
    }
}
