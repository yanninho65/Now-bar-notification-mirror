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
import android.os.Build
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
 * the notification itself) WITHOUT cancelling the source notification, see applySofascoreMatches.
 * Neither [image] nor [contentIntent]/[actions] is persisted (see SofascoreWidgetStore) — only
 * held in memory for the current process, same limitation as [WidgetAction] above and the mirror's
 * own liveContentIntent.
 *
 * [title]/[text] (NEW 18/09/2026, "peek" feature) are the raw Android notification title/text for
 * this match ("$homeTeam - $awayTeam" / the latest score line — see
 * SofascoreNotificationListenerService.rawTitleAndText) — see WidgetPeekPrefs' class doc for what
 * they're used for. [actions] mirrors [WidgetAction] handling on the mirror side (n.actions, gated
 * by WidgetActionsPrefs) — almost always empty in practice (Sofascore's own notifications rarely
 * carry action buttons), included for parity with the "avec bouton d'action si active" part of
 * Yann's request.
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
    val title: String,
    val text: String,
    val image: Bitmap?,
    val contentIntent: PendingIntent?,
    val actions: List<WidgetAction> = emptyList()
)

/**
 * One entry pushed into the widget's "Toutes notifs" history — see
 * [NowBarWidgetProvider.pushToAllNotifications] and WidgetAllNotificationsStore's class doc for
 * why this is a rolling log rather than a live/active set. [image]/[contentIntent]/[actions] follow
 * the same in-memory-only rule as [SofascoreWidgetMatch] above. [text]/[actions] (NEW 18/09/2026,
 * "peek" feature — see WidgetPeekPrefs' class doc) are what let a tile here be shown full-format
 * when tapped, in the exact same shape as the LATEST view, instead of only ever opening the source
 * app directly.
 */
data class AllNotifEntryPush(
    val key: String,
    val postTimeMillis: Long,
    val kind: WidgetAllNotificationsStore.Kind,
    val title: String? = null,
    val text: String? = null,
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
    val contentIntent: PendingIntent?,
    val actions: List<WidgetAction> = emptyList()
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
 *
 * PLUS a fourth, cross-cutting "peek" state (NEW 18/09/2026, see WidgetPeekPrefs' class doc — Yann:
 * "En vue toutes notifs ou sport, cliquer sur une icône doit ouvrir le texte de la notification en
 * question sous le même format que la dernière notif affichée"): tapping a tile in SPORT or
 * ALL_NOTIFS no longer opens the source app directly — it shows that ONE notification full-format,
 * reusing the exact same widget_latest_content block the LATEST view renders with (title, text,
 * image, dismiss button, action buttons), WITHOUT touching WidgetViewModePrefs.currentView — "je
 * précise bien que ça ne change rien à la vue dernière notif [...] elle doit toujours bien montrer
 * la dernière notif". While peeking, the small toggle under the left column keeps showing the
 * rotating-arrows glyph, doubling as a "back to the tile grid" button (Yann: "Mettre les flèches
 * tournantes pour le symboliser"); dismissing the peeked notification, or its disappearing for any
 * other reason (an action button that marked it read/archived it, say), closes the peek
 * automatically (Yann: "revenir automatiquement aux icônes") — see closePeekIfShowing, called from
 * both listener services. FIXED 18/09/2026, same request: tapping the peek's own text to open the
 * real notification now closes the peek too, instead of leaving it shown until the notification is
 * separately dismissed — Yann: "quand je clique sur une icône puis sur le texte pour ouvrir la
 * notif, revenir aux icônes dans le widget" — see openPeekContentPendingIntent. ALSO CHANGED same
 * day (Yann: "mettre l'icone de l'appli à gauche au lieu des grosses flèches qui tournent") — the
 * big 32dp icon slot (widget_app_icon) now shows the peeked entry's own source-app icon instead of
 * also switching to the arrows glyph; see applyPeekLeftColumn.
 */
class NowBarWidgetProvider : AppWidgetProvider() {

    companion object {

        private const val ACTION_TOGGLE_VIEW = "com.yann.nowbarmirror.widget.ACTION_TOGGLE_VIEW"
        private const val ACTION_TOGGLE_SPORT_NOTIFS = "com.yann.nowbarmirror.widget.ACTION_TOGGLE_SPORT_NOTIFS"
        private const val ACTION_OPEN_PEEK = "com.yann.nowbarmirror.widget.ACTION_OPEN_PEEK"
        private const val ACTION_CLOSE_PEEK = "com.yann.nowbarmirror.widget.ACTION_CLOSE_PEEK"
        // NEW 18/09/2026 (Yann: "quand je clique sur une icône puis sur le texte pour ouvrir la
        // notif, revenir aux icônes dans le widget") — a peek's own "open the real
        // notification/app" tap used to be wired DIRECTLY to that target PendingIntent
        // (renderLatestFormat's openIntent -> widget_latest_content), so tapping it left the peek
        // showing (only a dismiss/removal closed it, see closePeekIfShowing). It now goes through
        // this app's own broadcast first, carrying the real target as a Parcelable extra, so the
        // peek can be closed and the widget rebuilt into the tile grid BEFORE that target intent
        // is actually fired — see openPeekContentPendingIntent / onReceive.
        private const val ACTION_OPEN_PEEK_CONTENT = "com.yann.nowbarmirror.widget.ACTION_OPEN_PEEK_CONTENT"
        private const val EXTRA_PEEK_CONTENT_TARGET = "mirror.widget.peek_content_target"
        private const val EXTRA_PEEK_SOURCE = "mirror.widget.peek_source"
        private const val EXTRA_PEEK_ENTRY_ID = "mirror.widget.peek_entry_id"

        // Base request codes for the per-tile "open peek" PendingIntents (5 fixed slots per view,
        // see SOFASCORE_SLOT_IDS / ALL_NOTIF_SLOT_IDS below) — distinct ranges so a SPORT tile and
        // an ALL_NOTIFS tile at the same slot index never share a PendingIntent identity. Kept well
        // away from the toggle buttons' own request codes (0/1, see toggleViewPendingIntent/
        // toggleSportNotifsPendingIntent) and the dismiss buttons' (0, different target components
        // so no actual clash, but distinct ranges make this easier to reason about).
        private const val PEEK_REQUEST_CODE_SPORT_BASE = 4000
        private const val PEEK_REQUEST_CODE_ALL_NOTIFS_BASE = 4100

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
        // PendingIntent/action-list per match key, refreshed on every pushSofascoreMatches call. A
        // match whose key isn't in liveSofascoreIntents (stale after a process restart, or never
        // had a usable contentIntent) falls back to launchAppPendingIntent(SOFASCORE_PACKAGE) — see
        // resolvePeek. liveSofascoreActions has no such fallback: a match with nothing recorded
        // there just shows no action row, same as liveActions' own reset-on-restart limitation.
        private var liveSofascoreIntents: Map<String, PendingIntent> = emptyMap()
        private var liveSofascoreActions: Map<String, List<WidgetAction>> = emptyMap()

        // Same idea again for the "Toutes notifs" view, EXCEPT this one is additive rather than
        // fully replaced on every push (see pushToAllNotifications): unlike the Sofascore maps
        // above, entries here persist across many push events (it's a history, not a
        // recomputed-from-scratch active set), so a push only ADDS/refreshes its own entry and
        // then trims down to exactly the entries WidgetAllNotificationsStore is still keeping —
        // an older entry's live PendingIntent/actions survive in memory for as long as it stays in
        // the capped history AND this process stays alive; once either drops it, a tile/peek falls
        // back to launchAppPendingIntent (or Sofascore's package for a SOFASCORE_MATCH entry) for
        // the open intent, and simply shows no actions row for liveAllNotifActions.
        //
        // Keyed by [allNotifEntryId] (key + postTimeMillis), NOT by key alone (fixed 17/09/2026,
        // same identity fix as WidgetAllNotificationsStore — see its class doc): several tiles
        // can now share the same underlying notification key (a "Dernière notif"-mode app reusing
        // its notification id across distinct items), and each one must open ITS OWN article/
        // conversation when tapped, not whichever of them happened to push most recently.
        private var liveAllNotifIntents: Map<String, PendingIntent> = emptyMap()
        private var liveAllNotifActions: Map<String, List<WidgetAction>> = emptyMap()

        /** Composite identity for [liveAllNotifIntents]/[liveAllNotifActions] — see those fields' doc. */
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
         * PendingIntents/actions in memory for the KEPT matches only, persists the rest (including
         * the raw title/text now used for "peek" — see SofascoreWidgetMatch's doc), then rebuilds
         * whichever view/peek is currently showing (a Sport-view rebuild picks the new matches up
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
            liveSofascoreActions = kept.associate { m -> m.key to m.actions }

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
                        title = m.title,
                        text = m.text,
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
         * [liveAllNotifIntents]/[liveAllNotifActions] down to exactly the entries still kept — see
         * those fields' doc — before rebuilding whichever view/peek is currently showing.
         */
        fun pushToAllNotifications(context: Context, entry: AllNotifEntryPush) {
            val kept = WidgetAllNotificationsStore.push(
                context,
                WidgetAllNotificationsStore.PersistableEntry(
                    key = entry.key,
                    kind = entry.kind,
                    postTimeMillis = entry.postTimeMillis,
                    title = entry.title,
                    text = entry.text,
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
            liveAllNotifActions = buildMap {
                if (entry.actions.isNotEmpty()) put(entryId, entry.actions)
                keptIds.forEach { id ->
                    if (id != entryId) liveAllNotifActions[id]?.let { put(id, it) }
                }
            }

            pushToAllWidgets(context, buildViews(context))
        }

        /**
         * Closes an active "peek" (see WidgetPeekPrefs' class doc) if it's currently showing the
         * notification identified by [key] — called from both listener services'
         * onNotificationRemoved AND from both dismiss handlers (MirrorNotificationListener /
         * SofascoreNotificationListenerService), so a peek never keeps showing full-format detail
         * for a notification that's actually gone (Yann: "Si je supprime la notification ou la
         * fais disparaitre en marquant lu ou supprimer avec les boutons d'actions, revenir
         * automatiquement aux icônes"). A SPORT peek matches by [key] alone (a match's peek entryId
         * IS its key); an ALL_NOTIFS peek matches by the composite (key, postTimeMillis) id —
         * except when [postTimeMillis] is unknown (pass -1L), in which case it falls back to a
         * key-prefix match (used by the true-LATEST dismiss button, which never tracked postTime
         * for WidgetNotificationStore — see MirrorNotificationListener.EXTRA_DISMISS_POST_TIME's
         * doc; harmless there in practice since a peek is never active while LATEST is showing, but
         * kept correct rather than assumed). No-op, safe to call unconditionally, if no peek is
         * active or it points elsewhere. Pushes a fresh render itself when it does close something,
         * independent of whatever rebuild the caller's own surrounding code triggers.
         */
        fun closePeekIfShowing(context: Context, key: String, postTimeMillis: Long) {
            val peek = WidgetPeekPrefs.current(context) ?: return
            val matches = when (peek.source) {
                WidgetPeekPrefs.Source.SPORT -> peek.entryId == key
                WidgetPeekPrefs.Source.ALL_NOTIFS -> {
                    if (postTimeMillis >= 0) {
                        peek.entryId == allNotifEntryId(key, postTimeMillis)
                    } else {
                        peek.entryId.startsWith("$key::")
                    }
                }
            }
            if (matches) {
                WidgetPeekPrefs.close(context)
                pushToAllWidgets(context, buildViews(context))
            }
        }

        /**
         * Rebuilds and pushes from whatever the stores currently hold, with no live
         * PendingIntent available (used when just clearing the widget, refreshing after a
         * staleness check, or when the system calls onUpdate() independently of any specific
         * notification event). In the latest-notif view the tap falls back to opening the
         * source app, and the actions row — which has no such fallback — simply stays hidden;
         * in the other two views/the peek state, a tile/the open target falls back the same way
         * (see resolvePeek / applySofascoreMatches / applyAllNotifs).
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
            views.setImageViewResource(R.id.widget_app_icon, R.drawable.ic_notification_bell)
            views.setViewVisibility(R.id.widget_image, View.GONE)
            views.setViewVisibility(R.id.widget_dismiss, View.GONE)
            views.setViewVisibility(R.id.widget_actions_container, View.GONE)
            views.setViewVisibility(R.id.widget_view_toggle, View.GONE)
            views.setViewVisibility(R.id.widget_view_toggle_right, View.GONE)
            return views
        }

        private fun buildViewsUnsafe(context: Context, liveContentIntent: PendingIntent?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_now_bar)

            // "Peek" takes priority over the three normal views when active — see the class doc
            // and WidgetPeekPrefs. Self-heals if the peeked entry has vanished (aged out of its
            // store's top-5, or removed elsewhere) instead of ever getting stuck.
            val peek = WidgetPeekPrefs.current(context)
            val resolvedPeek = peek?.let { resolvePeek(context, it) }
            if (peek != null && resolvedPeek == null) {
                WidgetPeekPrefs.close(context)
            }

            if (resolvedPeek != null) {
                views.setViewVisibility(R.id.widget_sofascore_content, View.GONE)
                views.setViewVisibility(R.id.widget_all_notifs_content, View.GONE)
                views.setViewVisibility(R.id.widget_latest_content, View.VISIBLE)

                renderLatestFormat(
                    views,
                    title = resolvedPeek.title,
                    text = resolvedPeek.text,
                    image = resolvedPeek.image,
                    dismissIntent = resolvedPeek.dismissIntent,
                    // Wrapped so tapping to open the notification ALSO closes the peek and drops
                    // back to the tile grid, instead of just opening the target while the peek
                    // stays shown — see ACTION_OPEN_PEEK_CONTENT's doc.
                    openIntent = resolvedPeek.openIntent?.let { openPeekContentPendingIntent(context, it) },
                    actions = resolvedPeek.actions
                )
                applyPeekLeftColumn(context, views, resolvedPeek.iconPackageName)
                views.setViewVisibility(R.id.widget_view_toggle_right, View.GONE)
                return views
            }

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
            applyLatestIcon(context, views, data)

            if (data == null) {
                renderLatestFormat(
                    views,
                    title = context.getString(R.string.widget_empty_title),
                    text = "",
                    image = null,
                    dismissIntent = null,
                    openIntent = null,
                    actions = emptyList()
                )
                return
            }

            val imageBitmap = data.imageFile?.let { BitmapFactory.decodeFile(it.path) }
            val actions = if (WidgetActionsPrefs.isEnabled(context) && liveActionsKey == data.key) {
                liveActions
            } else {
                emptyList()
            }
            // Bound to widget_latest_content specifically (not the whole widget_root any more —
            // widget_root also contains the left/right columns, shared with the other views,
            // which must NOT open this notification when tapped) inside renderLatestFormat.
            val openIntent = liveContentIntent ?: launchAppPendingIntent(context, data.packageName)

            renderLatestFormat(
                views,
                title = data.title,
                text = data.text,
                image = imageBitmap,
                dismissIntent = dismissPendingIntent(context, data.key),
                openIntent = openIntent,
                actions = actions
            )
        }

        /** widget_app_icon for the TRUE LATEST view — "Idem quand il n'y a pas de notifs en vue texte" (18/09/2026): the bell replaces this app's own icon specifically for the "nothing to show" case; a real notification whose app-icon lookup itself fails keeps the pre-existing this-app-icon fallback, unrelated to that request. */
        private fun applyLatestIcon(context: Context, views: RemoteViews, data: WidgetNotificationStore.Data?) {
            if (data == null) {
                views.setImageViewResource(R.id.widget_app_icon, R.drawable.ic_notification_bell)
                return
            }
            val appIcon = appIconBitmap(context, data.packageName)
            if (appIcon != null) {
                views.setImageViewBitmap(R.id.widget_app_icon, circularBitmap(appIcon))
            } else {
                views.setImageViewResource(R.id.widget_app_icon, R.drawable.ic_stat_mirror)
            }
        }

        /**
         * CHANGED 18/09/2026 (Yann: "Afficher le même signe en plus grand sur la gauche en vue
         * sport") — used to show Sofascore's own app icon; now shows the stylized football-pitch
         * glyph instead (same one used on the right toggle for ALL_NOTIFS, see
         * applyRightToggleIcon/ic_football_pitch.xml), at the left column's full 32dp size.
         */
        private fun applySofascoreIcon(context: Context, views: RemoteViews) {
            views.setImageViewResource(R.id.widget_app_icon, R.drawable.ic_football_pitch)
        }

        /**
         * No single source app for a merged "Toutes notifs" feed — replaced 18/09/2026 (Yann:
         * "Remplacer l'icône sur la gauche en vue toutes notifs par une cloche représentant
         * notification") with a generic notification-bell glyph instead of this app's own icon.
         */
        private fun applyAllNotifsIcon(context: Context, views: RemoteViews) {
            views.setImageViewResource(R.id.widget_app_icon, R.drawable.ic_notification_bell)
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
         *
         * CHANGED 18/09/2026: a tap no longer opens Sofascore directly — it opens a "peek" (see
         * WidgetPeekPrefs' class doc) showing that match full-format inside the widget itself; the
         * actual "open Sofascore" action now lives on the peek's own content tap (see resolvePeek).
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

                views.setOnClickPendingIntent(
                    slotId,
                    openPeekPendingIntent(context, WidgetPeekPrefs.Source.SPORT, match.key, PEEK_REQUEST_CODE_SPORT_BASE + i)
                )
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
         *
         * CHANGED 18/09/2026: same as applySofascoreMatches above — a tap now opens a "peek"
         * instead of the notification directly (see WidgetPeekPrefs' class doc / resolvePeek).
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

                val entryId = allNotifEntryId(entry.key, entry.postTimeMillis)
                views.setOnClickPendingIntent(
                    ids.container,
                    openPeekPendingIntent(context, WidgetPeekPrefs.Source.ALL_NOTIFS, entryId, PEEK_REQUEST_CODE_ALL_NOTIFS_BASE + i)
                )
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
         * with nothing to show, see applyActionButtons); always stays visible on SPORT/ALL_NOTIFS so
         * Yann can always get back to LATEST — even if the matches/notifs shown just dropped to
         * zero while he was looking at one of them.
         *
         * CHANGED 18/09/2026 (Yann: "l'icône sur la gauche sert à changer la vue, laisser flèches
         * en dessous mais élargir la zone à l'icône pour que ce soit plus facile à cliquer"): the
         * same toggle PendingIntent is now ALSO bound to widget_app_icon whenever this button is
         * visible — the tiny 20dp arrows glyph alone was a fiddly target on a lock screen; the
         * 32dp icon right above it now shares the same action, so the pair reads as one
         * effectively bigger tap zone without any visual change.
         */
        private fun applyLeftToggle(context: Context, views: RemoteViews, currentView: WidgetViewModePrefs.WidgetView, hasAnythingElse: Boolean) {
            val visible = currentView != WidgetViewModePrefs.WidgetView.LATEST || hasAnythingElse
            views.setViewVisibility(R.id.widget_view_toggle, if (visible) View.VISIBLE else View.GONE)
            if (visible) {
                val toggleIntent = toggleViewPendingIntent(context)
                views.setOnClickPendingIntent(R.id.widget_view_toggle, toggleIntent)
                views.setOnClickPendingIntent(R.id.widget_app_icon, toggleIntent)
            }
        }

        /**
         * RIGHT button (widget_view_toggle_right) — flips directly between SPORT and ALL_NOTIFS.
         * Hidden on LATEST (nothing for it to do there — the left button already covers getting
         * to/from LATEST, see WidgetViewModePrefs' class doc).
         *
         * CHANGED 18/09/2026: the icon itself is context-dependent instead of always the plain
         * arrows glyph — see applyRightToggleIcon. FIXED same day (Yann: "sur la droite, supprimer
         * les flèches et agrandir l'icône cloche et ballon [...] Remplacer le ballon par une icône
         * de terrain de foot stylisee") — the arrows are gone entirely now (the icon alone, shown
         * bigger, is the affordance) and the ALL_NOTIFS-view icon is no longer derived from
         * Sofascore's own app icon; see ic_football_pitch.xml.
         */
        private fun applyRightToggle(context: Context, views: RemoteViews, currentView: WidgetViewModePrefs.WidgetView) {
            val visible = currentView != WidgetViewModePrefs.WidgetView.LATEST
            views.setViewVisibility(R.id.widget_view_toggle_right, if (visible) View.VISIBLE else View.GONE)
            if (visible) {
                applyRightToggleIcon(views, currentView)
                views.setOnClickPendingIntent(R.id.widget_view_toggle_right, toggleSportNotifsPendingIntent(context))
            }
        }

        /**
         * Picks the right toggle's icon based on which view is currently showing (see
         * applyRightToggle's doc for the request behind this) — both plain static drawables now,
         * no arrows, no runtime-composited bitmap:
         * - SPORT -> the plain bell glyph (ic_notification_bell), hinting at the ALL_NOTIFS view
         *   it leads to.
         * - ALL_NOTIFS -> the stylized football-pitch glyph (ic_football_pitch, see its own doc
         *   comment), hinting at the SPORT view it leads to — the SAME glyph shown larger on the
         *   left column while SPORT is showing (applySofascoreIcon).
         */
        private fun applyRightToggleIcon(views: RemoteViews, currentView: WidgetViewModePrefs.WidgetView) {
            when (currentView) {
                WidgetViewModePrefs.WidgetView.SPORT ->
                    views.setImageViewResource(R.id.widget_view_toggle_right, R.drawable.ic_notification_bell)
                WidgetViewModePrefs.WidgetView.ALL_NOTIFS ->
                    views.setImageViewResource(R.id.widget_view_toggle_right, R.drawable.ic_football_pitch)
                WidgetViewModePrefs.WidgetView.LATEST -> Unit // hidden in this view, see applyRightToggle
            }
        }

        /**
         * Left column while a "peek" is showing (see WidgetPeekPrefs' class doc). The small toggle
         * button beneath the main icon (widget_view_toggle) keeps showing the rotating-arrows
         * glyph as before (Yann: "Garder les petites"), still bound to the "close the peek, back
         * to the tile grid" action. FIXED 18/09/2026 (Yann: "vue texte dans widget : mettre
         * l'icone de l'appli à gauche au lieu des grosses flèches qui tournent") — the main
         * 32dp icon slot (widget_app_icon) used to ALSO switch to the arrows glyph; it now shows
         * [iconPackageName]'s own app icon instead (the peeked notification's source app — see
         * ResolvedPeek.iconPackageName / resolvePeek), same "this app's icon" treatment the true
         * LATEST view already uses (applyLatestIcon), falling back to ic_stat_mirror if the lookup
         * fails or no package is known. Both views stay bound to the same close action, same "one
         * enlarged tap zone" reasoning as applyLeftToggle above — only the big icon's PICTURE
         * changes, not what tapping it does.
         */
        private fun applyPeekLeftColumn(context: Context, views: RemoteViews, iconPackageName: String?) {
            val appIcon = iconPackageName?.let { appIconBitmap(context, it) }
            if (appIcon != null) {
                views.setImageViewBitmap(R.id.widget_app_icon, circularBitmap(appIcon))
            } else {
                views.setImageViewResource(R.id.widget_app_icon, R.drawable.ic_stat_mirror)
            }
            views.setViewVisibility(R.id.widget_view_toggle, View.VISIBLE)
            val backIntent = closePeekPendingIntent(context)
            views.setOnClickPendingIntent(R.id.widget_app_icon, backIntent)
            views.setOnClickPendingIntent(R.id.widget_view_toggle, backIntent)
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

        /** Opens a "peek" for one tile — see WidgetPeekPrefs' class doc. [requestCode] must be distinct per rendered tile (see PEEK_REQUEST_CODE_SPORT_BASE/PEEK_REQUEST_CODE_ALL_NOTIFS_BASE's doc) so up to 5 simultaneously-visible tiles each keep their own correctly-bound click. */
        private fun openPeekPendingIntent(context: Context, source: WidgetPeekPrefs.Source, entryId: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, NowBarWidgetProvider::class.java).apply {
                action = ACTION_OPEN_PEEK
                putExtra(EXTRA_PEEK_SOURCE, source.name)
                putExtra(EXTRA_PEEK_ENTRY_ID, entryId)
            }
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun closePeekPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, NowBarWidgetProvider::class.java).apply {
                action = ACTION_CLOSE_PEEK
            }
            return PendingIntent.getBroadcast(
                context,
                2,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * NEW 18/09/2026 (Yann: "quand je clique sur une icône puis sur le texte pour ouvrir la
         * notif, revenir aux icônes dans le widget") — wraps a peek's real "open" target
         * ([target], e.g. the mirrored notification's own contentIntent, or a launch-app fallback)
         * in a PendingIntent that routes through this app's own onReceive first: it closes the
         * peek and rebuilds the widget back into that view's tile grid, THEN fires [target] itself
         * (see the ACTION_OPEN_PEEK_CONTENT branch below) — a plain PendingIntent handed straight
         * to the widget host can't do both, since only one PendingIntent can be bound per view.
         * [target] rides along as a Parcelable extra (a PendingIntent can be put into another
         * Intent's extras) rather than being re-derived here, so this works identically for every
         * kind of peeked entry (mirrored notification, Sofascore match, launch-app fallback) with
         * no extra branching. Request code 3 is fine unwrapped/unshared: only one peek can ever be
         * showing at a time, so there's never more than one of these live at once.
         */
        private fun openPeekContentPendingIntent(context: Context, target: PendingIntent): PendingIntent {
            val intent = Intent(context, NowBarWidgetProvider::class.java).apply {
                action = ACTION_OPEN_PEEK_CONTENT
                putExtra(EXTRA_PEEK_CONTENT_TARGET, target)
            }
            return PendingIntent.getBroadcast(
                context,
                3,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * FIXED 18/09/2026 (Yann, after testing the fix above: "je t'assure que ça n'ouvre pas la
         * notif. Ça revient juste aux icônes.") — a plain [target].send() from inside onReceive
         * silently did nothing beyond closing the peek: before ACTION_OPEN_PEEK_CONTENT existed,
         * a tap on the peeked text fired [target] DIRECTLY from the widget host (LockStar/the
         * launcher) as a straight setOnClickPendingIntent — a single hop that Android always
         * privileges to start an activity, since it's the immediate result of the user's own tap.
         * Routing it through our own broadcast receiver first (so it can close the peek) added a
         * SECOND hop — our app calling [target].send() from a BroadcastReceiver that Android does
         * NOT consider to be "in the foreground" — which its background-activity-start
         * restrictions silently veto: no exception, the activity just never opens. [target].send()
         * with an [android.app.ActivityOptions] bundle whose
         * [android.app.ActivityOptions.setPendingIntentBackgroundActivityStartMode] is set to
         * MODE_BACKGROUND_ACTIVITY_START_ALLOWED is the OS-provided way for a sender to explicitly
         * bless the PendingIntent it's about to send for exactly this "was triggered by a real tap
         * a moment ago, chaining it is legitimate" case — only available from API 34 (Android 14)
         * onward, so below that this falls back to a plain send() (unaffected devices below 34
         * don't get the fix, but this app's own target device is well past that).
         */
        private fun sendAllowingBackgroundStart(context: Context, target: PendingIntent) {
            if (Build.VERSION.SDK_INT >= 34) {
                val options = android.app.ActivityOptions.makeBasic().apply {
                    pendingIntentBackgroundActivityStartMode =
                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }
                target.send(context, 0, null, null, null, null, options.toBundle())
            } else {
                target.send()
            }
        }

        /**
         * Shared renderer for widget_latest_content — used both by the TRUE LATEST view
         * (applyLatestContent) and by a "peek" (resolvePeek) showing a SPORT/ALL_NOTIFS tile
         * full-format instead of its grid. Populates title/text/image/dismiss/actions and the "tap
         * anywhere else to open" click target — exactly the shape Yann asked to unify ("le format
         * qui est uniformisé"). Never touches widget_app_icon, which callers set separately per
         * state (applyLatestIcon / applySofascoreIcon / applyAllNotifsIcon / applyPeekLeftColumn).
         */
        private fun renderLatestFormat(
            views: RemoteViews,
            title: String,
            text: String,
            image: Bitmap?,
            dismissIntent: PendingIntent?,
            openIntent: PendingIntent?,
            actions: List<WidgetAction>
        ) {
            views.setTextViewText(R.id.widget_title, title)
            views.setTextViewText(R.id.widget_text, text)

            if (image != null) {
                views.setImageViewBitmap(R.id.widget_image, circularBitmap(image))
                views.setViewVisibility(R.id.widget_image, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_image, View.GONE)
            }

            if (dismissIntent != null) {
                views.setViewVisibility(R.id.widget_dismiss, View.VISIBLE)
                views.setOnClickPendingIntent(R.id.widget_dismiss, dismissIntent)
            } else {
                views.setViewVisibility(R.id.widget_dismiss, View.GONE)
            }

            applyActionButtons(views, actions)

            if (openIntent != null) {
                views.setOnClickPendingIntent(R.id.widget_latest_content, openIntent)
            }
        }

        /**
         * Populates up to three text action buttons (the action's own label, e.g. "Répondre") —
         * text rather than an icon, same reasoning as before: most notification action icons are
         * meant to be tinted/rendered by the system itself, so a raw icon dropped into the widget
         * was often unreadable. Shared by the TRUE LATEST view and any "peek" — see
         * renderLatestFormat.
         */
        private fun applyActionButtons(views: RemoteViews, actions: List<WidgetAction>) {
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

        private data class ResolvedPeek(
            val title: String,
            val text: String,
            val image: Bitmap?,
            val dismissIntent: PendingIntent?,
            val openIntent: PendingIntent?,
            val actions: List<WidgetAction>,
            // NEW 18/09/2026 — the peeked entry's own source-app package, used by
            // applyPeekLeftColumn to show that app's icon on widget_app_icon (Yann: "mettre
            // l'icone de l'appli à gauche"). Sofascore for a SPORT match or an ALL_NOTIFS
            // SOFASCORE_MATCH entry, the mirrored notification's own package otherwise.
            val iconPackageName: String?
        )

        /**
         * Resolves a [WidgetPeekPrefs.Peek] pointer into everything [renderLatestFormat] needs, by
         * looking the entry up in whichever store still holds it — see WidgetPeekPrefs' class doc
         * for why the peek pointer itself carries no content. Returns null if the entry is no
         * longer there (aged out of the capped top-5, or removed elsewhere) — buildViewsUnsafe
         * treats that as "close the peek and fall back to that view's tile grid".
         */
        private fun resolvePeek(context: Context, peek: WidgetPeekPrefs.Peek): ResolvedPeek? {
            return when (peek.source) {
                WidgetPeekPrefs.Source.SPORT -> {
                    val match = SofascoreWidgetStore.get(context).firstOrNull { it.key == peek.entryId } ?: return null
                    ResolvedPeek(
                        title = match.title,
                        text = match.text,
                        image = match.imageFile?.let { BitmapFactory.decodeFile(it.path) },
                        dismissIntent = sofascoreDismissPendingIntent(context, match.key, match.postTimeMillis),
                        openIntent = liveSofascoreIntents[match.key]
                            ?: launchAppPendingIntent(context, SofascoreNotificationListenerService.SOFASCORE_PACKAGE),
                        actions = liveSofascoreActions[match.key] ?: emptyList(),
                        iconPackageName = SofascoreNotificationListenerService.SOFASCORE_PACKAGE
                    )
                }
                WidgetPeekPrefs.Source.ALL_NOTIFS -> {
                    val entry = WidgetAllNotificationsStore.get(context)
                        .firstOrNull { allNotifEntryId(it.key, it.postTimeMillis) == peek.entryId } ?: return null
                    val entryId = allNotifEntryId(entry.key, entry.postTimeMillis)
                    val isMatch = entry.kind == WidgetAllNotificationsStore.Kind.SOFASCORE_MATCH

                    val dismissIntent = if (isMatch) {
                        sofascoreDismissPendingIntent(context, entry.key, entry.postTimeMillis)
                    } else {
                        dismissPendingIntent(context, entry.key, entry.postTimeMillis)
                    }
                    val fallbackPackage = if (isMatch) SofascoreNotificationListenerService.SOFASCORE_PACKAGE else entry.packageName
                    val title = entry.title ?: (if (isMatch) "${entry.homeTeam} - ${entry.awayTeam}" else "")

                    ResolvedPeek(
                        title = title,
                        text = entry.text.orEmpty(),
                        image = entry.imageFile?.let { BitmapFactory.decodeFile(it.path) },
                        dismissIntent = dismissIntent,
                        openIntent = liveAllNotifIntents[entryId] ?: fallbackPackage?.let { launchAppPendingIntent(context, it) },
                        actions = liveAllNotifActions[entryId] ?: emptyList(),
                        iconPackageName = fallbackPackage
                    )
                }
            }
        }

        /**
         * Dismiss target for a GENERIC notification (the true LATEST view's own dismiss button, or
         * a GENERIC "peek" — see resolvePeek), routed through MirrorNotificationListener exactly as
         * before. [postTimeMillis] (NEW 18/09/2026, defaults to -1L/"unknown" for the true-LATEST
         * call site, which never tracked it) lets MirrorNotificationListener.closePeekIfShowing
         * match an ALL_NOTIFS peek precisely — see EXTRA_DISMISS_POST_TIME's doc there.
         */
        private fun dismissPendingIntent(context: Context, key: String, postTimeMillis: Long = -1L): PendingIntent {
            val intent = Intent(context, MirrorNotificationListener::class.java).apply {
                action = MirrorNotificationListener.ACTION_DISMISS_WIDGET
                putExtra(MirrorNotificationListener.EXTRA_DISMISS_KEY, key)
                putExtra(MirrorNotificationListener.EXTRA_DISMISS_POST_TIME, postTimeMillis)
            }
            return PendingIntent.getService(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * Dismiss target for a Sofascore-sourced notification (a SPORT match peek, or a
         * SOFASCORE_MATCH "Toutes notifs" peek — see resolvePeek) — NEW 18/09/2026, symmetric to
         * [dismissPendingIntent] above but routed through SofascoreNotificationListenerService,
         * since that's the listener with the notification-access grant covering Sofascore (see
         * README's "Two separate notification-access toggles").
         */
        private fun sofascoreDismissPendingIntent(context: Context, key: String, postTimeMillis: Long): PendingIntent {
            val intent = Intent(context, SofascoreNotificationListenerService::class.java).apply {
                action = SofascoreNotificationListenerService.ACTION_DISMISS_WIDGET
                putExtra(SofascoreNotificationListenerService.EXTRA_DISMISS_KEY, key)
                putExtra(SofascoreNotificationListenerService.EXTRA_DISMISS_POST_TIME, postTimeMillis)
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
         * source app itself rather than leaving the tap dead. Shared by all views/the peek state —
         * applyLatestContent (the mirrored app) and resolvePeek (Sofascore itself for a SPORT
         * match, or either the entry's own app or Sofascore for an ALL_NOTIFS peek).
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

        // whiteSilhouette/sofascoreToggleIconBitmap (composited the rotating-arrows glyph with a
        // white silhouette of Sofascore's own icon for the ALL_NOTIFS-view right toggle) were
        // REMOVED 18/09/2026 along with ic_widget_switch_to_notifs.xml — see applyRightToggleIcon
        // for why the right toggle no longer needs either the arrows or a runtime-derived bitmap.
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = buildViews(context)
        appWidgetIds.forEach { id -> appWidgetManager.updateAppWidget(id, views) }
    }

    /**
     * Catches this widget's own broadcasts (left toggle, right toggle, open peek, close peek —
     * see the class doc and the various *PendingIntent builders above) before falling through to
     * AppWidgetProvider's own onReceive, which is what normally dispatches to
     * onUpdate/onDeleted/etc. — same "handle our own action, then let the superclass handle
     * everything else" shape as MirrorNotificationListener.onStartCommand's ACTION_DISMISS_WIDGET
     * handling.
     */
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE_VIEW -> {
                // Defensive: the toggle buttons aren't bound to this action while a peek is
                // showing (see applyPeekLeftColumn), so this should never actually fire mid-peek —
                // closed here anyway as cheap insurance against any stale binding.
                WidgetPeekPrefs.close(context)
                WidgetViewModePrefs.toggleLatest(context)
                pushToAllWidgets(context, buildViews(context))
                return
            }
            ACTION_TOGGLE_SPORT_NOTIFS -> {
                WidgetPeekPrefs.close(context)
                WidgetViewModePrefs.toggleSportAllNotifs(context)
                pushToAllWidgets(context, buildViews(context))
                return
            }
            ACTION_OPEN_PEEK -> {
                val sourceName = intent.getStringExtra(EXTRA_PEEK_SOURCE)
                val entryId = intent.getStringExtra(EXTRA_PEEK_ENTRY_ID)
                val source = sourceName?.let { name ->
                    try {
                        WidgetPeekPrefs.Source.valueOf(name)
                    } catch (_: Throwable) {
                        null
                    }
                }
                if (source != null && entryId != null) {
                    WidgetPeekPrefs.open(context, source, entryId)
                    pushToAllWidgets(context, buildViews(context))
                }
                return
            }
            ACTION_CLOSE_PEEK -> {
                WidgetPeekPrefs.close(context)
                pushToAllWidgets(context, buildViews(context))
                return
            }
            // NEW 18/09/2026 (Yann: "quand je clique sur une icône puis sur le texte pour ouvrir
            // la notif, revenir aux icônes dans le widget") — see openPeekContentPendingIntent's
            // doc. Closes the peek and rebuilds the widget FIRST, then fires the real target
            // (the notification/app the tap was actually meant to open) — order matters here
            // since the widget update is a local, synchronous call while sending the target hands
            // off to another app/process. FIXED same day (Yann: "je t'assure que ça n'ouvre pas
            // la notif. Ça revient juste aux icônes.") — see sendAllowingBackgroundStart's doc for
            // why a plain target.send() silently failed to actually open anything here.
            ACTION_OPEN_PEEK_CONTENT -> {
                WidgetPeekPrefs.close(context)
                pushToAllWidgets(context, buildViews(context))
                peekContentTarget(intent)?.let { target ->
                    try {
                        sendAllowingBackgroundStart(context, target)
                    } catch (_: Throwable) {
                        // Either the wrapped notification/app PendingIntent is no longer valid
                        // (source notification gone, process restarted), or the OS refused the
                        // activity start outright — the peek is already closed above either way,
                        // so there's nothing further to do.
                    }
                }
                return
            }
        }
        super.onReceive(context, intent)
    }

    /** Type-safe [Intent.getParcelableExtra] for [EXTRA_PEEK_CONTENT_TARGET], following the same SDK_INT-gated pattern already used elsewhere in this app (e.g. AppSelectionActivity.installedApplications). */
    @Suppress("DEPRECATION")
    private fun peekContentTarget(intent: Intent): PendingIntent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_PEEK_CONTENT_TARGET, PendingIntent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_PEEK_CONTENT_TARGET)
        }
    }
}
