package com.yann.nowbarmirror.settings

import android.content.Context

/**
 * An ordered list of apps chosen by Yann for a watch screen (UPDATED 25/09/2026: shared base of
 * [MessageAppsPrefs] and [SportAppsPrefs], edited by the same MessageAppsActivity). Stored as a
 * newline-joined string, in display order; [defaults] until Yann saves a choice (absent apps are
 * harmless). [legacySetKey]: unordered set written by older versions, read as a fallback.
 */
open class OrderedAppsPrefs(
    private val prefsName: String,
    val defaults: List<String>,
    private val legacySetKey: String? = null
) {
    private val keyOrdered = "packages_ordered"

    @Volatile private var cachedRaw: String? = null
    @Volatile private var cachedList: List<String> = emptyList()

    private fun prefs(context: Context) =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    /** Selected packages, in Yann's order. */
    fun getOrdered(context: Context): List<String> {
        val p = prefs(context)
        val raw = p.getString(keyOrdered, null)
        if (raw != null) {
            if (raw != cachedRaw) {
                cachedList = raw.split('\n').filter { it.isNotBlank() }
                cachedRaw = raw
            }
            return cachedList
        }
        return legacySetKey?.let { p.getStringSet(it, null)?.sorted() } ?: defaults
    }

    fun get(context: Context): Set<String> = LinkedHashSet(getOrdered(context))

    fun contains(context: Context, packageName: String): Boolean = packageName in getOrdered(context)

    fun setOrdered(context: Context, packages: List<String>) {
        prefs(context).edit().apply {
            putString(keyOrdered, packages.filter { it.isNotBlank() }.distinct().joinToString("\n"))
            legacySetKey?.let { remove(it) }
        }.apply()
    }
}

/**
 * Apps whose notifications feed the watch "Messages" complication (NEW 24/09/2026) — independent
 * of the mirror mode (an app can be a message app without being mirrored to the Now Bar).
 * Ordered: the watch's app row follows this order.
 */
object MessageAppsPrefs : OrderedAppsPrefs(
    prefsName = "message_apps_prefs",
    defaults = listOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "org.thoughtcrime.securesms",
        "org.telegram.messenger",
        "com.facebook.orca"
    ),
    legacySetKey = "packages"
) {
    fun isMessageApp(context: Context, packageName: String): Boolean = contains(context, packageName)
}
