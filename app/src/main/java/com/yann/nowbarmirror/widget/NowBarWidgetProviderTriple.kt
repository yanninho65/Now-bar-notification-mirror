package com.yann.nowbarmirror.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import com.yann.nowbarmirror.R

/**
 * Third home-screen/lock-screen widget (NEW 23/09/2026 — Yann: "widget 4x2 : créer un nouveau qui
 * est comme l'actuel mais avec deux lignes d'icônes : une dernière notif et une sofascore. Ne pas
 * afficher les Sofascore dans dernière notif. 5 icônes par ligne toujours. Vue texte en bas
 * toujours.") — see widget_now_bar_triple.xml's own class doc for the exact layout.
 *
 * Unlike NowBarWidgetProviderCompact (ONE icon row, toggled between Sport and Toutes notifs), this
 * widget shows BOTH icon rows permanently, stacked, with no toggle between them at all:
 * - ROW 1 ("Dernière notif") — reuses [NowBarWidgetProvider.applyAllNotifs]/[NowBarWidgetProvider.applyAllNotifsIcon],
 *   same ids (widget_all_notifs_content/widget_notif_1..5) as the other two widgets' own
 *   Toutes-notifs view, fed [WidgetAllNotificationsStore]'s history FILTERED to [WidgetAllNotificationsStore.Kind.GENERIC]
 *   only ("Ne pas afficher les Sofascore dans dernière notif" — a Sofascore match never appears
 *   here, even when it happens to be the single most recent entry of any kind) — see [applyRows].
 * - ROW 2 ("Sofascore") — reuses [NowBarWidgetProvider.applySofascoreMatches]/[NowBarWidgetProvider.applySofascoreIcon],
 *   same ids (widget_sofascore_content/widget_match_1..5) as the other two widgets' own Sport view,
 *   every currently active Sofascore match — nothing to filter by kind here, this row IS the
 *   Sofascore one.
 * - ROW 3 ("Vue texte", "toujours en bas") — the exact same "Dernière notif"/peek rendering as
 *   NowBarWidgetProviderCompact's own row 2 (applyLatestContent/renderLatestFormat/
 *   applyPeekLeftColumn/resolvePeek), unconditionally at the bottom, same ids — a tap on any row
 *   1/2 tile still opens the shared "peek" here exactly as it does on the other two widgets
 *   (WidgetPeekPrefs is a single, app-wide pointer — opening a peek from this widget also flips
 *   whichever other widget is currently showing that same "Dernière notif" content into peek mode,
 *   exactly as already happens between the main and compact widgets today).
 *
 * BOTH icon rows exclude whichever entry ROW 3 is currently showing as "Dernière notif" — not
 * whatever's peeked (same reasoning as NowBarWidgetProviderCompact.applyIconsRow's own doc: "la
 * dernière notif n'apparaît pas car est dans vue texte" applies to both rows here, not just one),
 * so browsing rows 1/2 never flickers as the peek target changes. In practice this second filter
 * only ever thins out ROW 2 (Sofascore): ROW 1 has ALREADY dropped every Sofascore entry via the
 * kind filter above, so "the current Dernière notif happens to be a Sofascore match" can only ever
 * remove a tile from row 2, never row 1.
 *
 * Own PendingIntent request-code range ([PEEK_REQUEST_CODE_ALL_NOTIFS_BASE]/[PEEK_REQUEST_CODE_SPORT_BASE]),
 * distinct from BOTH other widgets' own bases (4300/4400 main, 4700/4800 compact), so a tile here
 * never shares a PendingIntent identity with either of theirs at the same slot index.
 *
 * "5 icônes par ligne toujours" — [WidgetAllNotificationsStore.MAX_SLOTS] (6) already reserves one
 * spare slot so ROW 1 still shows up to 5 once the current "Dernière notif" is filtered out of it
 * (same mechanism NowBarWidgetProviderCompact's own single icon row already relies on — see that
 * constant's doc). There is no equivalent reserve for ROW 1's OWN "no Sofascore" filter: if fewer
 * than 5 GENERIC entries are currently in the shared history (e.g. several Sofascore matches
 * crowding out the 6 stored slots), row 1 simply shows however many are actually available — same
 * "up to 5" rule every other tile row in this app already follows, nothing new here.
 */
class NowBarWidgetProviderTriple : AppWidgetProvider() {

    companion object {

        // Distinct from NowBarWidgetProvider's own (4300/4400) and NowBarWidgetProviderCompact's
        // own (4700/4800) — see the class doc's PendingIntent-identity note.
        private const val PEEK_REQUEST_CODE_ALL_NOTIFS_BASE = 4900
        private const val PEEK_REQUEST_CODE_SPORT_BASE = 5000

        /** Called from NowBarWidgetProvider.pushToAllWidgets — see the class doc. No-op if this widget isn't currently placed. */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NowBarWidgetProviderTriple::class.java))
            if (ids.isEmpty()) return
            val views = buildViews(context)
            ids.forEach { id -> manager.updateAppWidget(id, views) }
        }

        private fun buildViews(context: Context): RemoteViews {
            return try {
                buildViewsUnsafe(context)
            } catch (t: Throwable) {
                // Same "never let a rendering bug crash the shared app process" fallback as
                // NowBarWidgetProvider.buildViews/NowBarWidgetProviderCompact.buildViews.
                Toast.makeText(
                    context,
                    "Widget 4x2 (double icônes): ${t.javaClass.simpleName}: ${t.message}",
                    Toast.LENGTH_LONG
                ).show()
                emptyViews(context)
            }
        }

        private fun emptyViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_now_bar_triple)
            views.setViewVisibility(R.id.widget_sofascore_content, View.GONE)
            views.setViewVisibility(R.id.widget_all_notifs_content, View.GONE)
            views.setImageViewResource(R.id.widget_triple_notifs_icon, R.drawable.ic_notification_bell)
            views.setImageViewResource(R.id.widget_triple_sofascore_icon, R.drawable.ic_football_pitch)
            views.setViewVisibility(R.id.widget_latest_content, View.VISIBLE)
            views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_empty_title))
            views.setTextViewText(R.id.widget_text, "")
            views.setImageViewResource(R.id.widget_app_icon, R.drawable.ic_notification_bell)
            views.setViewVisibility(R.id.widget_image, View.GONE)
            views.setViewVisibility(R.id.widget_dismiss, View.GONE)
            views.setViewVisibility(R.id.widget_actions_container, View.GONE)
            views.setViewVisibility(R.id.widget_view_toggle, View.GONE)
            return views
        }

        private fun buildViewsUnsafe(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_now_bar_triple)

            // Row 3 — "Dernière notif" or peek, exact same rendering as the other two widgets' own
            // LATEST view (see the class doc). widget_view_toggle only has a job here while
            // peeking (closing it), same as NowBarWidgetProviderCompact's own row 2 — no other
            // row-3 "view" to switch to otherwise, so it stays GONE the rest of the time.
            val peek = WidgetPeekPrefs.current(context)
            val resolvedPeek = peek?.let { NowBarWidgetProvider.resolvePeek(context, it) }
            if (peek != null && resolvedPeek == null) {
                NowBarWidgetProvider.closePeekAndCancelAlarm(context)
            }
            if (resolvedPeek != null) {
                NowBarWidgetProvider.renderLatestFormat(
                    context,
                    views,
                    title = resolvedPeek.title,
                    text = resolvedPeek.text,
                    image = resolvedPeek.image,
                    dismissIntent = resolvedPeek.dismissIntent,
                    openIntent = resolvedPeek.openIntent,
                    actions = resolvedPeek.actions,
                    entryKey = resolvedPeek.entryKey,
                    entryPostTimeMillis = resolvedPeek.entryPostTimeMillis,
                    isMatch = resolvedPeek.isMatch
                )
                NowBarWidgetProvider.applyPeekLeftColumn(context, views, resolvedPeek.iconPackageName)
            } else {
                NowBarWidgetProvider.applyLatestContent(context, views)
                views.setViewVisibility(R.id.widget_view_toggle, View.GONE)
            }

            // Rows 1 & 2 — icon strips, independent of whether row 3 is currently peeking.
            applyRows(context, views)

            return views
        }

        /**
         * Row 1 ("Dernière notif", GENERIC only — "Ne pas afficher les Sofascore dans dernière
         * notif") + Row 2 ("Sofascore", every currently active match) — both always visible, both
         * excluding whichever entry is currently shown as row 3's own "Dernière notif" — see the
         * class doc.
         */
        private fun applyRows(context: Context, views: RemoteViews) {
            val latest = WidgetAllNotificationsStore.get(context).firstOrNull()

            NowBarWidgetProvider.applyAllNotifsIcon(context, views, R.id.widget_triple_notifs_icon)
            val notifEntries = WidgetAllNotificationsStore.get(context)
                .filter { it.kind == WidgetAllNotificationsStore.Kind.GENERIC }
                .filterNot { latest != null && it.key == latest.key && it.postTimeMillis == latest.postTimeMillis }
            NowBarWidgetProvider.applyAllNotifs(context, views, notifEntries, PEEK_REQUEST_CODE_ALL_NOTIFS_BASE)

            NowBarWidgetProvider.applySofascoreIcon(context, views, R.id.widget_triple_sofascore_icon)
            val matches = SofascoreWidgetStore.get(context)
                .sortedForWidget(
                    nowMillis = System.currentTimeMillis(),
                    statusOf = { it.status },
                    apiSourceOf = { it.apiSource },
                    postTimeOf = { it.postTimeMillis }
                )
                .filterNot { latest != null && latest.kind == WidgetAllNotificationsStore.Kind.SOFASCORE_MATCH && it.key == latest.key }
            NowBarWidgetProvider.applySofascoreMatches(context, views, matches, PEEK_REQUEST_CODE_SPORT_BASE)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = buildViews(context)
        appWidgetIds.forEach { id -> appWidgetManager.updateAppWidget(id, views) }
    }
}
