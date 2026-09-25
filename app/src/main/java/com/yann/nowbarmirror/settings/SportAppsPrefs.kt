package com.yann.nowbarmirror.settings

import android.content.Context

/**
 * NEW 25/09/2026 — sport apps shown (with their notification count) in the app row at the top of
 * the watch "Sport" screen (wear/SportActivity), same principle and same editing screen as
 * [MessageAppsPrefs] (MessageAppsActivity, opened from the phone's Sport screen). Only the row:
 * the match list below is always Sofascore's notifications.
 */
object SportAppsPrefs : OrderedAppsPrefs(
    prefsName = "sport_apps_prefs",
    defaults = listOf("com.sofascore.results")
) {
    fun isSportApp(context: Context, packageName: String): Boolean = contains(context, packageName)
}
