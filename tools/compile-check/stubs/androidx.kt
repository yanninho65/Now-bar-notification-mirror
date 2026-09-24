@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.core.app

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Bundle

class Person { val name: CharSequence? = null; val icon: androidx.core.graphics.drawable.IconCompat? = null }

class NotificationCompat {
    companion object {
        const val PRIORITY_LOW = -1
        const val PRIORITY_HIGH = 1
        const val CATEGORY_STATUS = "status"
    }
    abstract class Style
    class BigTextStyle : Style() { fun bigText(t: CharSequence?): BigTextStyle = this }
    class MessagingStyle : Style() {
        class Message { val text: CharSequence? = null; val person: Person? = null }
        val messages: List<Message> = emptyList()
        val historicMessages: List<Message> = emptyList()
        companion object { @JvmStatic fun extractMessagingStyleFromNotification(n: Notification): MessagingStyle? = null }
    }
    class Action(icon: Int, title: CharSequence?, intent: PendingIntent?) {
        class Builder(icon: Int, title: CharSequence?, intent: PendingIntent?) { fun build(): Action = TODO() }
    }
    class Builder(context: Context, channelId: String) {
        fun setSmallIcon(i: Int): Builder = this
        fun setPriority(i: Int): Builder = this
        fun setContentTitle(t: CharSequence?): Builder = this
        fun setContentText(t: CharSequence?): Builder = this
        fun setShortCriticalText(t: String?): Builder = this
        fun setStyle(s: Style?): Builder = this
        fun setOngoing(b: Boolean): Builder = this
        fun setAutoCancel(b: Boolean): Builder = this
        fun setOnlyAlertOnce(b: Boolean): Builder = this
        fun setCategory(c: String?): Builder = this
        fun setWhen(w: Long): Builder = this
        fun setShowWhen(b: Boolean): Builder = this
        fun setContentIntent(p: PendingIntent?): Builder = this
        fun setFullScreenIntent(p: PendingIntent?, b: Boolean): Builder = this
        fun setLargeIcon(b: Bitmap?): Builder = this
        fun addExtras(b: Bundle?): Builder = this
        fun addAction(a: Action?): Builder = this
        fun setRequestPromotedOngoing(b: Boolean): Builder = this
        fun build(): Notification = TODO()
    }
}

class NotificationManagerCompat {
    fun notify(id: Int, n: Notification) {}
    fun cancel(id: Int) {}
    fun areNotificationsEnabled(): Boolean = true
    companion object {
        @JvmStatic fun from(c: Context): NotificationManagerCompat = TODO()
        @JvmStatic fun getEnabledListenerPackages(c: Context): Set<String> = emptySet()
    }
}
