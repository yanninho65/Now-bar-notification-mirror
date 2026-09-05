package com.yann.nowbarmirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
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

        val image = extractImageBitmap(sbn)   // computed once, reused for the large icon and the chip attempt below

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentTitle(title)
            .setContentText(text)
            // Collapsed pill content. Chip is max 96dp wide: text only renders if it fits,
            // otherwise the system falls back to icon-only — keep this short.
            .setShortCriticalText(shortChipText(text, title))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setCategory(n.category ?: Notification.CATEGORY_MESSAGE)
            .setWhen(n.`when`)
            .setShowWhen(true)
            .setContentIntent(n.contentIntent)
            .setLargeIcon(image ?: appIconBitmap(sbn.packageName))   // drives the reduced Now Bar on lock screen
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

        var notification = builder.build()

        // Chip/status-bar icon slot. NOTE: Android renders the small icon as a monochrome
        // alpha-mask silhouette in the classic status bar — this is a documented platform
        // behavior, not specific to this app. Whether One UI 8.5's Live Update pill honors
        // full color here is unverified; this is the thing to check on-device on the S26.
        // Falls back to the app icon if there's no image or the OS ignores it.
        val chipIcon = image?.let { Icon.createWithBitmap(it) } ?: appIcon(sbn.packageName)
        chipIcon?.let { icon ->
            notification = Notification.Builder.recoverBuilder(this, notification)
                .setSmallIcon(icon)
                .build()
        }

        cancelMirror()
        currentKey = sbn.key
        currentSourcePackage = sbn.packageName
        getSystemService(NotificationManager::class.java).notify(MIRROR_ID, notification)
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

    /**
     * The source app's real icon, used for the small-icon slot (left side, next to the title).
     * Modern launcher icons are AdaptiveIconDrawable (background + foreground layers). Baking
     * both layers into a flat bitmap and handing it to the system as a plain Icon is what
     * produces the ugly solid-color/white square once the OS tries to mask it into a circle.
     * Icon.createWithAdaptiveBitmap() tells the OS "this bitmap already follows the adaptive
     * safe-zone convention", so it applies the same clean round mask native notifications get.
     */
    private fun appIcon(pkg: String): Icon? {
        return try {
            val drawable = packageManager.getApplicationIcon(pkg)
            val bitmap = drawableToBitmap(drawable)
            if (Build.VERSION.SDK_INT >= 26 && drawable is AdaptiveIconDrawable) {
                Icon.createWithAdaptiveBitmap(bitmap)
            } else {
                Icon.createWithBitmap(bitmap)
            }
        } catch (_: Throwable) { null }
    }

    /**
     * The Live Update chip is at most 96dp wide and only shows text if the whole string
     * fits (roughly: <7 chars always shown, otherwise shown only if more than half fits,
     * else icon-only). Prefer the message text over the title per product requirement,
     * but trim it so it actually has a chance of rendering instead of collapsing to icon-only.
     */
    private fun shortChipText(text: String, title: String): String {
        val source = text.ifBlank { title }
        return if (source.length <= 24) source else source.take(24).trimEnd() + "…"
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