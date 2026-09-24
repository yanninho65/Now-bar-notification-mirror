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
 * Second, 4x2 home-screen/lock-screen widget (NEW 22/09/2026, REVISED 23/09/2026 — Yann: "Pour la
 * partie icône, il faut que ça soit exactement la même chose que la vue icône du widget 4x1 : 5
 * icônes, titres, icône cloche ou sport sur la gauche. La difference est seulement que la dernière
 * notif n'apparaît pas car est dans vue texte") — see widget_now_bar_compact.xml's class doc for
 * the exact layout.
 *
 * Deliberately reuses NowBarWidgetProvider's own rendering functions rather than duplicating them,
 * so this row is not just visually similar but IDENTICAL to the main widget's Sport/Toutes-notifs
 * view — any future tweak to a tile's appearance there (Yann already has a standing rule that a
 * Sofascore tile's look must stay aligned between the Sport and Toutes-notifs views — see
 * NowBarWidgetProvider's class doc) applies here for free too, with nothing to keep in sync by hand:
 * - Row 1's tiles are rendered by the EXACT SAME [NowBarWidgetProvider.applySofascoreMatches]/
 *   [NowBarWidgetProvider.applyAllNotifs] the main widget's own Sport/Toutes-notifs view uses, on
 *   the SAME ids (widget_sofascore_content/widget_match_1..5, widget_all_notifs_content/
 *   widget_notif_1..5 — copied verbatim into widget_now_bar_compact.xml) — just fed a list with
 *   whichever entry is currently "Dernière notif" filtered out first (see [applyIconsRow]), and
 *   with this widget's OWN peek request-code range ([PEEK_REQUEST_CODE_SPORT_BASE]/
 *   [PEEK_REQUEST_CODE_ALL_NOTIFS_BASE], distinct from the main widget's own bases) so a tile here
 *   never shares a PendingIntent identity with the main widget's own tile at the same slot index.
 * - The left-column icon (football pitch / bell) and the right toggle are rendered by the SAME
 *   [NowBarWidgetProvider.applySofascoreIcon]/[NowBarWidgetProvider.applyAllNotifsIcon]/
 *   [NowBarWidgetProvider.applyRightToggle] the main widget uses — the icon functions take this
 *   widget's own [R.id.widget_compact_row1_icon] instead of widget_app_icon (already row 2's
 *   here), the toggle reuses widget_view_toggle_right verbatim (same id, same PendingIntent).
 * - Row 2 ("Dernière notif" / peek) is rendered with the exact same
 *   [NowBarWidgetProvider.applyLatestContent]/[NowBarWidgetProvider.renderLatestFormat]/
 *   [NowBarWidgetProvider.applyPeekLeftColumn]/[NowBarWidgetProvider.resolvePeek] this widget's
 *   own LATEST view and peek feature already use — same ids, same WidgetPeekPrefs state, same 15s
 *   auto-close alarm (scheduled by NowBarWidgetProvider.onReceive's ACTION_OPEN_PEEK branch — this
 *   class never schedules or cancels that alarm itself).
 * - Row 1's Sport/Toutes-notifs choice is the SAME shared value as the main widget's own right
 *   toggle (see WidgetViewModePrefs.sportOrAllNotifsView/toggleSportOrAllNotifsView) — flipping
 *   either widget's toggle moves both.
 * - [NowBarWidgetProvider.pushToAllWidgets] (the one choke point every state change already goes
 *   through — peek open/close/auto-close, view toggle, a new Sofascore/notification push) calls
 *   [refreshAll] after updating its own widget instances, so this widget picks up every one of
 *   those existing call sites for free — nothing here schedules its own refresh outside [onUpdate].
 *
 * This class therefore only owns its own [buildViewsUnsafe] (assembling the two rows from
 * whichever store/pref state is current) and the "exclude whatever's already shown as Dernière
 * notif" filtering row 1 needs before handing its list to the shared rendering functions.
 */
class NowBarWidgetProviderCompact : AppWidgetProvider() {

    companion object {

        // Distinct from NowBarWidgetProvider's own PEEK_REQUEST_CODE_SPORT_BASE (4300)/
        // PEEK_REQUEST_CODE_ALL_NOTIFS_BASE (4400) — see the class doc's PendingIntent-identity note.
        private const val PEEK_REQUEST_CODE_SPORT_BASE = 4700
        private const val PEEK_REQUEST_CODE_ALL_NOTIFS_BASE = 4800

        /** Called from NowBarWidgetProvider.pushToAllWidgets — see the class doc. No-op if this widget isn't currently placed. */
        fun refreshAll(context: Context) {
            // Shared with the other 4x2 widget (AUDIT 23/09/2026) — see NowBarWidgetProvider.refreshProvider.
            NowBarWidgetProvider.refreshProvider(context, NowBarWidgetProviderCompact::class.java, ::buildViews)
        }

        private fun buildViews(context: Context): RemoteViews {
            return try {
                buildViewsUnsafe(context)
            } catch (t: Throwable) {
                // Same "never let a rendering bug crash the shared app process" fallback as
                // NowBarWidgetProvider.buildViews — see its own doc.
                Toast.makeText(
                    context,
                    "Widget 4x2: ${t.javaClass.simpleName}: ${t.message}",
                    Toast.LENGTH_LONG
                ).show()
                emptyViews(context)
            }
        }

        private fun emptyViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_now_bar_compact)
            views.setViewVisibility(R.id.widget_sofascore_content, View.GONE)
            views.setViewVisibility(R.id.widget_all_notifs_content, View.GONE)
            views.setViewVisibility(R.id.widget_view_toggle_right, View.GONE)
            views.setImageViewResource(R.id.widget_compact_row1_icon, R.drawable.ic_notification_bell)
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
            val views = RemoteViews(context.packageName, R.layout.widget_now_bar_compact)

            // Row 2 — "Dernière notif" or peek, exact same rendering as NowBarWidgetProvider's own
            // LATEST view (see the class doc). widget_view_toggle only has a job here while
            // peeking (closing it) — applyPeekLeftColumn already shows/binds it in that case; the
            // rest of the time this widget has no other row-2 "view" to switch to, so it stays
            // GONE, unlike the main widget where applyLeftToggle may still show it for LATEST.
            // Bottom "Dernière notif"/peek row — shared with the other 4x2 widget (AUDIT
            // 23/09/2026, used to be the same block copy-pasted in both), see
            // NowBarWidgetProvider.renderPeekOrLatestRow.
            NowBarWidgetProvider.renderPeekOrLatestRow(context, views)

            // Row 1 — icon strip, independent of whether row 2 is currently peeking.
            applyIconsRow(context, views)

            return views
        }

        /**
         * Row 1 — "il faut que ça soit exactement la même chose que la vue icône du widget 4x1" :
         * the same [NowBarWidgetProvider.applySofascoreMatches]/[NowBarWidgetProvider.applyAllNotifs]
         * (5 tiles, images + titles/score/period) and the same left icon/right toggle the main
         * widget's own Sport/Toutes-notifs view uses — see the class doc. [WidgetViewModePrefs.sportOrAllNotifsView]
         * picks Sport vs. Toutes-notifs (shared with the main widget's own right toggle); whichever
         * entry is currently "Dernière notif" (row 2's default content, [WidgetAllNotificationsStore]'s
         * own most-recent entry — the SAME one [NowBarWidgetProvider.applyLatestContent] just
         * rendered into row 2 above, peek or not) is filtered out of the list before it's handed to
         * those shared functions, per Yann's "la dernière notif n'apparaît pas car est dans vue
         * texte" — deliberately based on the true latest entry rather than whatever's currently
         * peeked, so browsing row 1 never makes its own tiles flicker in and out as the peek target
         * changes.
         */
        private fun applyIconsRow(context: Context, views: RemoteViews) {
            val mode = WidgetViewModePrefs.sportOrAllNotifsView(context)
            val latest = WidgetAllNotificationsStore.get(context).firstOrNull()

            views.setViewVisibility(R.id.widget_sofascore_content, if (mode == WidgetViewModePrefs.WidgetView.SPORT) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_all_notifs_content, if (mode == WidgetViewModePrefs.WidgetView.ALL_NOTIFS) View.VISIBLE else View.GONE)

            when (mode) {
                WidgetViewModePrefs.WidgetView.ALL_NOTIFS -> {
                    NowBarWidgetProvider.applyAllNotifsIcon(context, views, R.id.widget_compact_row1_icon)
                    val entries = WidgetAllNotificationsStore.get(context)
                        .filterNot { latest != null && it.key == latest.key && it.postTimeMillis == latest.postTimeMillis }
                    NowBarWidgetProvider.applyAllNotifs(context, views, entries, PEEK_REQUEST_CODE_ALL_NOTIFS_BASE)
                }
                else -> {
                    NowBarWidgetProvider.applySofascoreIcon(context, views, R.id.widget_compact_row1_icon)
                    val matches = SofascoreWidgetStore.get(context)
                        .sortedForWidget(
                            nowMillis = System.currentTimeMillis(),
                            statusOf = { it.status },
                            postTimeOf = { it.postTimeMillis }
                        )
                        .filterNot { latest != null && latest.kind == WidgetAllNotificationsStore.Kind.SOFASCORE_MATCH && it.key == latest.key }
                    NowBarWidgetProvider.applySofascoreMatches(context, views, matches, PEEK_REQUEST_CODE_SPORT_BASE)
                }
            }

            // Same toggle as the main widget's own (widget_view_toggle_right, same id/PendingIntent) —
            // always visible here since [mode] is never LATEST (see applyRightToggle's doc).
            NowBarWidgetProvider.applyRightToggle(context, views, mode)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = buildViews(context)
        appWidgetIds.forEach { id -> appWidgetManager.updateAppWidget(id, views) }
    }
}
