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
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import com.yann.nowbarmirror.MirrorNotificationListener
import com.yann.nowbarmirror.R

/**
 * Home-screen App Widget (4x1, transparent background) meant to be placed on the lock screen
 * through a third-party lock-widget host such as Samsung's LockStar. Mirrors whatever
 * WidgetNotificationStore currently holds: the single most recently posted notification from
 * any app configured with a mirror mode, regardless of ALL vs LATEST.
 */
class NowBarWidgetProvider : AppWidgetProvider() {

    companion object {

        /** Pushes the current WidgetNotificationStore content to every placed instance. */
        fun requestUpdate(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NowBarWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = buildViews(context)
            ids.forEach { id -> manager.updateAppWidget(id, views) }
        }

        private fun buildViews(context: Context): RemoteViews {
            return try {
                buildViewsUnsafe(context)
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
            return views
        }

        private fun buildViewsUnsafe(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_now_bar)
            val data = WidgetNotificationStore.get(context)

            if (data == null) {
                return emptyViews(context)
            }

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

            val openIntent = data.contentIntent ?: launchAppPendingIntent(context, data.packageName)
            if (openIntent != null) {
                views.setOnClickPendingIntent(R.id.widget_root, openIntent)
            }

            return views
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
         * Best-effort fallback for the rare case where there's no restorable "open" PendingIntent
         * (e.g. it couldn't be reconstructed after a process restart): opens the source app
         * itself rather than leaving the tap dead.
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
