package com.yann.nowbarmirror.widget

import android.content.Context

/**
 * "Peek" state (NEW 18/09/2026, Yann: "cliquer sur une icône doit ouvrir le texte de la
 * notification en question sous le même format que la dernière notif affichée") — which single
 * tile from the SPORT or ALL_NOTIFS grid, if any, is currently being shown full-format (title,
 * text, image, dismiss button, action buttons — the EXACT SAME widget_latest_content block the
 * true LATEST view uses, see NowBarWidgetProvider.renderLatestFormat) instead of that view's
 * usual tile grid.
 *
 * Persisted (SharedPreferences), NOT just an in-memory companion var — same reasoning as
 * WidgetViewModePrefs: a tap reaches this app as a broadcast PendingIntent, which the system can
 * deliver to a freshly-spun-up process, so any state that has to survive that needs to be on
 * disk, not just in a static field.
 *
 * Deliberately does NOT persist the peeked entry's title/text/image/actions/dismiss target —
 * those are re-resolved at render time from whichever store (SofascoreWidgetStore /
 * WidgetAllNotificationsStore) still holds that entry (see NowBarWidgetProvider.resolvePeek). If
 * the entry is gone by then (aged out of the top-5, or dismissed from elsewhere), resolvePeek
 * returns null and the caller closes the peek automatically, falling back to that view's tile
 * grid — see NowBarWidgetProvider.buildViewsUnsafe and closePeekIfShowing.
 *
 * [entryId] uses each view's own tile identity: a SPORT match's own key (same id
 * SofascoreWidgetStore.Data.key uses), or, for ALL_NOTIFS, the composite
 * NowBarWidgetProvider.allNotifEntryId(key, postTimeMillis) — the same composite id
 * liveAllNotifIntents already keys by, since several ALL_NOTIFS tiles can share one underlying
 * notification key (a "Dernière notif"-mode app reusing its notification id across items).
 */
object WidgetPeekPrefs {

    enum class Source { SPORT, ALL_NOTIFS }

    private const val PREFS_NAME = "widget_peek_prefs"
    private const val KEY_ACTIVE = "active"
    private const val KEY_SOURCE = "source"
    private const val KEY_ENTRY_ID = "entry_id"

    data class Peek(val source: Source, val entryId: String)

    fun current(context: Context): Peek? {
        val p = prefs(context)
        if (!p.getBoolean(KEY_ACTIVE, false)) return null
        val source = p.getString(KEY_SOURCE, null)?.let { name ->
            try {
                Source.valueOf(name)
            } catch (_: Throwable) {
                null
            }
        } ?: return null
        val entryId = p.getString(KEY_ENTRY_ID, null) ?: return null
        return Peek(source, entryId)
    }

    fun open(context: Context, source: Source, entryId: String) {
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_SOURCE, source.name)
            .putString(KEY_ENTRY_ID, entryId)
            .apply()
    }

    fun close(context: Context) {
        prefs(context).edit().putBoolean(KEY_ACTIVE, false).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
