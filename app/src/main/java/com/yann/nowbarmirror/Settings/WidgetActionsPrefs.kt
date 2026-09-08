package com.yann.nowbarmirror.settings

import android.content.Context

/**
 * Whether the lock-screen "Now Bar" widget should show up to two of the notification's own
 * action buttons (e.g. "Reply", "Mark as read") next to the dismiss button. Off by default:
 * the widget is meant to stay compact, so this is opt-in.
 *
 * The buttons only work while this app's process is still alive since the notification was
 * mirrored (see the liveActions doc in NowBarWidgetProvider) — after a process restart they
 * simply don't show again until the next notification arrives, same limitation the widget's
 * tap-to-open already has.
 */
object WidgetActionsPrefs {

    private const val PREFS_NAME = "widget_actions_prefs"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}
