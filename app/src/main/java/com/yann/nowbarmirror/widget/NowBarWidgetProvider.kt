package com.yann.nowbarmirror.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import com.yann.nowbarmirror.MirrorNotificationListener
import com.yann.nowbarmirror.R
import com.yann.nowbarmirror.settings.WidgetActionsPrefs

/** One notification action, ready to render as a small tappable icon in the widget. */
data class WidgetAction(
    val label: String,
    val icon: Icon?,
    val pendingIntent: PendingIntent
)

/**
 * Home-screen App Widget (4x1, transparent background) meant to be placed on the lock screen
 * through a third-party lock-widget host such as Samsung's LockStar. Mirrors whatever
 * WidgetNotificationStore currently holds: the single most recently posted notification from
 * any app configured with a mirror mode, regardless of ALL vs LATEST.
 */
class NowBarWidgetProvider : AppWidgetProvider() {

    companion object {

        // Actions are deliberately NOT persisted to WidgetNotificationStore/SharedPreferences,
        // for the same reason the content PendingIntent isn't (see the class doc on
        // WidgetNotificationStore): a PendingIntent only survives a real Binder transaction
        // (handing it straight to AppWidgetManager here), not a round-trip through disk. So
        // these live only in memory, for as long as this process stays alive since the
        // notification was posted. liveActionsKey guards against showing them for the wrong
        // notification: buildViewsUnsafe() only uses liveActions when it matches the key
        // WidgetNotificationStore currently holds, so a stale set from a previous notification
        // (or the empty default right after a process restart) never leaks onto an unrelated
        // one — the actions row just stays hidden until the next live push.
        private var liveActions: List<WidgetAction> = emptyList()
        private var liveActionsKey: String? = null

        /**
         * Called right when a notification is mirrored, with THAT notification's own live
         * PendingIntent(s). This is the only reliable way to give the widget a working "open
         * the exact conversation/article" tap and working action buttons: handing a
         * PendingIntent to AppWidgetManager here goes through a real Binder transaction (same
         * as NotificationManager.notify() already does for the system-notification mirror),
         * which is what actually preserves it — trying to save and later reconstruct a
         * PendingIntent from SharedPreferences does not.
         */
        fun pushLive(
            context: Context,
            key: String,
            title: String,
            text: String,
            packageName: String,
            contentIntent: PendingIntent?,
            image: Bitmap?,
            actions: List<WidgetAction> = emptyList()
        ) {
            WidgetNotificationStore.save(context, key, title, text, packageName, image)
            liveActions = actions
            liveActionsKey = key
            pushToAllWidgets(context, buildViews(context, liveContentIntent = contentIntent))
        }

        /**
         * Rebuilds and pushes from whatever WidgetNotificationStore currently holds, with no
         * live PendingIntent available (used when just clearing the widget, refreshing after a
         * staleness check, or when the system calls onUpdate() independently of any specific
         * notification event). The tap falls back to opening the source app in that case, and
         * the actions row — which has no such fallback — simply stays hidden.
         */
        fun requestUpdate(context: Context) {
            pushToAllWidgets(context, buildViews(context))
        }

        private fun pushToAllWidgets(context: Context, views: RemoteViews) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NowBarWidgetProvider::class.java))
            if (ids.isEmpty()) return
            ids.forEach { id -> manager.updateAppWidget(id, views) }
        }

        private fun buildViews(context: Context, liveContentIntent: PendingIntent? = null): RemoteViews {
            return try {
                buildViewsUnsafe(context, liveContentIntent)
            } catch (t: Throwable) {
                // TEMPORARY diagnostic: surfaces the exact failure on screen since this device
                // can't be hooked up to Android Studio for logcat. Safe to remove once the
                // widget rendering path is confirmed stable — until then, a fallback empty
                // widget is returned so this can never crash the shared app process.
                Toast.makeText(
                    context,
                    "Widget: ${t.javaClass.simpleName}: ${t.message}",
                    Toast.LENGTH_LONG
                ).show()
                emptyViews(context)
            }
        }

        private fun emptyViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_now_bar)
            views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_empty_title))
            views.setTextViewText(R.id.widget_text, "")
            views.setImageViewResource(R.id.widget_app_icon, R.drawable.ic_stat_mirror)
            views.setViewVisibility(R.id.widget_image, View.GONE)
            views.setViewVisibility(R.id.widget_dismiss, View.GONE)
            views.setViewVisibility(R.id.widget_actions_container, View.GONE)
            return views
        }

        private fun buildViewsUnsafe(context: Context, liveContentIntent: PendingIntent?): RemoteViews {
            val data = WidgetNotificationStore.get(context) ?: return emptyViews(context)

            val views = RemoteViews(context.packageName, R.layout.widget_now_bar)
            views.setTextViewText(R.id.widget_title, data.title)
            views.setTextViewText(R.id.widget_text, data.text)

            val appIcon = appIconBitmap(context, data.packageName)
            if (appIcon != null) {
                views.setImageViewBitmap(R.id.widget_app_icon, circularBitmap(appIcon))
            } else {
                views.setImageViewResource(R.id.widget_app_icon, R.drawable.ic_stat_mirror)
            }

            val imageBitmap = data.imageFile?.let { BitmapFactory.decodeFile(it.path) }
            if (imageBitmap != null) {
                views.setImageViewBitmap(R.id.widget_image, circularBitmap(imageBitmap))
                views.setViewVisibility(R.id.widget_image, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_image, View.GONE)
            }

            views.setViewVisibility(R.id.widget_dismiss, View.VISIBLE)
            views.setOnClickPendingIntent(R.id.widget_dismiss, dismissPendingIntent(context, data.key))

            applyActions(context, views, data.key)

            val openIntent = liveContentIntent ?: launchAppPendingIntent(context, data.packageName)
            if (openIntent != null) {
                views.setOnClickPendingIntent(R.id.widget_root, openIntent)
            }

            return views
        }

        /**
         * Populates up to two action buttons when the option is enabled in Settings AND we
         * still hold live PendingIntents for THIS exact notification (see the liveActions doc
         * above) — otherwise hides the whole row rather than showing dead buttons. Icons are
         * force-tinted white via setColorFilter to match the rest of this widget's white,
         * background-less style; a source app that hands over a full-color (non-template) icon
         * here could look off, but most notification action icons are simple monochrome glyphs
         * meant to be tinted by whoever renders them, same as the system does natively.
         */
        private fun applyActions(context: Context, views: RemoteViews, dataKey: String) {
            val actions = if (WidgetActionsPrefs.isEnabled(context) && liveActionsKey == dataKey) {
                liveActions
            } else {
                emptyList()
            }

            val slots = listOf(R.id.widget_action_1 to actions.getOrNull(0), R.id.widget_action_2 to actions.getOrNull(1))
            for ((viewId, action) in slots) {
                if (action == null) {
                    views.setViewVisibility(viewId, View.GONE)
                    continue
                }
                views.setViewVisibility(viewId, View.VISIBLE)
                val icon = action.icon
                if (icon != null) {
                    views.setImageViewIcon(viewId, icon)
                } else {
                    views.setImageViewResource(viewId, android.R.drawable.ic_menu_send)
                }
                views.setInt(viewId, "setColorFilter", Color.WHITE)
                views.setContentDescription(viewId, action.label)
                views.setOnClickPendingIntent(viewId, action.pendingIntent)
            }

            views.setViewVisibility(
                R.id.widget_actions_container,
                if (actions.isEmpty()) View.GONE else View.VISIBLE
            )
        }

        private fun dismissPendingIntent(context: Context, key: String): PendingIntent {
            val intent = Intent(context, MirrorNotificationListener::class.java).apply {
                action = MirrorNotificationListener.ACTION_DISMISS_WIDGET
                putExtra(MirrorNotificationListener.EXTRA_DISMISS_KEY, key)
            }
            return PendingIntent.getService(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * Fallback used whenever there's no live PendingIntent to attach (i.e. every render
         * that isn't happening right at the moment a notification was posted): opens the
         * source app itself rather than leaving the tap dead.
         */
        private fun launchAppPendingIntent(context: Context, packageName: String): PendingIntent? {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return null
            return PendingIntent.getActivity(
                context,
                packageName.hashCode(),
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun appIconBitmap(context: Context, packageName: String): Bitmap? {
            return try {
                drawableToBitmap(context.packageManager.getApplicationIcon(packageName))
            } catch (_: Throwable) {
                null
            }
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

        /** Crops [source] into a circle, matching the round chips in the reference design. */
        private fun circularBitmap(source: Bitmap): Bitmap {
            val size = minOf(source.width, source.height).coerceAtLeast(1)
            val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.drawOval(RectF(Rect(0, 0, size, size)), paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            val left = (source.width - size) / 2f
            val top = (source.height - size) / 2f
            canvas.drawBitmap(source, -left, -top, paint)
            return output
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = buildViews(context)
        appWidgetIds.forEach { id -> appWidgetManager.updateAppWidget(id, views) }
    }
}
