package com.yann.nowbarmirror.widget

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

/**
 * Persists the single notification currently shown in the lock-screen "Now Bar" widget: the
 * most recently mirrored notification from any app configured with a mirror mode (ALL or
 * LATEST alike — the widget makes no distinction between the two, unlike the system-notification
 * mirror in MirrorNotificationListener). Survives the listener service's process being killed
 * and restarted, since the widget can be tapped or dismissed at any time independently of
 * whether the app is currently running.
 *
 * Deliberately does NOT try to persist the notification's "open" PendingIntent here. A
 * PendingIntent's real state lives in the system process, and while it can be handed off
 * correctly through an actual Binder transaction (e.g. AppWidgetManager.updateAppWidget(),
 * exactly like NotificationManager.notify() already does for the system-notification mirror),
 * round-tripping it through Parcel.marshall()/unmarshall() into SharedPreferences and back does
 * NOT reliably reconstruct a working one — that was the earlier bug where the widget's tap
 * always fell back to opening the source app instead of the exact conversation/article. See
 * NowBarWidgetProvider.pushLive(), which is given the live PendingIntent directly instead.
 */
object WidgetNotificationStore {

    private const val PREFS_NAME = "widget_notification_prefs"
    private const val KEY_ORIGINAL_KEY = "key"
    private const val KEY_TITLE = "title"
    private const val KEY_TEXT = "text"
    private const val KEY_PACKAGE = "package"
    private const val IMAGE_FILE_NAME = "widget_notification_image.png"

    data class Data(
        val key: String,
        val title: String,
        val text: String,
        val packageName: String,
        val imageFile: File?
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun imageFile(context: Context) = File(context.filesDir, IMAGE_FILE_NAME)

    fun save(
        context: Context,
        key: String,
        title: String,
        text: String,
        packageName: String,
        image: Bitmap?
    ) {
        val file = imageFile(context)
        if (image != null) {
            try {
                FileOutputStream(file).use { out -> image.compress(Bitmap.CompressFormat.PNG, 100, out) }
            } catch (_: Throwable) {
                file.delete()
            }
        } else {
            file.delete()
        }

        prefs(context).edit().apply {
            putString(KEY_ORIGINAL_KEY, key)
            putString(KEY_TITLE, title)
            putString(KEY_TEXT, text)
            putString(KEY_PACKAGE, packageName)
            apply()
        }
    }

    fun clear(context: Context) {
        imageFile(context).delete()
        prefs(context).edit().clear().apply()
    }

    fun get(context: Context): Data? {
        val p = prefs(context)
        val key = p.getString(KEY_ORIGINAL_KEY, null) ?: return null
        val pkg = p.getString(KEY_PACKAGE, null) ?: return null
        val title = p.getString(KEY_TITLE, "") ?: ""
        val text = p.getString(KEY_TEXT, "") ?: ""
        val file = imageFile(context).takeIf { it.exists() }
        return Data(key, title, text, pkg, file)
    }
}
