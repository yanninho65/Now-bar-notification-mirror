package com.yann.nowbarmirror.settings

import android.content.Context

/**
 * Apps whose notifications feed the watch "Messages" complication (NEW 24/09/2026) — independent
 * of the mirror mode (an app can be a message app without being mirrored to the Now Bar).
 * ORDERED since 24/09/2026 (Yann reorders them in MessageAppsActivity; the watch app rows follow
 * this order). Until Yann saves a choice, [DEFAULTS] is used (absent apps are harmless).
 */
object MessageAppsPrefs {

    private const val PREFS_NAME = "message_apps_prefs"
    private const val KEY_ORDERED = "packages_ordered"   // newline-separated, in display order
    private const val KEY_LEGACY_SET = "packages"         // unordered set, before 24/09/2026

    val DEFAULTS = listOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "org.thoughtcrime.securesms",
        "org.telegram.messenger",
        "com.facebook.orca"
    )

    @Volatile private var cachedRaw: String? = null
    @Volatile private var cachedList: List<String> = emptyList()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Selected packages, in Yann's order. */
    fun getOrdered(context: Context): List<String> {
        val p = prefs(context)
        val raw = p.getString(KEY_ORDERED, null)
        if (raw != null) {
            if (raw != cachedRaw) {
                cachedList = raw.split('\n').filter { it.isNotBlank() }
                cachedRaw = raw
            }
            return cachedList
        }
        return p.getStringSet(KEY_LEGACY_SET, null)?.sorted() ?: DEFAULTS
    }

    fun get(context: Context): Set<String> = LinkedHashSet(getOrdered(context))

    fun isMessageApp(context: Context, packageName: String): Boolean = packageName in getOrdered(context)

    fun setOrdered(context: Context, packages: List<String>) {
        prefs(context).edit()
            .putString(KEY_ORDERED, packages.filter { it.isNotBlank() }.distinct().joinToString("\n"))
            .remove(KEY_LEGACY_SET)
            .apply()
    }
}
