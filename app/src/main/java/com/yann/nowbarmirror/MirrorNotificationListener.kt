package com.yann.nowbarmirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean

class MirrorNotificationListener : NotificationListenerService() {

    companion object {
        const val CHANNEL_ID = "mirror"
        const val MIRROR_ID = 9001
        const val EXTRA_ORIGINAL_KEY = "mirror.original.key"
        const val EXTRA_MIRROR = "mirror.is_mirror"
    }

    private val ready = AtomicBoolean(false)
    private var currentKey: String? = null
    private var currentSourcePackage: String? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        ready.set(true)
        createChannel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!ready.get()) return
        if (sbn.packageName == packageName) return
        if (sbn.isOngoing) return
        mirror(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!ready.get()) return
        if (sbn.packageName == packageName) {
            val key = sbn.notification.extras.getString(EXTRA_ORIGINAL_KEY)
            if (!key.isNullOrEmpty()) cancelOriginal(key)
            return
        }

        if (sbn.key == currentKey) {
            cancelMirror()
            currentKey = null
            currentSourcePackage = null
        }
    }

    private fun mirror(sbn: StatusBarNotification) {
        val n = sbn.notification
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
            ?: getAppName(sbn.packageName)
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: ""

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentTitle(title)
            .setContentText(text)
            .setShortCriticalText(title)   // what the collapsed pill shows — without this it defaults to a time/duration
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setCategory(n.category ?: Notification.CATEGORY_MESSAGE)
            .setWhen(n.`when`)
            .setShowWhen(true)
            .setContentIntent(n.contentIntent)
            .setLargeIcon(extractImageBitmap(sbn) ?: appIconBitmap(sbn.packageName))
            .addExtras(Bundle().apply {
                putBoolean(EXTRA_MIRROR, true)
                putString(EXTRA_ORIGINAL_KEY, sbn.key)
            })

        n.actions?.take(3)?.forEach { action ->
            val pi = action.actionIntent ?: return@forEach
            builder.addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_send,
                    action.title,
                    pi
                ).build()
            )
        }

        if (Build.VERSION.SDK_INT >= 36) {
            try {
                builder.setRequestPromotedOngoing(true)
            } catch (_: Throwable) { }
        }

        cancelMirror()
        currentKey = sbn.key
        currentSourcePackage = sbn.packageName
        getSystemService(NotificationManager::class.java).notify(MIRROR_ID, builder.build())
    }

    private fun cancelMirror() {
        getSystemService(NotificationManager::class.java).cancel(MIRROR_ID)
    }

    private fun cancelOriginal(key: String) {
        try {
            cancelNotification(key)
        } catch (_: Throwable) {
            activeNotifications?.firstOrNull { it.key == key }?.let {
                cancelNotification(it.key)
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_description)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun appIconBitmap(pkg: String): Bitmap? {
        return try {
            drawableToBitmap(packageManager.getApplicationIcon(pkg))
        } catch (_: Throwable) { null }
    }

    private fun getAppName(pkg: String): String {
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
    }

    /**
     * Tries, in order:
     * 1) the contact photo attached to the sender of a MessagingStyle notification
     *    (WhatsApp, Messages, etc. put it here, NOT in the large icon),
     * 2) a BigPictureStyle image (EXTRA_PICTURE),
     * 3) the notification's actual large icon via the official getLargeIcon() accessor
     *    (reading raw extras instead of this, like the previous version did, misses
     *    most real-world notifications).
     */
    private fun extractImageBitmap(sbn: StatusBarNotification): Bitmap? {
        val n = sbn.notification

        NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
            ?.messages
            ?.lastOrNull { it.person?.icon != null }
            ?.person?.icon
            ?.let { personIcon ->
                drawableFromIcon(personIcon.toIcon(this))?.let { return it }
            }

        val bigPicture: Bitmap? = if (Build.VERSION.SDK_INT >= 33) {
            n.extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
        } else {
            @Suppress("DEPRECATION") n.extras.getParcelable(Notification.EXTRA_PICTURE) as? Bitmap
        }
        if (bigPicture != null) return bigPicture

        n.getLargeIcon()?.let { icon ->
            drawableFromIcon(icon)?.let { return it }
        }

        return null
    }

    private fun drawableFromIcon(icon: Icon): Bitmap? {
        val drawable = try { icon.loadDrawable(this) } catch (_: Throwable) { null } ?: return null
        return drawableToBitmap(drawable)
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}