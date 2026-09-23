package com.yann.nowbarmirror.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.graphics.BitmapFactory
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import com.yann.nowbarmirror.R

/**
 * Second, 4x2 home-screen/lock-screen widget (NEW 22/09/2026, Yann: "Fais moi un widget 4x2. En
 * bas du widget, la vue dernière notif. En haut, les icônes avec comme aujourd'hui la possibilité
 * d'alterner entre sport et toutes notifs. Ne pas inclure l'icone de la dernière notif vu qu'elle
 * est déjà en dessous. Si je clique sur une icône, afficher le texte à la place de la dernière
 * notif. Au bout de 15 secondes comme pour l'autre, fermer la vue texte et donc revenir à la
 * dernière notif. Harmoniser au maximum avec le widget existant. C'est juste une manière de
 * présenter différente.") — see widget_now_bar_compact.xml's class doc for the exact layout.
 *
 * Deliberately reuses NowBarWidgetProvider's own machinery rather than duplicating it, in the
 * spirit of "harmoniser au maximum [...] c'est juste une manière de présenter différente":
 * - Row 2 ("Dernière notif" / peek) is rendered with the exact same
 *   [NowBarWidgetProvider.applyLatestContent]/[NowBarWidgetProvider.renderLatestFormat]/
 *   [NowBarWidgetProvider.applyPeekLeftColumn]/[NowBarWidgetProvider.resolvePeek] this widget's
 *   own LATEST view and peek feature already use — same ids in widget_now_bar_compact.xml, same
 *   WidgetPeekPrefs state, same 15s auto-close alarm (NowBarWidgetProvider.scheduleAutoClosePeek,
 *   scheduled by NowBarWidgetProvider.onReceive's ACTION_OPEN_PEEK branch — this class never
 *   schedules or cancels that alarm itself).
 * - Row 1's Sport/Toutes-notifs choice is the SAME shared value as the main widget's own right
 *   toggle (see WidgetViewModePrefs.sportOrAllNotifsView/toggleSportOrAllNotifsView) — flipping
 *   either widget's toggle moves both.
 * - A row-1 icon tap opens a "peek" via [NowBarWidgetProvider.openPeekPendingIntent] — the SAME
 *   ACTION_OPEN_PEEK broadcast the main widget's own tiles use, targeting NowBarWidgetProvider
 *   (not this class), just with its own request-code range ([PEEK_REQUEST_CODE_SPORT_BASE]/
 *   [PEEK_REQUEST_CODE_ALL_NOTIFS_BASE]) so a tile here never shares a PendingIntent identity with
 *   the main widget's own tile at the same slot index pointing at a different entry.
 * - [NowBarWidgetProvider]'s own [NowBarWidgetProvider.pushToAllWidgets] (the one choke point
 *   every state change already goes through — peek open/close/auto-close, view toggle, a new
 *   Sofascore/notification push) calls [refreshAll] after updating its own widget instances, so
 *   this widget picks up every one of those existing call sites for free — nothing here schedules
 *   its own refresh outside of [onUpdate].
 *
 * This class therefore only owns: its own [buildViewsUnsafe] (assembling the two rows from
 * whichever store/pref state is current) and the "exclude whatever's already shown as Dernière
 * notif" filtering row 1 needs that the main widget's own tile rows never had to do.
 */
class NowBarWidgetProviderCompact : AppWidgetProvider() {

    companion object {

        // Distinct from NowBarWidgetProvider's own PEEK_REQUEST_CODE_SPORT_BASE (4300)/
        // PEEK_REQUEST_CODE_ALL_NOTIFS_BASE (4400) — see the class doc's PendingIntent-identity note.
        private const val PEEK_REQUEST_CODE_SPORT_BASE = 4700
        private const val PEEK_REQUEST_CODE_ALL_NOTIFS_BASE = 4800

        private val SPORT_ICON_IDS = listOf(
            R.id.widget_compact_sport_icon_1,
            R.id.widget_compact_sport_icon_2,
            R.id.widget_compact_sport_icon_3,
            R.id.widget_compact_sport_icon_4,
            R.id.widget_compact_sport_icon_5
        )

        private data class NotifIconIds(val container: Int, val photo: Int, val matchImage: Int, val badge: Int)

        private val NOTIF_ICON_IDS = listOf(
            NotifIconIds(R.id.widget_compact_notif_icon_1, R.id.widget_compact_notif_icon_1_photo, R.id.widget_compact_notif_icon_1_match, R.id.widget_compact_notif_icon_1_badge),
            NotifIconIds(R.id.widget_compact_notif_icon_2, R.id.widget_compact_notif_icon_2_photo, R.id.widget_compact_notif_icon_2_match, R.id.widget_compact_notif_icon_2_badge),
            NotifIconIds(R.id.widget_compact_notif_icon_3, R.id.widget_compact_notif_icon_3_photo, R.id.widget_compact_notif_icon_3_match, R.id.widget_compact_notif_icon_3_badge),
            NotifIconIds(R.id.widget_compact_notif_icon_4, R.id.widget_compact_notif_icon_4_photo, R.id.widget_compact_notif_icon_4_match, R.id.widget_compact_notif_icon_4_badge),
            NotifIconIds(R.id.widget_compact_notif_icon_5, R.id.widget_compact_notif_icon_5_photo, R.id.widget_compact_notif_icon_5_match, R.id.widget_compact_notif_icon_5_badge)
        )

        /** Called from NowBarWidgetProvider.pushToAllWidgets — see the class doc. No-op if this widget isn't currently placed. */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NowBarWidgetProviderCompact::class.java))
            if (ids.isEmpty()) return
            val views = buildViews(context)
            ids.forEach { id -> manager.updateAppWidget(id, views) }
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
            views.setViewVisibility(R.id.widget_compact_sport_icons, View.GONE)
            views.setViewVisibility(R.id.widget_compact_notif_icons, View.GONE)
            views.setViewVisibility(R.id.widget_compact_icons_empty, View.GONE)
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

            // Row 1 — icon strip, independent of whether row 2 is currently peeking.
            applyIconsRow(context, views)

            return views
        }

        /**
         * Row 1 — see widget_now_bar_compact.xml's class doc. [WidgetViewModePrefs.sportOrAllNotifsView]
         * picks Sport vs. Toutes-notifs (shared with the main widget's own right toggle); whichever
         * entry is currently "Dernière notif" (row 2's default content, [WidgetAllNotificationsStore]'s
         * own most-recent entry — the SAME one [NowBarWidgetProvider.applyLatestContent] just
         * rendered into row 2 above, peek or not) is filtered out before the 5 fixed slots are
         * filled, per Yann's "elle est déjà en dessous" — deliberately based on the true latest
         * entry rather than whatever's currently peeked, so browsing row 1 never makes its own
         * icons flicker in and out as the peek target changes.
         */
        private fun applyIconsRow(context: Context, views: RemoteViews) {
            val mode = WidgetViewModePrefs.sportOrAllNotifsView(context)
            val latest = WidgetAllNotificationsStore.get(context).firstOrNull()

            views.setViewVisibility(R.id.widget_compact_sport_icons, if (mode == WidgetViewModePrefs.WidgetView.SPORT) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_compact_notif_icons, if (mode == WidgetViewModePrefs.WidgetView.ALL_NOTIFS) View.VISIBLE else View.GONE)

            when (mode) {
                WidgetViewModePrefs.WidgetView.ALL_NOTIFS -> applyNotifIcons(context, views, latest)
                else -> applySportIcons(context, views, latest)
            }

            applyToggle(context, views, mode)
        }

        private fun applySportIcons(context: Context, views: RemoteViews, latest: WidgetAllNotificationsStore.Data?) {
            val matches = SofascoreWidgetStore.get(context)
                .sortedForWidget(
                    nowMillis = System.currentTimeMillis(),
                    statusOf = { it.status },
                    apiSourceOf = { it.apiSource },
                    postTimeOf = { it.postTimeMillis }
                )
                .filterNot { latest != null && latest.kind == WidgetAllNotificationsStore.Kind.SOFASCORE_MATCH && it.key == latest.key }

            val empty = matches.isEmpty()
            views.setViewVisibility(R.id.widget_compact_icons_empty, if (empty) View.VISIBLE else View.GONE)
            if (empty) views.setTextViewText(R.id.widget_compact_icons_empty, context.getString(R.string.sofascore_widget_empty))

            for (i in SPORT_ICON_IDS.indices) {
                val slotId = SPORT_ICON_IDS[i]
                val match = matches.getOrNull(i)
                if (match == null) {
                    views.setViewVisibility(slotId, View.GONE)
                    continue
                }
                views.setViewVisibility(slotId, View.VISIBLE)

                val bitmap = match.imageFile?.let { BitmapFactory.decodeFile(it.path) }
                if (bitmap != null) {
                    views.setImageViewBitmap(slotId, bitmap)
                } else {
                    views.setImageViewResource(slotId, R.drawable.bg_sofascore_placeholder)
                }

                views.setOnClickPendingIntent(
                    slotId,
                    NowBarWidgetProvider.openPeekPendingIntent(context, WidgetPeekPrefs.Source.SPORT, match.key, PEEK_REQUEST_CODE_SPORT_BASE + i)
                )
            }
        }

        private fun applyNotifIcons(context: Context, views: RemoteViews, latest: WidgetAllNotificationsStore.Data?) {
            val entries = WidgetAllNotificationsStore.get(context)
                .filterNot { latest != null && it.key == latest.key && it.postTimeMillis == latest.postTimeMillis }

            val empty = entries.isEmpty()
            views.setViewVisibility(R.id.widget_compact_icons_empty, if (empty) View.VISIBLE else View.GONE)
            if (empty) views.setTextViewText(R.id.widget_compact_icons_empty, context.getString(R.string.widget_empty_title))

            for (i in NOTIF_ICON_IDS.indices) {
                val ids = NOTIF_ICON_IDS[i]
                val entry = entries.getOrNull(i)
                if (entry == null) {
                    views.setViewVisibility(ids.container, View.GONE)
                    continue
                }
                views.setViewVisibility(ids.container, View.VISIBLE)

                when (entry.kind) {
                    WidgetAllNotificationsStore.Kind.SOFASCORE_MATCH -> {
                        views.setViewVisibility(ids.photo, View.GONE)
                        views.setViewVisibility(ids.badge, View.GONE)
                        views.setViewVisibility(ids.matchImage, View.VISIBLE)
                        val bitmap = entry.imageFile?.let { BitmapFactory.decodeFile(it.path) }
                        if (bitmap != null) {
                            views.setImageViewBitmap(ids.matchImage, bitmap)
                        } else {
                            views.setImageViewResource(ids.matchImage, R.drawable.bg_sofascore_placeholder)
                        }
                    }
                    WidgetAllNotificationsStore.Kind.GENERIC -> {
                        views.setViewVisibility(ids.matchImage, View.GONE)
                        views.setViewVisibility(ids.photo, View.VISIBLE)
                        val bitmap = entry.imageFile?.let { BitmapFactory.decodeFile(it.path) }
                        val appIcon = entry.packageName?.let { NowBarWidgetProvider.appIconBitmap(context, it) }
                        if (bitmap != null) {
                            views.setImageViewBitmap(ids.photo, NowBarWidgetProvider.circularBitmap(bitmap))
                            if (appIcon != null) {
                                views.setImageViewBitmap(ids.badge, NowBarWidgetProvider.circularBitmap(appIcon))
                                views.setViewVisibility(ids.badge, View.VISIBLE)
                            } else {
                                views.setViewVisibility(ids.badge, View.GONE)
                            }
                        } else {
                            views.setViewVisibility(ids.badge, View.GONE)
                            if (appIcon != null) {
                                views.setImageViewBitmap(ids.photo, NowBarWidgetProvider.circularBitmap(appIcon))
                            } else {
                                views.setImageViewResource(ids.photo, R.drawable.ic_stat_mirror)
                            }
                        }
                    }
                }

                val entryId = NowBarWidgetProvider.allNotifEntryId(entry.key, entry.postTimeMillis)
                views.setOnClickPendingIntent(
                    ids.container,
                    NowBarWidgetProvider.openPeekPendingIntent(context, WidgetPeekPrefs.Source.ALL_NOTIFS, entryId, PEEK_REQUEST_CODE_ALL_NOTIFS_BASE + i)
                )
            }
        }

        /** Same icon convention as NowBarWidgetProvider.applyRightToggleIcon (bell hints at Toutes notifs, football pitch hints at Sport), bound to the exact same toggle PendingIntent as the main widget's own right toggle — see WidgetViewModePrefs.toggleSportOrAllNotifsView's doc. */
        private fun applyToggle(context: Context, views: RemoteViews, mode: WidgetViewModePrefs.WidgetView) {
            val iconRes = if (mode == WidgetViewModePrefs.WidgetView.SPORT) {
                R.drawable.ic_notification_bell
            } else {
                R.drawable.ic_football_pitch
            }
            views.setImageViewResource(R.id.widget_compact_toggle, iconRes)
            views.setOnClickPendingIntent(R.id.widget_compact_toggle, NowBarWidgetProvider.toggleSportNotifsPendingIntent(context))
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = buildViews(context)
        appWidgetIds.forEach { id -> appWidgetManager.updateAppWidget(id, views) }
    }
}
