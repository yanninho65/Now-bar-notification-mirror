package com.yann.nowbarmirror.wear

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest

/**
 * NEW 24/09/2026 — "Messages" complication (SMALL_IMAGE, round): the 4 most recent message-app
 * notifications from the phone's notification center (MessagesStore, fed by "/messages"), drawn by
 * ComplicationImageComposer.composeMessagesImage. Tap opens [MessagesActivity].
 *
 * If the "Notification" complication is also placed (NotificationComplicationService.Presence) and
 * its current entry IS one of these messages (same phone notification key), that message is left
 * out here so it isn't shown twice — [visibleMessages], shared with nothing else: the list screen
 * still shows every message. Exception (24/09/2026): when it is the ONLY message, it's shown in both.
 */
class MessagesComplicationService : ComplicationDataSourceService() {

    override fun onComplicationRequest(request: ComplicationRequest, listener: ComplicationRequestListener) {
        val list = when (val read = PhoneDataLayer.readMessages(this)) {
            is PhoneDataLayer.Read.Success -> read.value
            PhoneDataLayer.Read.Failed -> MessagesStore.current
        }
        val data: ComplicationData = when (request.complicationType) {
            ComplicationType.SMALL_IMAGE -> buildSmallImage(visibleMessages(this, list?.messages.orEmpty()))
            else -> NoDataComplicationData()
        }
        listener.onComplicationData(data)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SMALL_IMAGE) return null
        val preview = listOf("Alice", "Bruno", "Chloé", "David").mapIndexed { i, name ->
            MessageInfo("preview$i", 0L, "", name, "", emptyList(), emptyList(), null, null)
        }
        return buildSmallImage(preview, cache = false)
    }

    private fun buildSmallImage(messages: List<MessageInfo>, cache: Boolean = true): ComplicationData {
        val shown = messages.take(4)
        val bitmap = if (cache) composeCached(shown) else ComplicationImageComposer.composeMessagesImage(shown)
        val description = if (shown.isEmpty()) "Aucun message" else shown.joinToString(", ") { it.title }
        return SmallImageComplicationData.Builder(
            smallImage = SmallImage.Builder(Icon.createWithBitmap(bitmap), SmallImageType.PHOTO).build(),
            contentDescription = PlainComplicationText.Builder(description).build()
        ).setTapAction(tapAction()).build()
    }

    private fun tapAction(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MessagesActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    companion object {
        private var lastKey: List<Any>? = null
        private var lastBitmap: Bitmap? = null

        // Value-keyed (the filtered list is a new instance on every request): same messages → same bitmap.
        @Synchronized
        private fun composeCached(shown: List<MessageInfo>): Bitmap {
            val key = shown.map { listOf(it.key, it.postTimeMillis, it.title, it.image?.generationId ?: 0, it.appIcon?.generationId ?: 0) }
            lastBitmap?.let { if (key == lastKey) return it }
            return ComplicationImageComposer.composeMessagesImage(shown).also {
                lastKey = key
                lastBitmap = it
            }
        }

        /** [messages] minus the one the "Notification" complication is currently showing, if placed. */
        fun visibleMessages(context: Context, messages: List<MessageInfo>): List<MessageInfo> {
            // A single message stays visible in both complications (Yann, 24/09/2026).
            if (messages.size < 2 || !NotificationComplicationService.Presence.isActive(context)) return messages
            val latest = when (val read = PhoneDataLayer.readNotification(context)) {
                is PhoneDataLayer.Read.Success -> read.value
                PhoneDataLayer.Read.Failed -> NotificationInfoStore.current
            } ?: return messages
            if (latest.entryKey.isBlank()) return messages
            return messages.filterNot { it.key == latest.entryKey }
        }
    }
}
