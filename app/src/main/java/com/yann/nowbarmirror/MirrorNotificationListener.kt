package com.yann.nowbarmirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.yann.nowbarmirror.settings.AppMirrorPrefs
import com.yann.nowbarmirror.settings.LatestModePrefs
import com.yann.nowbarmirror.settings.MirrorMode
import com.yann.nowbarmirror.settings.ServicePrefs
import com.yann.nowbarmirror.widget.NowBarWidgetProvider
import com.yann.nowbarmirror.widget.WidgetNotificationStore
import java.util.concurrent.atomic.AtomicBoolean

class MirrorNotificationListener : NotificationListenerService() {

    companion object {
        const val CHANNEL_ID = "mirror"
        const val MIRROR_ID = 9001          // fixed slot shared by every app set to "Dernière notif"
        const val ALL_MODE_ID_BASE = 9100   // "Toutes" mirrors get their own id, allocated from here
        const val EXTRA_ORIGINAL_KEY = "mirror.original.key"
        const val EXTRA_MIRROR = "mirror.is_mirror"
        const val ACTION_DISMISS_WIDGET = "com.yann.nowbarmirror.widget.ACTION_DISMISS"
        const val EXTRA_DISMISS_KEY = "mirror.widget.dismiss_key"
    }

    private val ready = AtomicBoolean(false)

    // Set by onStartCommand() when a widget dismiss arrives before onListenerConnected() has
    // fired (e.g. this process was just woken up to handle the tap) — processed as soon as the
    // listener is actually connected, instead of silently failing to cancel anything.
    private var pendingDismissKey: String? = null

    // LATEST mode: one shared slot; whichever LATEST-mode app posted last occupies it.
    private var latestOriginalKey: String? = null

    // ALL mode: every distinct original notification key gets its own persistent mirror id.
    private val allModeMirrors = mutableMapOf<String, Int>()
    private var nextAllModeMirrorId = ALL_MODE_ID_BASE

    // LATEST mode: every currently active (not-yet-removed) original from a LATEST-mode app,
    // kept in insertion order so the last entry is always the most recent -> this is the
    // fallback queue used to promote the next-most-recent one when the current mirror or its
    // original gets dismissed, instead of just clearing the shared slot.
    private val latestModeActive = LinkedHashMap<String, StatusBarNotification>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        ready.set(true)
        createChannel()
        // This service is not a foreground service, so Android (One UI in particular) can and
        // does kill its process in the background. When it comes back, onListenerConnected()
        // fires again but latestOriginalKey / allModeMirrors have been reset to empty — any
        // mirror already on screen from before the kill is now "orphaned" in memory, so
        // swiping it never calls cancelOriginal() and the delete sync silently breaks. Fix:
        // rebuild the bookkeeping from what's actually posted, using the original key we
        // already stamp into each mirror's extras, instead of trusting in-memory state that
        // may not have survived.
        rebuildStateFromActiveNotifications()
        pendingDismissKey?.let { key ->
            pendingDismissKey = null
            cancelOriginal(key)
        }
    }

    /**
     * The system keeps this service bound for the notification-listener callbacks, but a bound
     * service can also be started explicitly — that's how the widget's dismiss button reaches
     * it: its PendingIntent (PendingIntent.getService) targets this component directly with
     * ACTION_DISMISS_WIDGET, so the tap keeps working even if this process had been killed and
     * needs the system to spin it back up first.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISMISS_WIDGET) {
            val key = intent.getStringExtra(EXTRA_DISMISS_KEY)
            if (key != null) {
                if (ready.get()) cancelOriginal(key) else pendingDismissKey = key
            }
            // Clear the widget right away instead of waiting for the onNotificationRemoved
            // round-trip, so the tap always feels instant even if cancelOriginal() above is
            // deferred (not yet connected) or silently no-ops (original already gone).
            if (key != null && WidgetNotificationStore.get(applicationContext)?.key == key) {
                WidgetNotificationStore.clear(applicationContext)
                NowBarWidgetProvider.requestUpdate(applicationContext)
            }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun rebuildStateFromActiveNotifications() {
        latestOriginalKey = null
        allModeMirrors.clear()
        latestModeActive.clear()

        val all = try {
            activeNotifications ?: return
        } catch (_: Throwable) {
            return
        }

        // Rebuild the LATEST-mode fallback queue from every currently posted notification
        // belonging to an app set to "Dernière notif" (same eligibility filters as
        // onNotificationPosted), oldest -> newest, so a swipe right after a process restart
        // still has the right candidate to promote.
        all.asSequence()
            .filter { it.packageName != packageName }
            .filter { !it.isOngoing }
            .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .filter { AppMirrorPrefs.getMode(applicationContext, it.packageName) == MirrorMode.LATEST }
            .sortedBy { it.postTime }
            .forEach { latestModeActive[it.key] = it }

        // Same staleness concern as the mirrors below: if the widget was pointing at a
        // notification that's no longer posted (removed while this process was dead), there
        // will never be an onNotificationRemoved callback for it — clear it explicitly instead
        // of leaving a dismiss button on screen that dismisses nothing.
        WidgetNotificationStore.get(applicationContext)?.let { widgetData ->
            if (all.none { it.key == widgetData.key }) {
                WidgetNotificationStore.clear(applicationContext)
                NowBarWidgetProvider.requestUpdate(applicationContext)
            }
        }

        val ourMirrors = all.filter { it.packageName == packageName }
        if (ourMirrors.isEmpty()) return

        for (mirrorSbn in ourMirrors) {
            val extras = mirrorSbn.notification.extras
            if (!extras.getBoolean(EXTRA_MIRROR, false)) continue
            val originalKey = extras.getString(EXTRA_ORIGINAL_KEY) ?: continue

            val originalStillPosted = all.any { it.key == originalKey }
            if (!originalStillPosted) {
                // The original disappeared while this service's process was dead, so we never
                // got the removal event for it. Don't leave a stale mirror behind.
                cancelMirror(mirrorSbn.id)
                continue
            }

            if (mirrorSbn.id == MIRROR_ID) {
                latestOriginalKey = originalKey
            } else {
                allModeMirrors[originalKey] = mirrorSbn.id
                if (mirrorSbn.id >= nextAllModeMirrorId) {
                    nextAllModeMirrorId = mirrorSbn.id + 1
                }
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!ready.get()) return
        if (!ServicePrefs.isEnabled(applicationContext)) return
        if (sbn.packageName == packageName) return
        if (sbn.isOngoing) return
        // Group-summary notifications (e.g. WhatsApp's "X new messages" bundle) carry no
        // per-conversation photo or actions — skip them so they don't overwrite the real one.
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        when (AppMirrorPrefs.getMode(applicationContext, sbn.packageName)) {
            MirrorMode.ALL -> {
                val mirrorId = allModeMirrors.getOrPut(sbn.key) { nextAllModeMirrorId++ }
                mirror(sbn, mirrorId)
            }
            MirrorMode.LATEST -> {
                // Remove-then-reinsert bumps this key to the end of the queue on an update
                // (same original key reposted), so insertion order always tracks recency.
                latestModeActive.remove(sbn.key)
                latestModeActive[sbn.key] = sbn
                latestOriginalKey = sbn.key
                mirror(sbn, MIRROR_ID)
            }
            MirrorMode.NONE -> Unit
        }
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: RankingMap?,
        reason: Int
    ) {
        if (!ready.get()) return

        if (sbn.packageName == packageName) {
            // Only react to a *genuine* dismissal of one of our mirrors (user swipe, or
            // "clear all"). REASON_APP_CANCEL means we cancelled it ourselves. The previous
            // version always cancelled-then-reposted the shared slot to swap its content, which
            // fired this same callback for our own cancel and raced against the state update —
            // by the time the async removal event arrived, the tracked key had often already
            // moved on to the *new* original, so that new original got wrongly cancelled.
            // Filtering by reason removes the race entirely: an original is only ever cancelled
            // when its mirror was actually swiped away by the user.
            val userDismissed = reason == REASON_CANCEL || reason == REASON_CANCEL_ALL
            if (!userDismissed) return

            if (sbn.id == MIRROR_ID) {
                // User swiped the shared slot: cancel the original that was showing in it,
                // drop it from the fallback queue, then promote whatever is now the most
                // recent survivor instead of leaving the slot empty.
                latestOriginalKey?.let { key ->
                    cancelOriginal(key)
                    latestModeActive.remove(key)
                }
                latestOriginalKey = null
                promoteNextLatestMode()
            } else {
                val originalKey = allModeMirrors.entries.firstOrNull { it.value == sbn.id }?.key
                if (originalKey != null) {
                    cancelOriginal(originalKey)
                    allModeMirrors.remove(originalKey)
                }
            }
            return
        }

        // The widget shows whichever eligible notification was mirrored most recently,
        // decoupled from ALL vs LATEST — clear it whenever ITS specific original disappears,
        // regardless of which mirror-mode branch below ends up handling the removal.
        WidgetNotificationStore.get(applicationContext)?.let { widgetData ->
            if (widgetData.key == sbn.key) {
                WidgetNotificationStore.clear(applicationContext)
                NowBarWidgetProvider.requestUpdate(applicationContext)
            }
        }

        // The original notification itself was removed (by its app, the user, whatever reason).
        if (latestModeActive.remove(sbn.key) != null) {
            // It belonged to the LATEST-mode queue. If it was the one currently shown in the
            // shared slot, promote the next-most-recent survivor; if it wasn't (e.g. it was
            // sitting in the shade behind the current mirror and got swiped on its own), just
            // drop it from the queue and leave the current mirror untouched.
            if (sbn.key == latestOriginalKey) {
                latestOriginalKey = null
                promoteNextLatestMode()
            }
            return
        }
        allModeMirrors.remove(sbn.key)?.let { cancelMirror(it) }
    }

    /**
     * Re-posts the shared LATEST-mode slot with whatever is now the most recent entry left in
     * the fallback queue, or clears the slot if the queue is empty or the option is turned off.
     * Called whenever the currently-mirrored original (or its mirror) is dismissed.
     */
    private fun promoteNextLatestMode() {
        val next = latestModeActive.entries.lastOrNull()
        if (next != null && LatestModePrefs.isFallbackEnabled(applicationContext)) {
            latestOriginalKey = next.key
            mirror(next.value, MIRROR_ID)
        } else {
            // Option disabled, or nothing left to fall back to: clear the slot, same as the
            // original behavior before this option existed.
            cancelMirror(MIRROR_ID)
        }
    }

    private fun mirror(sbn: StatusBarNotification, mirrorId: Int) {
        val n = sbn.notification
        val extras = n.extras
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
            ?: getAppName(sbn.packageName)
        val rawText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: ""

        // Some apps put the more useful string in the title rather than the text (e.g. a
        // price alert app: title = "AAPL +5%", text = generic body copy). shortChipText()
        // below prefers `text` for the collapsed pill, so swapping here is what actually
        // puts the title in the pill for those apps.
        val invert = AppMirrorPrefs.getInvertTitleText(applicationContext, sbn.packageName)
        val title = if (invert) rawText.ifBlank { rawTitle } else rawTitle
        val text = if (invert) rawTitle else rawText

        val image = extractImageBitmap(sbn)   // computed once, reused for the large icon and the chip attempt below

        // The lock-screen widget mirrors whichever eligible notification arrived most recently
        // from ANY app configured with a mirror mode — no ALL vs LATEST distinction, unlike the
        // system-notification mirror above. Reuses exactly the same title/text/image already
        // computed for the mirror instead of recomputing them.
        WidgetNotificationStore.save(
            context = applicationContext,
            key = sbn.key,
            title = title,
            text = text,
            packageName = sbn.packageName,
            contentIntent = n.contentIntent,
            image = image
        )
        NowBarWidgetProvider.requestUpdate(applicationContext)

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
            .setLargeIcon(appIconBitmap(sbn.packageName))   // top-right thumbnail in the expanded popup — app icon, not the contact photo
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

        // Small icon slot: the extracted photo when available, so the pill and the reduced
        // lock-screen Now Bar show the contact/notification image. Independent from the
        // large icon above, which now always shows the app icon (top-right thumbnail in
        // the expanded popup). Falls back to the app icon here too if there's no image.
        val chipIcon = image?.let { bmp ->
            // Same reasoning as appIcon() below: without this, a plain createWithBitmap() photo
            // is treated as a "legacy" square icon, shrunk into the adaptive safe zone and given
            // a synthesized background plate (usually white) to fill the rest of the circle.
            // The photo is already full-bleed, so telling the OS it's adaptive lets it fill the
            // whole circle with no background showing through.
            if (Build.VERSION.SDK_INT >= 26) Icon.createWithAdaptiveBitmap(bmp) else Icon.createWithBitmap(bmp)
        } ?: appIcon(sbn.packageName)
        chipIcon?.let { icon ->
            notification = Notification.Builder.recoverBuilder(this, notification)
                .setSmallIcon(icon)
                .build()
        }

        // Posting to an id that's already showing is an in-place update as far as the system
        // is concerned — no removal event is generated. That's what lets a LATEST-mode swap or
        // an ALL-mode content refresh happen without ever triggering onNotificationRemoved for
        // our own package, which is the other half of the race fix above.
        getSystemService(NotificationManager::class.java).notify(mirrorId, notification)
    }

    private fun cancelMirror(mirrorId: Int) {
        getSystemService(NotificationManager::class.java).cancel(mirrorId)
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
     * Text shown in the collapsed pill. Prefer the message text over the title.
     * No truncation here: Samsung's Now Bar appears to handle long text itself
     * (marquee/scroll) rather than following the strict AOSP 96dp chip-fit rule,
     * so cutting it in code would only hide that behavior.
     */
    private fun shortChipText(text: String, title: String): String {
        return text.ifBlank { title }
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
