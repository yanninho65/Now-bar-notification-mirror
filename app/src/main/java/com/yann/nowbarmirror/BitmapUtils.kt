package com.yann.nowbarmirror

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream

/**
 * Shared bitmap helpers (AUDIT 23/09/2026 — harmonization + battery).
 *
 * Before this file, `drawableToBitmap` existed in four copies (MirrorNotificationListener,
 * NotificationImageExtractor, WatchNotificationSync, NowBarWidgetProvider), every widget rebuild
 * re-decoded every tile's PNG from disk once PER WIDGET (up to 3 widgets placed), re-rendered every
 * app icon from PackageManager and re-cropped it into a circle, and notification images were kept
 * at full resolution (a BigPictureStyle image can easily be 1000+ px) all the way to disk, to the
 * RemoteViews binder transaction and to the watch over Bluetooth.
 *
 * - [drawableToBitmap] / [circular] / [downscale]: the single implementation of each.
 * - [AppIcons]: in-memory cache of source-app icons (raw + circular), per package.
 * - [ImageFiles]: decode cache for the widget stores' image files. Safe to cache by path because
 *   those files are now content-addressed (one file name per notification posting, never
 *   rewritten with different content — see WidgetImageFiles), plus lastModified as a guard.
 *
 * Everything here is process-lifetime memory only, bounded by LruCache sizes, and rebuilt lazily
 * after a process restart — no behavior depends on it being warm.
 */
object BitmapUtils {

    /**
     * Longest side, in px, for any notification image persisted for the widgets or sent to the
     * watch. Widget tiles are ~40-48dp (≈120-144px at xxhdpi), the watch complication is composed
     * at 320px with the image drawn in a fraction of it — 256px keeps everything sharp while
     * capping memory/IO/Bluetooth cost for oversized BigPictureStyle images.
     */
    const val MAX_STORED_IMAGE_PX = 256

    fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    /** Returns [source] itself when it already fits within [maxPx], otherwise a proportionally scaled copy. */
    fun downscale(source: Bitmap, maxPx: Int = MAX_STORED_IMAGE_PX): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxPx || longest <= 0) return source
        val ratio = maxPx.toFloat() / longest
        val w = (source.width * ratio).toInt().coerceAtLeast(1)
        val h = (source.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, w, h, true)
    }

    /** Crops [source] into a circle, matching the round chips in the reference design. */
    fun circular(source: Bitmap): Bitmap {
        val size = minOf(source.width, source.height).coerceAtLeast(1)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawOval(RectF(0f, 0f, size.toFloat(), size.toFloat()), paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val left = (source.width - size) / 2f
        val top = (source.height - size) / 2f
        canvas.drawBitmap(source, -left, -top, paint)
        return output
    }

    /** Writes [bitmap] as PNG to [file]; deletes the partial file and returns false on failure. */
    fun writePng(bitmap: Bitmap, file: File): Boolean {
        return try {
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            true
        } catch (_: Throwable) {
            file.delete()
            false
        }
    }

    /** Source-app icons, cached per package (raw bitmap and its circular crop). */
    object AppIcons {
        private val raw = LruCache<String, Bitmap>(48)
        private val round = LruCache<String, Bitmap>(48)

        fun get(context: Context, packageName: String): Bitmap? {
            raw.get(packageName)?.let { return it }
            val bitmap = try {
                drawableToBitmap(context.packageManager.getApplicationIcon(packageName))
            } catch (_: Throwable) {
                return null
            }
            raw.put(packageName, bitmap)
            return bitmap
        }

        fun getCircular(context: Context, packageName: String): Bitmap? {
            round.get(packageName)?.let { return it }
            val source = get(context, packageName) ?: return null
            val bitmap = circular(source)
            round.put(packageName, bitmap)
            return bitmap
        }
    }

    /** Decoded widget-store image files (plain and circular), cached by path + lastModified. */
    object ImageFiles {
        // Sized in KB: each cached bitmap is at most MAX_STORED_IMAGE_PX² × 4 bytes (256 KB).
        private val decoded = object : LruCache<String, Bitmap>(6 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = (value.byteCount / 1024).coerceAtLeast(1)
        }
        private val round = object : LruCache<String, Bitmap>(3 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = (value.byteCount / 1024).coerceAtLeast(1)
        }

        private fun cacheKey(file: File) = "${file.path}@${file.lastModified()}"

        fun decode(file: File?): Bitmap? {
            if (file == null) return null
            val key = cacheKey(file)
            decoded.get(key)?.let { return it }
            val bitmap = try { BitmapFactory.decodeFile(file.path) } catch (_: Throwable) { null } ?: return null
            decoded.put(key, bitmap)
            return bitmap
        }

        fun decodeCircular(file: File?): Bitmap? {
            if (file == null) return null
            val key = cacheKey(file)
            round.get(key)?.let { return it }
            val source = decode(file) ?: return null
            val bitmap = circular(source)
            round.put(key, bitmap)
            return bitmap
        }
    }
}
