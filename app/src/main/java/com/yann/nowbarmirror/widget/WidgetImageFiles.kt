package com.yann.nowbarmirror.widget

import android.content.Context
import android.graphics.Bitmap
import com.yann.nowbarmirror.BitmapUtils
import java.io.File

/**
 * Content-addressed image files shared by [WidgetAllNotificationsStore] and [SofascoreWidgetStore]
 * (AUDIT 23/09/2026 — battery).
 *
 * Both stores used to name their image files by SLOT INDEX, so every save (i.e. every notification
 * event, plus every refill after a removal) had to delete every file, decode every kept image back
 * into a Bitmap, and re-encode all of them as PNG — up to 12 PNG encodes for the "Toutes notifs"
 * history and 6 more for the Sport list on a single Sofascore goal. Files are now named after the
 * notification POSTING they belong to (key + postTime): the same posting always has the same image,
 * so an image already on disk is simply kept as-is, and only a genuinely new posting costs one
 * encode. Files no longer referenced by the saved list are deleted at the end of [commit].
 *
 * An image can be handed over either eagerly ([Bitmap]) or lazily (a loader) — a loader is only
 * invoked when the file doesn't exist yet, which is what lets the listeners skip image EXTRACTION
 * too (not just the encode) for postings already stored.
 */
internal class WidgetImageFiles(private val context: Context, private val prefix: String) {

    private val kept = mutableSetOf<String>()

    /** Stable file name for one notification posting. */
    fun nameFor(key: String, postTimeMillis: Long): String =
        "$prefix${Integer.toHexString(key.hashCode())}_${key.length}_$postTimeMillis.png"

    /**
     * Ensures the image for this posting is on disk and returns its file name (null when there's
     * no image at all). [existing] is the file this entry already had before this save (a legacy
     * slot-indexed file is migrated by renaming it). [image]/[loader] are only used when nothing
     * is on disk yet.
     */
    fun persist(
        key: String,
        postTimeMillis: Long,
        existing: File?,
        image: Bitmap?,
        loader: (() -> Bitmap?)?
    ): String? {
        val name = nameFor(key, postTimeMillis)
        val target = File(context.filesDir, name)
        val ok = when {
            target.exists() -> true
            existing != null && existing.exists() && existing.renameTo(target) -> true
            else -> {
                val bitmap = image ?: try { loader?.invoke() } catch (_: Throwable) { null }
                bitmap != null && BitmapUtils.writePng(BitmapUtils.downscale(bitmap), target)
            }
        }
        if (!ok) return null
        kept += name
        return name
    }

    /** Deletes every file of this store's [prefix] that the list just saved no longer references. */
    fun commit() {
        context.filesDir.listFiles()?.forEach { file ->
            val name = file.name
            if (name.startsWith(prefix) && name.endsWith(".png") && name !in kept) file.delete()
        }
    }

    companion object {
        /** File for a name read back from a store's JSON, or null if missing on disk. */
        fun resolve(context: Context, name: String?): File? =
            name?.let { File(context.filesDir, it) }?.takeIf { it.exists() }
    }
}

/**
 * Tiny parse cache for a store's JSON string (AUDIT 23/09/2026): a single widget refresh used to
 * call `WidgetAllNotificationsStore.get()` 5-10 times across the three widgets and the watch sync,
 * each one re-parsing the same JSON. SharedPreferences already keeps the raw string in memory, so
 * comparing it to the last parsed one is enough to know the parsed list is still valid.
 */
internal class ParsedCache<T> {
    private var raw: String? = null
    private var value: T? = null

    @Synchronized
    fun getOrParse(current: String, parse: (String) -> T): T {
        val cached = value
        if (cached != null && current == raw) return cached
        val parsed = parse(current)
        raw = current
        value = parsed
        return parsed
    }
}
