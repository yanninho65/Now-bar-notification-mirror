package com.yann.nowbarmirror.widget

import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Parcel
import android.util.Base64
import java.io.File
import java.io.FileOutputStream

/**
 * Persists the single notification currently shown in the lock-screen "Now Bar" widget: the
 * most recently mirrored notification from any app configured with a mirror mode (ALL or
 * LATEST alike — the widget makes no distinction between the two, unlike the system-notification
 * mirror in MirrorNotificationListener). Survives the listener service's process being killed
 * and restarted, since the widget can be tapped or dismissed at any time independently of
 * whether the app is currently running.
 */
object WidgetNotificationStore {

    private const val PREFS_NAME = "widget_notification_prefs"
    private const val KEY_ORIGINAL_KEY = "key"
    private const val KEY_TITLE = "title"
    private const val KEY_TEXT = "text"
    private const val KEY_PACKAGE = "package"
    private const val KEY_CONTENT_INTENT = "content_intent"
    private const val IMAGE_FILE_NAME = "widget_notification_image.png"

    data class Data(
        val key: String,
        val title: String,
        val text: String,
        val packageName: String,
        val contentIntent: PendingIntent?,
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
        contentIntent: PendingIntent?,
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
            if (contentIntent != null) {
                putString(KEY_CONTENT_INTENT, marshall(contentIntent))
            } else {
                remove(KEY_CONTENT_INTENT)
            }
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
        val contentIntent = p.getString(KEY_CONTENT_INTENT, null)?.let(::unmarshall)
        val file = imageFile(context).takeIf { it.exists() }
        return Data(key, title, text, pkg, contentIntent, file)
    }

    /**
     * PendingIntents can't be serialized to plain SharedPreferences values directly, but they
     * are Parcelable — and unlike most Parcelables, their real state lives in the system process
     * (ActivityManagerService), not in ours. Marshalling one to bytes and unmarshalling it back
     * later, even from a freshly restarted process, restores a working PendingIntent as long as
     * the device hasn't rebooted since it was created and the source app is still installed.
     * That's what lets the widget's "open" tap keep working after this app's own process has
     * been killed and restarted by the system in the background.
     */
    private fun marshall(pendingIntent: PendingIntent): String {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeParcelable(pendingIntent, 0)
            Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP)
        } finally {
            parcel.recycle()
        }
    }

    private fun unmarshall(encoded: String): PendingIntent? {
        val parcel = Parcel.obtain()
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            if (Build.VERSION.SDK_INT >= 33) {
                parcel.readParcelable(PendingIntent::class.java.classLoader, PendingIntent::class.java)
            } else {
                @Suppress("DEPRECATION")
                parcel.readParcelable(PendingIntent::class.java.classLoader)
            }
        } catch (_: Throwable) {
            null
        } finally {
            parcel.recycle()
        }
    }
}
