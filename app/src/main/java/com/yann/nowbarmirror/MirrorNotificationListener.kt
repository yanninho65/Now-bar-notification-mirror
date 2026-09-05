package com.yann.nowbarmirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Bitmap
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
            // The user removed our mirror (e.g. from the notification shade / Now Bar).
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
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setCategory(n.category ?: Notification.CATEGORY_MESSAGE)
            .setWhen(n.`when`)
            .setShowWhen(true)
            .setContentIntent(n.contentIntent)
            .setLargeIcon(extractBitmap(extras) ?: appIconBitmap(sbn.packageName))
            .addExtras(Bundle().apply {
                putBoolean(EXTRA_MIRROR, true)
                putString(EXTRA_ORIGINAL_KEY, sbn.key)
            })

        // Copy action buttons. Their PendingIntents remain owned by the source app.
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

        // Android 16 promoted ongoing notifications can be eligible for Live Update surfaces.
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
            // Fallback for OEM variations: resolve active notification and cancel by fields.
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
            val drawable = packageManager.getApplicationIcon(pkg)
            val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 128
            val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 128
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        } catch (_: Throwable) { null }
    }

    private fun getAppName(pkg: String): String {
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
    }

    private fun extractBitmap(extras: Bundle): Bitmap? {
        return try {
            extras.getParcelableCompat<Bitmap>(Notification.EXTRA_LARGE_ICON_BIG)
                ?: extras.getParcelableCompat<Bitmap>(Notification.EXTRA_LARGE_ICON)
        } catch (_: Throwable) { null }
    }

}

private inline fun <reified T : android.os.Parcelable> Bundle.getParcelableCompat(key: String): T? {
    return if (Build.VERSION.SDK_INT >= 33) getParcelable(key, T::class.java) else @Suppress("DEPRECATION") getParcelable(key)
}
