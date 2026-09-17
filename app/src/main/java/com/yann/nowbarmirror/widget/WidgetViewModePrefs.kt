package com.yann.nowbarmirror.widget

import android.content.Context

/**
 * Which of the widget's two views is currently showing: the usual "last notification" view, or
 * the Sofascore "Sport" view toggled on by the rotating-arrows button (see
 * NowBarWidgetProvider.applyViewToggle / onReceive(ACTION_TOGGLE_VIEW)). One value shared by
 * every placed widget instance — the simplest option, and the only one that matters here: Yann
 * places a single instance of this widget (lock screen via LockStar), so there's never more than
 * one to keep in sync.
 */
object WidgetViewModePrefs {

    private const val PREFS_NAME = "widget_view_mode_prefs"
    private const val KEY_SOFASCORE_ACTIVE = "sofascore_active"

    fun isSofascoreActive(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SOFASCORE_ACTIVE, false)

    fun toggle(context: Context) {
        val current = isSofascoreActive(context)
        prefs(context).edit().putBoolean(KEY_SOFASCORE_ACTIVE, !current).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
