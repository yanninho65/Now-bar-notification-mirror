package com.yann.nowbarmirror.settings

import android.content.Context
import org.json.JSONObject

/**
 * Serializes the per-app mirror modes (and the enabled/disabled switch) to a small JSON
 * document, and restores them from one. Uses org.json (built into Android) so no extra
 * dependency is needed.
 */
object SettingsBackup {

    private const val FORMAT_VERSION = 1

    fun export(context: Context): String {
        val modes = JSONObject()
        AppMirrorPrefs.getConfiguredPackages(context).forEach { (pkg, mode) ->
            modes.put(pkg, mode.name)
        }
        val inverted = org.json.JSONArray()
        AppMirrorPrefs.getInvertedPackages(context).forEach { inverted.put(it) }
        return JSONObject().apply {
            put("format_version", FORMAT_VERSION)
            put("service_enabled", ServicePrefs.isEnabled(context))
            put("modes", modes)
            put("invert_title_text", inverted)
        }.toString(2)
    }

    /** Replaces the current configuration with the one described by [json]. */
    fun import(context: Context, json: String) {
        val root = JSONObject(json)

        // Clear whatever is currently configured first, so an app removed from the backup
        // doesn't linger with its old mode.
        AppMirrorPrefs.getConfiguredPackages(context).keys.forEach { pkg ->
            AppMirrorPrefs.setMode(context, pkg, MirrorMode.NONE)
        }

        val modes = root.optJSONObject("modes") ?: JSONObject()
        modes.keys().forEach { pkg ->
            val mode = try {
                MirrorMode.valueOf(modes.getString(pkg))
            } catch (e: IllegalArgumentException) {
                MirrorMode.NONE
            }
            AppMirrorPrefs.setMode(context, pkg, mode)
        }

        // Same clear-then-restore approach as the modes above, so a package no longer
        // listed as inverted in the backup doesn't keep its old flag.
        AppMirrorPrefs.getInvertedPackages(context).forEach { pkg ->
            AppMirrorPrefs.setInvertTitleText(context, pkg, false)
        }
        root.optJSONArray("invert_title_text")?.let { inverted ->
            for (i in 0 until inverted.length()) {
                AppMirrorPrefs.setInvertTitleText(context, inverted.getString(i), true)
            }
        }

        if (root.has("service_enabled")) {
            ServicePrefs.setEnabled(context, root.getBoolean("service_enabled"))
        }
    }
}
