package com.yann.nowbarmirror.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import com.yann.nowbarmirror.MirrorNotificationListener
import com.yann.nowbarmirror.R
import com.yann.nowbarmirror.settings.WidgetActionsPrefs
import com.yann.nowbarmirror.sport.SofascoreNotificationListenerService

/** One notification action, rendered as a small text button (the action's own label) in the widget. */
data class WidgetAction(
    val label: String,
    val pendingIntent: PendingIntent
)

/**
 * One Sofascore match as pushed from SofascoreNotificationListenerService.pushWidgetMatches — see
 * its doc. [image] is Sofascore's own combined notification image (may be null, see
 * NotificationImageExtractor) and [contentIntent] is that SPECIFIC notification's own live
 * PendingIntent — tapping a match tile must open Sofascore on that exact match (same as tapping
 * the notification itself) WITHOUT cancelling the source notification, see
 * applySofascoreMatches. Neither [image] nor [contentIntent] is persisted (see
 * SofascoreWidgetStore) — only held in memory for the current process, same limitation as
 * [WidgetAction] above and the mirror's own liveContentIntent.
 */
data class SofascoreWidgetMatch(
    val key: String,
    val homeTeam: String,
    val awayTeam: String,
    val homeScore: String?,
    val awayScore: String?,
    val lastScorer: String?,
    val status: String,
    val apiSource: String,
    val postTimeMillis: Long,
    val image: Bitmap?,
    val contentIntent: PendingIntent?
)

/**
 * One entry pushed into the widget's "Toutes notifs" history — see
 * [NowBarWidgetProvider.pushToAllNotifications] and WidgetAllNotificationsStore's class doc for
 * why this is a rolling log rather than a live/active set. [image]/[contentIntent] follow the same
 * in-memory-only rule as [SofascoreWidgetMatch] above.
 */
data class AllNotifEntryPush(
    val key: String,
    val postTimeMillis: Long,
    val kind: WidgetAllNotificationsStore.Kind,
    val title: String? = null,
    val packageName: String? = null,
    // See WidgetAllNotificationsStore.PersistableEntry.isConversation's doc — set by
    // MirrorNotificationListener for GENERIC entries, always false (irrelevant) for
    // SOFASCORE_MATCH ones, which collapse by key alone unconditionally regardless of this flag.
    val isConversation: Boolean = false,
    val homeTeam: String? = null,
    val awayTeam: String? = null,
    val homeScore: String? = null,
    val awayScore: String? = null,
    val lastScorer: String? = null,
    val status: String? = null,
    val apiSource: String? = null,
    val image: Bitmap?,
    val contentIntent: PendingIntent?
)

/**
 * Sorts [this] with priority to live/recently-finished matches, most-recently-notified first
 * within each bucket — a match SofascoreMatchPresentation.isMatchFinished() considers finished
 * AND whose last notification was more than 5 minutes ago drops into the second bucket (Yann:
 * "priorité aux matches en cours, un match fini depuis plus de 5 minutes passe après"). Generic
 * over [T] so it applies both to SofascoreWidgetMatch (push time, in
 * NowBarWidgetProvider.pushSofascoreMatches) and to SofascoreWidgetStore.Data (render time, in
 * buildViewsUnsafe) — see pushSofascoreMatches for why it's applied at BOTH points rather than
 * just once at push time: re-sorting the same already-capped set on every render keeps their
 * RELATIVE order (live vs. finished-a-while-ago) accurate as time passes, even between Sofascore
 * events, without needing a periodic background refresh (this app has none, by design — see
 * WidgetNotificationStore's own class doc for the same reasoning elsewhere).
 */
private fun <T> List<T>.sortedForWidget(
    nowMillis: Long,
    statusOf: (T) -> String,
    apiSourceOf: (T) -> String,
    postTimeOf: (T) -> Long
): List<T> {
    fun isDemoted(item: T): Boolean {
        if (!SofascoreMatchPresentation.isMatchFinished(statusOf(item))) return false
        return nowMillis - postTimeOf(item) > 5 * 60_000L
    }
    return sortedWith(compareBy<T> { isDemoted(it) }.thenByDescending { postTimeOf(it) })
}

/**
 * Home-screen App Widget (4x1, transparent background) meant to be placed on the lock screen
 * through a third-party lock-widget host such as Samsung's LockStar. THREE views (extended
 * 17/09/2026 from the original two — see WidgetViewModePrefs' class doc for the full navigation
 * model and why there are two separate toggle buttons):
 * - LATEST: whatever WidgetNotificationStore currently holds, the single most recently posted
 *   notification from any app configured with a mirror mode, across ALL/LATEST alike.
 * - SPORT: up to 5 Sofascore matches from SofascoreWidgetStore, pushed by
 *   SofascoreNotificationListenerService whenever a Sofascore notification changes.
 * - ALL_NOTIFS ("Toutes notifs"): up to 5 recently received notifications from
 *   WidgetAllNotificationsStore, fed by both MirrorNotificationListener (generic) and
 *   SofascoreNotificationListenerService (Sofascore, kept in its match-tile presentation).
 */
class NowBarWidgetProvider : AppWidgetProvider() {

    companion object {

        private const val ACTION_TOGGLE_VIEW = "com.yann.nowbarmirror.widget.ACTION_TOGGLE_VIEW"
        private const val ACTION_TOGGLE_SPORT_NOTIFS = "com.yann.nowbarmirror.widget.ACTION_TOGGLE_SPORT_NOTIFS"

        // Actions are deliberately NOT persisted to WidgetNotificationStore/SharedPreferences,
        // for the same reason the content PendingIntent isn't (see the class doc on
        // WidgetNotificationStore): a PendingIntent only survives a real Binder transaction
        // (handing it straight to AppWidgetManager here), not a round-trip through disk. So
        // these live only in memory, for as long as this process stays alive since the
        // notification was posted. liveActionsKey guards against showing them for the wrong
        // notification: buildViewsUnsafe() only uses liveActions when it matches the key
        // WidgetNotificationStore currently holds, so a stale set from a previous notification
        // (or the empty default right after a process restart) never leaks onto an unrelated
        // one — the actions row just stays hidden until the next live push.
        private var liveActions: List<WidgetAction> = emptyList()
        private var liveActionsKey: String? = null

        // Same in-memory-only trick as liveActions above, for the Sofascore view: one live
        // PendingIntent per match key, refreshed on every pushSofascoreMatches call. A match
        // whose key isn't in this map (stale after a process restart, or never had a usable
        // contentIntent) falls back to launchAppPendingIntent(SOFASCORE_PACKAGE) — see
        // applySofascoreMatches.
        private var liveSofascoreIntents: Map<String, PendingIntent> = emptyMap()

        // Same idea again for the "Toutes notifs" view, EXCEPT this one is additive rather than
        // fully replaced on every push (see pushToAllNotifications): unlike the Sofascore map
        // above, entries here persist across many push events (it's a history, not a
        // recomputed-from-scratch active set), so a push only ADDS/refreshes its own entry and
        // then trims down to exactly the entries WidgetAllNotificationsStore is still keeping —
        // an older entry's live PendingIntent survives in memory for as long as it stays in the
        // capped history AND this process stays alive; once either drops it, the tile falls back
        // to launchAppPendingIntent (or Sofascore's package for a SOFASCORE_MATCH entry).
        //
        // Keyed by [allNotifEntryId] (key + postTimeMillis), NOT by key alone (fixed 17/09/2026,
        // same identity fix as WidgetAllNotificationsStore — see its class doc): several tiles
        // can now share the same underlying notification key (a "Dernière notif"-mode app reusing
        // its notification id across distinct items), and each one must open ITS OWN article/
        // conversation when tapped, not whichever of them happened to push most recently.
        private var liveAllNotifIntents: Map<String, PendingIntent> = emptyMap()

        /** Composite identity for [liveAllNotifIntents] — see that field's doc. */
        private fun allNotifEntryId(key: String, postTimeMillis: Long) = "$key::$postTimeMillis"

        /**
         * Called right when a notification is mirrored, with THAT notification's own live
         * PendingIntent(s). This is the only reliable way to give the widget a working "open
         * the exact conversation/article" tap and working action buttons: handing a
         * PendingIntent to AppWidgetManager here goes through a real Binder transaction (same
         * as NotificationManager.notify() already does for the system-notification mirror),
         * which is what actually preserves it — trying to save and later reconstruct a
         * PendingIntent from SharedPreferences does not.
         */
        fun pushLive(
            context: Context,
            key: String,
            title: String,
            text: String,
            packageName: String,
            contentIntent: PendingIntent?,
            image: Bitmap?,
            actions: List<WidgetAction> = emptyList()
        ) {
            WidgetNotificationStore.save(context, key, title, text, packageName, image)
            liveActions = actions
            liveActionsKey = key
            pushToAllWidgets(context, buildViews(context, liveContentIntent = contentIntent))
        }

        /**
         * Called by SofascoreNotificationListenerService.pushWidgetMatches whenever a Sofascore
         * notification is posted or removed, with EVERY currently active Sofascore notification
         * turned into a match (not just the one the watch complication follows — see that
         * function's doc for why the API-override system doesn't apply here). Sorts + caps to
         * SofascoreWidgetStore.MAX_SLOTS (see sortedForWidget above), keeps the live
         * PendingIntents in memory for the KEPT matches only, persists the rest, then rebuilds
         * whichever view is currently showing (a Sport-view rebuild picks the new matches up
         * immediately; any other view's rebuild only needs this to recompute whether the left
         * toggle button should now be visible — see applyLeftToggle).
         */
        fun pushSofascoreMatches(context: Context, matches: List<SofascoreWidgetMatch>) {
            val kept = matches.sortedForWidget(
                nowMillis = System.currentTimeMillis(),
                statusOf = { it.status },
                apiSourceOf = { it.apiSource },
                postTimeOf = { it.postTimeMillis }
            ).take(SofascoreWidgetStore.MAX_SLOTS)

            liveSofascoreIntents = kept.mapNotNull { m -> m.contentIntent?.let { m.key to it } }.toMap()

            SofascoreWidgetStore.save(
                context,
                kept.map { m ->
                    SofascoreWidgetStore.PersistableMatch(
                        key = m.key,
                        homeTeam = m.homeTeam,
                        awayTeam = m.awayTeam,
                        homeScore = m.homeScore,
                        awayScore = m.awayScore,
                        lastScorer = m.lastScorer,
                        status = m.status,
                        apiSource = m.apiSource,
                        postTimeMillis = m.postTimeMillis,
                        image = m.image
                    )
                }
            )

            pushToAllWidgets(context, buildViews(context))
        }

        /**
         * Called by MirrorNotificationListener.mirror()/pushAllNotifsHistoryOnly() for every
         * received notification (any app, ALL or LATEST mode alike) and by
         * SofascoreNotificationListenerService.onNotificationPosted (plus its own listener-connect
         * catch-up) for every Sofascore notification event — see WidgetAllNotificationsStore's
         * class doc for why both funnel into this ONE shared history rather than each having their
         * own. Merges [entry] into the persisted history — a Sofascore match or a conversation
         * (see [AllNotifEntryPush.isConversation]) always updates its ONE tile in place, by key
         * alone; anything else only updates in place for the exact same (key, postTimeMillis) pair
         * re-pushed, and gets a new tile for the same key with a DIFFERENT postTimeMillis — see
         * WidgetAllNotificationsStore's IDENTITY section, fixed 17/09/2026, then trims
         * [liveAllNotifIntents] down to exactly the entries still kept — see that field's doc —
         * before rebuilding whichever view is currently showing.
         */
        fun pushToAllNotifications(context: Context, entry: AllNotifEntryPush) {
            val kept = WidgetAllNotificationsStore.push(
                context,
                WidgetAllNotificationsStore.PersistableEntry(
                    key = entry.key,
                    kind = entry.kind,
                    postTimeMillis = entry.postTimeMillis,
                    title = entry.title,
                    packageName = entry.packageName,
                    isConversation = entry.isConversation,
                    homeTeam = entry.homeTeam,
                    awayTeam = entry.awayTeam,
                    homeScore = entry.homeScore,
                    awayScore = entry.awayScore,
                    lastScorer = entry.lastScorer,
                    status = entry.status,
                    apiSource = entry.apiSource,
                    image = entry.image
                )
            )

            val entryId = allNotifEntryId(entry.key, entry.postTimeMillis)
            val keptIds = kept.map { allNotifEntryId(it.key, it.postTimeMillis) }.toSet()
            liveAllNotifIntents = buildMap {
                entry.contentIntent?.let { put(entryId, it) }
                keptIds.forEach { id ->
                    if (id != entryId) liveAllNotifIntents[id]?.let { put(id, it) }
                }
            }

            pushToAllWidgets(context, buildViews(context))
        }

        /**
         * Rebuilds and pushes from whatever the stores currently hold, with no live
         * PendingIntent available (used when just clearing the widget, refreshing after a
         * staleness check, or when the system calls onUpdate() independently of any specific
         * notification event). In the latest-notif view the tap falls back to opening the
         * source app, and the actions row — which has no such fallback — simply stays hidden;
         * in the other two views, a tile falls back the same way (see applySofascoreMatches /
         * applyAllNotifs).
         */
        fun requestUpdate(context: Context) {
            pushToAllWidgets(context, buildViews(context))
        }

        private fun pushToAllWidgets(context: Context, views: RemoteViews) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NowBarWidgetProvider::class.java))
            if (ids.isEmpty()) return
            ids.forEach { id -> manager.updateAppWidget(id, views) }
        }

        private fun buildViews(context: Context, liveContentIntent: PendingIntent? = null): RemoteViews {
            return try {
                buildViewsUnsafe(context, liveContentIntent)
            } catch (t: Throwable) {
                // TEMPORARY diagnostic: surfaces the exact failure on screen since this device
                // can't be hooked up to Android Studio for logcat. Safe to remove once the
                // widget rendering path is confirmed stable — until then, a fallback empty
                // widget is returned so this can never crash the shared app process.
                Toast.makeText(
                    context,
                    "Widget: ${t.javaClass.simpleName}: ${t.message}",
                    Toast.LENGTH_LONG
                ).show()
                emptyViews(context)
            }
        }

        /** Ultra-safe fallback used only from buildViews()'s catch block — everything below is deliberately re-derived rather than shared with applyLatestContent, so a bug in THAT function can never take this fallback down with it. */
        private fun emptyViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_now_bar)
            views.setViewVisibility(R.id.widget_sofascore_content, View.GONE)
            views.setViewVisibility(R.id.widget_all_notifs_content, View.GONE)
            views.setViewVisibility(R.id.widget_latest_content, View.VISIBLE)
            views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_empty_title))
            views.setTextViewText(R.id.widget_text, "")
            views.setImageViewResource(R.id.widget_app_icon, R.drawable.ic_stat_mirror)
            views.setViewVisibility(R.id.widget_image, View.GONE)
            views.setViewVisibility(R.id.widget_dismiss, View.GONE)
            views.setViewVisibility(R.id.widget_actions_container, View.GONE)
            views.setViewVisibility(R.id.widget_view_toggle, View.GONE)
            views.setViewVisibility(R.id.widget_view_toggle_right, View.GONE)
            return views
        }

        private fun buildViewsUnsafe(context: Context, liveContentIntent: PendingIntent?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_now_bar)

            val sofascoreMatches = SofascoreWidgetStore.get(context).sortedForWidget(
                nowMillis = System.currentTimeMillis(),
                statusOf = { it.status },
                apiSourceOf = { it.apiSource },
                postTimeOf = { it.postTimeMillis }
            )
            val allNotifs = WidgetAllNotificationsStore.get(context)
            val currentView = WidgetViewModePrefs.currentView(context)

            views.setViewVisibility(R.id.widget_latest_content, if (currentView == WidgetViewModePrefs.WidgetView.LATEST) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_sofascore_content, if (currentView == WidgetViewModePrefs.WidgetView.SPORT) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_all_notifs_content, if (currentView == WidgetViewModePrefs.WidgetView.ALL_NOTIFS) View.VISIBLE else View.GONE)

            when (currentView) {
                WidgetViewModePrefs.WidgetView.LATEST -> applyLatestContent(context, views, liveContentIntent)
                WidgetViewModePrefs.WidgetView.SPORT -> {
                    applySofascoreIcon(context, views)
                    applySofascoreMatches(context, views, sofascoreMatches)
                }
                WidgetViewModePrefs.WidgetView.ALL_NOTIFS -> {
                    applyAllNotifsIcon(context, views)
                    applyAllNotifs(context, views, allNotifs)
                }
            }

            val hasAnythingElse = sofascoreMatches.isNotEmpty() || allNotifs.isNotEmpty()
            applyLeftToggle(context, views, currentView, hasAnythingElse)
            applyRightToggle(context, views, currentView)

            return views
        }

        private fun applyLatestContent(context: Context, views: RemoteViews, liveContentIntent: PendingIntent?) {
            val data = WidgetNotificationStore.get(context)
            if (data == null) {
                views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_empty_title))
                views.setTextViewText(R.id.widget_text, "")
                views.setImageViewResource(R.id.widget_app_icon, R.drawable.ic_stat_mirror)
                views.setViewVisibility(R.id.widget_image, View.GONE)
                views.setViewVisibility(R.id.widget_dismiss, View.GONE)
                views.setViewVisibility(R.id.widget_actions_container, View.GONE)
                return
            }

            views.setTextViewText(R.id.widget_title, data.title)
            views.setTextViewText(R.id.widget_text, data.text)

            val appIcon = appIconBitmap(context, data.packageName)
            if (appIcon != null) {
                views.setImageViewBitmap(R.id.widget_app_icon, circularBitmap(appIcon))
            } else {
                views.setImageViewResource(R.id.widget_app_icon, R.drawable.ic_stat_mirror)
            }

            val imageBitmap = data.imageFile?.let { BitmapFactory.decodeFile(it.path) }
            if (imageBitmap != null) {
                views.setImageViewBitmap(R.id.widget_image, circularBitmap(imageBitmap))
                views.setViewVisibility(R.id.widget_image, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_image, View.GONE)
            }

            views.setViewVisibility(R.id.widget_dismiss, View.VISIBLE)
            views.setOnClickPendingIntent(R.id.widget_dismiss, dismissPendingIntent(context, data.key))

            applyActions(context, views, data.key)

            // Bound to widget_latest_content specifically (not the whole widget_root any more —
            // widget_root also contains the left/right columns, shared with the other views,
            // which must NOT open this notification when tapped).
            val openIntent = liveContentIntent ?: launchAppPendingIntent(context, data.packageName)
            if (openIntent != null) {
                views.setOnClickPendingIntent(R.id.widget_latest_content, openIntent)
            }
        }

        private fun applySofascoreIcon(context: Context, views: RemoteViews) {
            val icon = appIconBitmap(context, SofascoreNotificationListenerService.SOFASCORE_PACKAGE)
            if (icon != null) {
                views.setImageViewBitmap(R.id.widget_app_icon, circularBitmap(icon))
            } else {
                views.setImageViewResource(R.id.widget_app_icon, R.drawable.ic_stat_mirror)
            }
        }

        /** No single source app for a merged "Toutes notifs" feed — falls back to this app's own icon, same as the empty-latest-view fallback. */
        private fun applyAllNotifsIcon(context: Context, views: RemoteViews) {
            views.setImageViewResource(R.id.widget_app_icon, R.drawable.ic_stat_mirror)
        }

        private val SOFASCORE_SLOT_IDS = listOf(R.id.widget_match_1, R.id.widget_match_2, R.id.widget_match_3, R.id.widget_match_4, R.id.widget_match_5)
        private val SOFASCORE_SLOT_IMAGE_IDS = listOf(R.id.widget_match_1_image, R.id.widget_match_2_image, R.id.widget_match_3_image, R.id.widget_match_4_image, R.id.widget_match_5_image)
        private val SOFASCORE_SLOT_SCORE_IDS = listOf(R.id.widget_match_1_score, R.id.widget_match_2_score, R.id.widget_match_3_score, R.id.widget_match_4_score, R.id.widget_match_5_score)
        private val SOFASCORE_SLOT_PERIOD_IDS = listOf(R.id.widget_match_1_period, R.id.widget_match_2_period, R.id.widget_match_3_period, R.id.widget_match_4_period, R.id.widget_match_5_period)

        /**
         * Fills the up-to-5 fixed match slots (see widget_now_bar.xml's class doc for why fixed
         * slots rather than a RemoteViews collection — 5th slot added 17/09/2026, was 4). [matches]
         * is already sorted by sortedForWidget by the caller (buildViewsUnsafe) and already capped
         * to SofascoreWidgetStore.MAX_SLOTS at push time, so this just walks the slots in order.
         */
        private fun applySofascoreMatches(context: Context, views: RemoteViews, matches: List<SofascoreWidgetStore.Data>) {
            views.setViewVisibility(R.id.widget_sofascore_empty, if (matches.isEmpty()) View.VISIBLE else View.GONE)

            for (i in SOFASCORE_SLOT_IDS.indices) {
                val slotId = SOFASCORE_SLOT_IDS[i]
                val match = matches.getOrNull(i)
                if (match == null) {
                    views.setViewVisibility(slotId, View.GONE)
                    continue
                }
                views.setViewVisibility(slotId, View.VISIBLE)

                val imageBitmap = match.imageFile?.let { BitmapFactory.decodeFile(it.path) }
                if (imageBitmap != null) {
                    views.setImageViewBitmap(SOFASCORE_SLOT_IMAGE_IDS[i], imageBitmap)
                } else {
                    views.setImageViewResource(SOFASCORE_SLOT_IMAGE_IDS[i], R.drawable.bg_sofascore_placeholder)
                }

                views.setTextViewText(
                    SOFASCORE_SLOT_SCORE_IDS[i],
                    SofascoreMatchPresentation.scoreText(match.homeScore, match.awayScore, match.lastScorer)
                )
                views.setTextViewText(
                    SOFASCORE_SLOT_PERIOD_IDS[i],
                    SofascoreMatchPresentation.periodLabel(match.status, match.apiSource, kickoffEpochMillis = null)
                )

                // Same live-PendingIntent-first, launch-the-app-as-fallback pattern as the
                // latest-notif view (applyLatestContent) — see liveSofascoreIntents' doc above.
                // Crucially, this is Sofascore's OWN contentIntent, the exact same one Sofascore
                // itself uses — opening it does NOT cancel the source notification (that only
                // happens via the dedicated dismiss button in the latest-notif view, which this
                // view deliberately has none of, see widget_now_bar.xml).
                val openIntent = liveSofascoreIntents[match.key]
                    ?: launchAppPendingIntent(context, SofascoreNotificationListenerService.SOFASCORE_PACKAGE)
                if (openIntent != null) {
                    views.setOnClickPendingIntent(slotId, openIntent)
                }
            }
        }

        private data class AllNotifSlotIds(
            val container: Int,
            val photo: Int,
            val matchImage: Int,
            val badge: Int,
            val title: Int,
            val score: Int,
            val period: Int
        )

        private val ALL_NOTIF_SLOT_IDS = listOf(
            AllNotifSlotIds(R.id.widget_notif_1, R.id.widget_notif_1_photo, R.id.widget_notif_1_match_image, R.id.widget_notif_1_badge, R.id.widget_notif_1_title, R.id.widget_notif_1_score, R.id.widget_notif_1_period),
            AllNotifSlotIds(R.id.widget_notif_2, R.id.widget_notif_2_photo, R.id.widget_notif_2_match_image, R.id.widget_notif_2_badge, R.id.widget_notif_2_title, R.id.widget_notif_2_score, R.id.widget_notif_2_period),
            AllNotifSlotIds(R.id.widget_notif_3, R.id.widget_notif_3_photo, R.id.widget_notif_3_match_image, R.id.widget_notif_3_badge, R.id.widget_notif_3_title, R.id.widget_notif_3_score, R.id.widget_notif_3_period),
            AllNotifSlotIds(R.id.widget_notif_4, R.id.widget_notif_4_photo, R.id.widget_notif_4_match_image, R.id.widget_notif_4_badge, R.id.widget_notif_4_title, R.id.widget_notif_4_score, R.id.widget_notif_4_period),
            AllNotifSlotIds(R.id.widget_notif_5, R.id.widget_notif_5_photo, R.id.widget_notif_5_match_image, R.id.widget_notif_5_badge, R.id.widget_notif_5_title, R.id.widget_notif_5_score, R.id.widget_notif_5_period)
        )

        /**
         * Fills the up-to-5 fixed "Toutes notifs" slots (same fixed-slot philosophy as
         * applySofascoreMatches above). [entries] is already in most-recent-first order (see
         * WidgetAllNotificationsStore.get). Per entry, EITHER the generic (image/app-icon + title)
         * views OR the Sofascore match-tile views are shown, per WidgetAllNotificationsStore.Kind
         * — never both — see widget_now_bar.xml's class doc for the exact rules ("icône notif en
         * petit dans l'angle SAUF si pas d'image, auquel cas icône appli à la place de l'image").
         */
        private fun applyAllNotifs(context: Context, views: RemoteViews, entries: List<WidgetAllNotificationsStore.Data>) {
            views.setViewVisibility(R.id.widget_all_notifs_empty, if (entries.isEmpty()) View.VISIBLE else View.GONE)

            for (i in ALL_NOTIF_SLOT_IDS.indices) {
                val ids = ALL_NOTIF_SLOT_IDS[i]
                val entry = entries.getOrNull(i)
                if (entry == null) {
                    views.setViewVisibility(ids.container, View.GONE)
                    continue
                }
                views.setViewVisibility(ids.container, View.VISIBLE)

                when (entry.kind) {
                    WidgetAllNotificationsStore.Kind.SOFASCORE_MATCH -> applyAllNotifSlotAsMatch(context, views, ids, entry)
                    WidgetAllNotificationsStore.Kind.GENERIC -> applyAllNotifSlotAsGeneric(context, views, ids, entry)
                }

                // Tap only ever opens the notification — never wired to any dismiss/cancel action,
                // so it never removes it ("Cliquer dessus ouvre notification mais ne supprime pas
                // notif"). Same live-PendingIntent-first, launch-the-app-as-fallback pattern as the
                // other two views.
                val fallbackPackage = if (entry.kind == WidgetAllNotificationsStore.Kind.SOFASCORE_MATCH) {
                    SofascoreNotificationListenerService.SOFASCORE_PACKAGE
                } else {
                    entry.packageName
                }
                val openIntent = liveAllNotifIntents[allNotifEntryId(entry.key, entry.postTimeMillis)]
                    ?: fallbackPackage?.let { launchAppPendingIntent(context, it) }
                if (openIntent != null) {
                    views.setOnClickPendingIntent(ids.container, openIntent)
                }
            }
        }

        /** "Garder la même présentation qu'aujourd'hui pour les notifs de Sofascore" — identical rendering to applySofascoreMatches' per-slot content, just on the "Toutes notifs" slot ids instead. */
        private fun applyAllNotifSlotAsMatch(context: Context, views: RemoteViews, ids: AllNotifSlotIds, entry: WidgetAllNotificationsStore.Data) {
            views.setViewVisibility(ids.photo, View.GONE)
            views.setViewVisibility(ids.badge, View.GONE)
            views.setViewVisibility(ids.matchImage, View.VISIBLE)
            views.setViewVisibility(ids.title, View.GONE)
            views.setViewVisibility(ids.score, View.VISIBLE)
            views.setViewVisibility(ids.period, View.VISIBLE)

            val imageBitmap = entry.imageFile?.let { BitmapFactory.decodeFile(it.path) }
            if (imageBitmap != null) {
                views.setImageViewBitmap(ids.matchImage, imageBitmap)
            } else {
                views.setImageViewResource(ids.matchImage, R.drawable.bg_sofascore_placeholder)
            }

            views.setTextViewText(
                ids.score,
                SofascoreMatchPresentation.scoreText(entry.homeScore, entry.awayScore, entry.lastScorer)
            )
            views.setTextViewText(
                ids.period,
                SofascoreMatchPresentation.periodLabel(entry.status.orEmpty(), entry.apiSource.orEmpty(), kickoffEpochMillis = null)
            )
        }

        /**
         * "Mettre image notif en haut [...] en petit dans l'angle de l'image, l'icône [de l'app]
         * sauf si pas d'image. Si pas d'image, mettre juste icône appli à la place de l'image. En
         * dessous, le titre sur jusqu'à deux lignes."
         */
        private fun applyAllNotifSlotAsGeneric(context: Context, views: RemoteViews, ids: AllNotifSlotIds, entry: WidgetAllNotificationsStore.Data) {
            views.setViewVisibility(ids.matchImage, View.GONE)
            views.setViewVisibility(ids.score, View.GONE)
            views.setViewVisibility(ids.period, View.GONE)
            views.setViewVisibility(ids.photo, View.VISIBLE)
            views.setViewVisibility(ids.title, View.VISIBLE)

            views.setTextViewText(ids.title, entry.title.orEmpty())

            val imageBitmap = entry.imageFile?.let { BitmapFactory.decodeFile(it.path) }
            val appIcon = entry.packageName?.let { appIconBitmap(context, it) }
            if (imageBitmap != null) {
                views.setImageViewBitmap(ids.photo, circularBitmap(imageBitmap))
                if (appIcon != null) {
                    views.setImageViewBitmap(ids.badge, circularBitmap(appIcon))
                    views.setViewVisibility(ids.badge, View.VISIBLE)
                } else {
                    views.setViewVisibility(ids.badge, View.GONE)
                }
            } else {
                // No notification image: the app icon fills the main slot directly instead —
                // never both at once, so no badge here either.
                views.setViewVisibility(ids.badge, View.GONE)
                if (appIcon != null) {
                    views.setImageViewBitmap(ids.photo, circularBitmap(appIcon))
                } else {
                    views.setImageViewResource(ids.photo, R.drawable.ic_stat_mirror)
                }
            }
        }

        /**
         * LEFT button (pre-existing widget_view_toggle) — see WidgetViewModePrefs' class doc.
         * Only offered once there's actually a Sport match or "toutes notifs" entry worth
         * switching to when leaving LATEST (mirrors how widget_actions_container stays hidden
         * with nothing to show, see applyActions); always stays visible on SPORT/ALL_NOTIFS so
         * Yann can always get back to LATEST — even if the matches/notifs shown just dropped to
         * zero while he was looking at one of them.
         */
        private fun applyLeftToggle(context: Context, views: RemoteViews, currentView: WidgetViewModePrefs.WidgetView, hasAnythingElse: Boolean) {
            val visible = currentView != WidgetViewModePrefs.WidgetView.LATEST || hasAnythingElse
            views.setViewVisibility(R.id.widget_view_toggle, if (visible) View.VISIBLE else View.GONE)
            if (visible) {
                views.setOnClickPendingIntent(R.id.widget_view_toggle, toggleViewPendingIntent(context))
            }
        }

        /**
         * RIGHT button (NEW, widget_view_toggle_right) — flips directly between SPORT and
         * ALL_NOTIFS. Hidden on LATEST (nothing for it to do there — the left button already
         * covers getting to/from LATEST, see WidgetViewModePrefs' class doc).
         */
        private fun applyRightToggle(context: Context, views: RemoteViews, currentView: WidgetViewModePrefs.WidgetView) {
            val visible = currentView != WidgetViewModePrefs.WidgetView.LATEST
            views.setViewVisibility(R.id.widget_view_toggle_right, if (visible) View.VISIBLE else View.GONE)
            if (visible) {
                views.setOnClickPendingIntent(R.id.widget_view_toggle_right, toggleSportNotifsPendingIntent(context))
            }
        }

        private fun toggleViewPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, NowBarWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_VIEW
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun toggleSportNotifsPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, NowBarWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_SPORT_NOTIFS
            }
            return PendingIntent.getBroadcast(
                context,
                1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * Populates up to three text action buttons when the option is enabled in Settings AND
         * we still hold live PendingIntents for THIS exact notification (see the liveActions
         * doc above) — otherwise hides the whole row rather than showing dead buttons. Text
         * (the action's own label, e.g. "Répondre") rather than an icon: most notification
         * action icons are meant to be tinted/rendered by the system itself, so a raw icon
         * dropped into the widget was often unreadable — the label reads reliably regardless.
         */
        private fun applyActions(context: Context, views: RemoteViews, dataKey: String) {
            val actions = if (WidgetActionsPrefs.isEnabled(context) && liveActionsKey == dataKey) {
                liveActions
            } else {
                emptyList()
            }

            val slots = listOf(
                R.id.widget_action_1 to actions.getOrNull(0),
                R.id.widget_action_2 to actions.getOrNull(1),
                R.id.widget_action_3 to actions.getOrNull(2)
            )
            for ((viewId, action) in slots) {
                if (action == null) {
                    views.setViewVisibility(viewId, View.GONE)
                    continue
                }
                views.setViewVisibility(viewId, View.VISIBLE)
                views.setTextViewText(viewId, action.label)
                views.setOnClickPendingIntent(viewId, action.pendingIntent)
            }

            views.setViewVisibility(
                R.id.widget_actions_container,
                if (actions.isEmpty()) View.GONE else View.VISIBLE
            )
        }

        private fun dismissPendingIntent(context: Context, key: String): PendingIntent {
            val intent = Intent(context, MirrorNotificationListener::class.java).apply {
                action = MirrorNotificationListener.ACTION_DISMISS_WIDGET
                putExtra(MirrorNotificationListener.EXTRA_DISMISS_KEY, key)
            }
            return PendingIntent.getService(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * Fallback used whenever there's no live PendingIntent to attach (i.e. every render
         * that isn't happening right at the moment a notification was posted): opens the
         * source app itself rather than leaving the tap dead. Shared by all three views —
         * applyLatestContent (the mirrored app), applySofascoreMatches (Sofascore itself), and
         * applyAllNotifs (either the entry's own app, or Sofascore for a match-tile entry).
         */
        private fun launchAppPendingIntent(context: Context, packageName: String): PendingIntent? {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return null
            return PendingIntent.getActivity(
                context,
                packageName.hashCode(),
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun appIconBitmap(context: Context, packageName: String): Bitmap? {
            return try {
                drawableToBitmap(context.packageManager.getApplicationIcon(packageName))
            } catch (_: Throwable) {
                null
            }
        }

        private fun drawableToBitmap(drawable: Drawable): Bitmap {
            if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
            val width = drawable.intrinsicWidth.coerceAtLeast(1)
            val height = drawable.intrinsicHeight.coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            return bitmap
        }

        /** Crops [source] into a circle, matching the round chips in the reference design. */
        private fun circularBitmap(source: Bitmap): Bitmap {
            val size = minOf(source.width, source.height).coerceAtLeast(1)
            val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.drawOval(RectF(Rect(0, 0, size, size)), paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            val left = (source.width - size) / 2f
            val top = (source.height - size) / 2f
            canvas.drawBitmap(source, -left, -top, paint)
            return output
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = buildViews(context)
        appWidgetIds.forEach { id -> appWidgetManager.updateAppWidget(id, views) }
    }

    /**
     * Catches the two view-toggle broadcasts (left: ACTION_TOGGLE_VIEW, right:
     * ACTION_TOGGLE_SPORT_NOTIFS — see toggleViewPendingIntent/toggleSportNotifsPendingIntent)
     * before falling through to AppWidgetProvider's own onReceive, which is what normally
     * dispatches to onUpdate/onDeleted/etc. — same "handle our own action, then let the
     * superclass handle everything else" shape as MirrorNotificationListener.onStartCommand's
     * ACTION_DISMISS_WIDGET handling.
     */
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE_VIEW -> {
                WidgetViewModePrefs.toggleLatest(context)
                pushToAllWidgets(context, buildViews(context))
                return
            }
            ACTION_TOGGLE_SPORT_NOTIFS -> {
                WidgetViewModePrefs.toggleSportAllNotifs(context)
                pushToAllWidgets(context, buildViews(context))
                return
            }
        }
        super.onReceive(context, intent)
    }
}
