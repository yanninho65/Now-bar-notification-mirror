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
    private const val KEY_INVERT_PREFIX = "invert_"

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

    /**
     * Whether [packageName]'s title and text should be swapped before mirroring, so the
     * pill's short text (which prefers the original text field) ends up showing the
     * original title instead. Defaults to false; independent from the mirror mode.
     */
    fun getInvertTitleText(context: Context, packageName: String): Boolean =
        prefs(context).getBoolean(KEY_INVERT_PREFIX + packageName, false)

    fun setInvertTitleText(context: Context, packageName: String, invert: Boolean) {
        prefs(context).edit().apply {
            if (invert) putBoolean(KEY_INVERT_PREFIX + packageName, true)
            else remove(KEY_INVERT_PREFIX + packageName)
            apply()
        }
    }

    /** Packages currently flagged to have their title/text swapped. */
    fun getInvertedPackages(context: Context): Set<String> {
        return prefs(context).all
            .asSequence()
            .filter { it.key.startsWith(KEY_INVERT_PREFIX) && it.value == true }
            .map { it.key.removePrefix(KEY_INVERT_PREFIX) }
            .toSet()
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
