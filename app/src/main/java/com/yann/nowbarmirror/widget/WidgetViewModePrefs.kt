package com.yann.nowbarmirror.widget

import android.content.Context

/**
 * Which of the widget's THREE views is currently showing (extended 17/09/2026 at Yann's request
 * from the original two — see NowBarWidgetProvider's class doc):
 * - [WidgetView.LATEST]: the usual "last notification" detailed view (dismiss button, actions
 *   row).
 * - [WidgetView.SPORT]: the Sofascore "Sport" view (up to 5 matches).
 * - [WidgetView.ALL_NOTIFS]: the "Toutes notifs" view (up to 5 recently received notifications,
 *   Sofascore ones kept in their match-tile presentation — see WidgetAllNotificationsStore).
 *
 * TWO separate toggle buttons, on purpose (see widget_now_bar.xml's class doc and
 * NowBarWidgetProvider.applyLeftToggle/applyRightToggle):
 * - the LEFT button (under the app icon, pre-existing) always gets Yann back to LATEST from
 *   whichever of SPORT/ALL_NOTIFS he's looking at, and from LATEST takes him to whichever of the
 *   two he looked at last (remembered in [KEY_LAST_NON_LATEST_VIEW] rather than defaulting back to
 *   SPORT every time) — this preserves exactly what the single left button already did before this
 *   change (LATEST <-> "the other view"), just generalized from one other view to a remembered
 *   choice of two.
 * - the RIGHT button (NEW, added 17/09/2026 — "Sur la droite, ajouter un bouton pour changer vue
 *   entre sport et toutes notifs") flips directly and only between SPORT and ALL_NOTIFS — see
 *   [toggleSportAllNotifs]. It has no effect on/from LATEST (NowBarWidgetProvider only shows it
 *   while SPORT or ALL_NOTIFS is on screen), so there's no ambiguity about what it does.
 *
 * One value shared by every placed widget instance — the simplest option, and the only one that
 * matters here: Yann places a single instance of this widget (lock screen via LockStar), so
 * there's never more than one to keep in sync.
 */
object WidgetViewModePrefs {

    enum class WidgetView { LATEST, SPORT, ALL_NOTIFS }

    private const val PREFS_NAME = "widget_view_mode_prefs"
    private const val KEY_CURRENT_VIEW = "current_view"
    private const val KEY_LAST_NON_LATEST_VIEW = "last_non_latest_view"

    // Pre-17/09/2026 boolean pref, read once for migration in currentView() below so upgrading
    // the app doesn't silently reset Yann back to LATEST if he had the Sport view showing.
    private const val LEGACY_KEY_SOFASCORE_ACTIVE = "sofascore_active"

    fun currentView(context: Context): WidgetView {
        val p = prefs(context)
        val stored = p.getString(KEY_CURRENT_VIEW, null)
        if (stored != null) return parseView(stored, WidgetView.LATEST)

        // Migration from the old two-view boolean pref (see class doc) — runs at most once,
        // since KEY_CURRENT_VIEW is written right after so `stored` is non-null on every later call.
        val legacySofascoreActive = p.getBoolean(LEGACY_KEY_SOFASCORE_ACTIVE, false)
        val migrated = if (legacySofascoreActive) WidgetView.SPORT else WidgetView.LATEST
        setView(context, migrated)
        if (migrated != WidgetView.LATEST) setLastNonLatestView(context, migrated)
        return migrated
    }

    /** LEFT button — see class doc. */
    fun toggleLatest(context: Context) {
        val current = currentView(context)
        if (current == WidgetView.LATEST) {
            setView(context, lastNonLatestView(context))
        } else {
            setLastNonLatestView(context, current)
            setView(context, WidgetView.LATEST)
        }
    }

    /** RIGHT button — see class doc. No-op (stays put) if somehow called while on LATEST. */
    fun toggleSportAllNotifs(context: Context) {
        val current = currentView(context)
        val next = when (current) {
            WidgetView.SPORT -> WidgetView.ALL_NOTIFS
            WidgetView.ALL_NOTIFS -> WidgetView.SPORT
            WidgetView.LATEST -> return
        }
        setView(context, next)
        setLastNonLatestView(context, next)
    }

    private fun lastNonLatestView(context: Context): WidgetView =
        parseView(prefs(context).getString(KEY_LAST_NON_LATEST_VIEW, null), WidgetView.SPORT)

    private fun setLastNonLatestView(context: Context, view: WidgetView) {
        prefs(context).edit().putString(KEY_LAST_NON_LATEST_VIEW, view.name).apply()
    }

    private fun setView(context: Context, view: WidgetView) {
        prefs(context).edit().putString(KEY_CURRENT_VIEW, view.name).apply()
    }

    private fun parseView(raw: String?, default: WidgetView): WidgetView =
        raw?.let { name -> try { WidgetView.valueOf(name) } catch (_: Throwable) { null } } ?: default

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
