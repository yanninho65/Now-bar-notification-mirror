package com.yann.nowbarmirror.settings

import android.content.Context

/**
 * A simple in-app pause switch for the mirroring feature. When disabled, the listener stops
 * creating or updating mirrors for newly-posted notifications, but keeps reacting to removals
 * (so the two-way delete sync stays consistent for whatever mirrors are already showing).
 */
object ServicePrefs {

    private const val PREFS_NAME = "service_prefs"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}
