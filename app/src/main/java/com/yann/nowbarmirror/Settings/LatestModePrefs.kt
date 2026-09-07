package com.yann.nowbarmirror.settings

import android.content.Context

/**
 * Controls what happens to the shared "Dernière notif" slot when the notification currently
 * shown in it (or its mirror) gets dismissed, while other "Dernière notif" originals are still
 * active elsewhere:
 * - enabled (default): the slot is re-posted with the next-most-recent survivor.
 * - disabled: the old behavior — the slot is simply cleared, like before this option existed.
 */
object LatestModePrefs {

    private const val PREFS_NAME = "latest_mode_prefs"
    private const val KEY_FALLBACK_ENABLED = "fallback_enabled"

    fun isFallbackEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FALLBACK_ENABLED, true)

    fun setFallbackEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FALLBACK_ENABLED, enabled)
            .apply()
    }
}
