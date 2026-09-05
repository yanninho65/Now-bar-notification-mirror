package com.yann.nowbarmirror.settings

import android.content.Context

/**
 * Stores, per source package name, whether its notifications should be
 * mirrored at all, and if so whether only the latest one is kept (LATEST,
 * sharing one slot with every other LATEST-mode app) or every notification
 * gets its own persistent mirror (ALL).
 *
 * An app with no entry is treated as NONE (opt-in model: nothing is
 * mirrored until explicitly selected in AppSelectionActivity).
 */
object AppMirrorPrefs {

    private const val PREFS_NAME = "app_mirror_prefs"
    private const val KEY_PREFIX = "mode_"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getMode(context: Context, packageName: String): MirrorMode {
        val raw = prefs(context).getString(KEY_PREFIX + packageName, null)
            ?: return MirrorMode.NONE
        return try {
            MirrorMode.valueOf(raw)
        } catch (e: IllegalArgumentException) {
            MirrorMode.NONE
        }
    }

    fun setMode(context: Context, packageName: String, mode: MirrorMode) {
        prefs(context).edit().apply {
            if (mode == MirrorMode.NONE) {
                remove(KEY_PREFIX + packageName)
            } else {
                putString(KEY_PREFIX + packageName, mode.name)
            }
            apply()
        }
    }

    /** Packages currently configured with a mode other than NONE. */
    fun getConfiguredPackages(context: Context): Map<String, MirrorMode> {
        return prefs(context).all
            .asSequence()
            .filter { it.key.startsWith(KEY_PREFIX) }
            .mapNotNull { (key, value) ->
                val pkg = key.removePrefix(KEY_PREFIX)
                val mode = (value as? String)?.let {
                    try {
                        MirrorMode.valueOf(it)
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                }
                mode?.let { pkg to it }
            }
            .toMap()
    }
}
