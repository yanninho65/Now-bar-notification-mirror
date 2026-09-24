package com.yann.nowbarmirror.settings

import android.content.Context

/**
 * Apps whose notifications feed the watch "Messages" complication (NEW 24/09/2026) — independent
 * of the mirror mode (an app can be a message app without being mirrored to the Now Bar).
 * Until Yann saves a choice, [DEFAULTS] is used (common messaging apps; absent ones are harmless).
 */
object MessageAppsPrefs {

    private const val PREFS_NAME = "message_apps_prefs"
    private const val KEY_PACKAGES = "packages"

    val DEFAULTS = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "org.thoughtcrime.securesms",
        "org.telegram.messenger",
        "com.facebook.orca"
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_PACKAGES, null)?.toSet() ?: DEFAULTS

    fun isMessageApp(context: Context, packageName: String): Boolean = packageName in get(context)

    fun set(context: Context, packages: Set<String>) {
        prefs(context).edit().putStringSet(KEY_PACKAGES, HashSet(packages)).apply()
    }
}
