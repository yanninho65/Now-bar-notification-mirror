package com.yann.nowbarmirror.widget

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.app.Notification
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.yann.nowbarmirror.BitmapUtils
import com.yann.nowbarmirror.MirrorNotificationListener
import com.yann.nowbarmirror.R
import com.yann.nowbarmirror.WatchNotificationSync
import com.yann.nowbarmirror.WatchCompanionLink
import com.yann.nowbarmirror.sport.SofascoreNotificationListenerService
import java.util.concurrent.atomic.AtomicBoolean

/** One notification action, rendered as a small text button (the action's own label) in the widget. */
data class WidgetAction(
    val label: String,
    val pendingIntent: PendingIntent,
    // NEW 21/09/2026 (Yann: "les notifications dont une action est supprimer ou marquer comme lu,
    // je clique dessus et rien ne se passe [...] ça marche très bien dans la Now Bar") — true for a
    // "silent" action, one whose semantic role (getSemanticAction(), see WidgetAction.from) is one
    // that normally makes the notification disappear once handled (mark as read / delete / archive
    // / mute). Tapping such a button for real, in the Now Bar/shade, also has the SYSTEM auto-cancel
    // that notification as part of that exact click-dispatch code path — that auto-cancel is what
    // actually makes it disappear, not something the action's own PendingIntent does by itself.
    // Replaying that same PendingIntent from anywhere else (our widget button, the watch relay)
    // never goes through that dispatch path: the source app still does its real work (WhatsApp/
    // Gmail do mark the item read/delete it), but nothing here ever reflects it, which is exactly
    // what looked like "rien ne se passe". "Appeler"/"Répondre"-style actions don't have this
    // problem — they open their own screen, so this stays false for those (no well-behaved app tags
    // a UI-opening button with one of the above semantic actions) and they're left untouched. See
    // [WidgetAction.from], [fireAction] and NowBarWidgetProvider.actionFirePendingIntent for how
    // this flag is used to fix it.
    val dismissesOnFire: Boolean = false
) {
    companion object {
        private val DISMISSING_SEMANTIC_ACTIONS = setOf(
            Notification.Action.SEMANTIC_ACTION_MARK_AS_READ,
            Notification.Action.SEMANTIC_ACTION_DELETE,
            Notification.Action.SEMANTIC_ACTION_ARCHIVE,
            Notification.Action.SEMANTIC_ACTION_MUTE
        )

        /**
         * Builds a [WidgetAction] from one of a notification's own [Notification.Action]s, or null
         * if it has no usable label/PendingIntent — shared by MirrorNotificationListener's and
         * SofascoreNotificationListenerService's own widgetActionsFor. [dismissesOnFire] is based on
         * getSemanticAction() alone (API 28+, false below that, no worse than before this fix) —
         * there's no public getter for the platform Action's own "shows no UI" hint (that flag only
         * exists as a hidden extra androidx.core.app.NotificationCompat.Action reads back out, not
         * on the raw platform android.app.Notification.Action this reads from), but a semantic
         * action of mark-as-read/delete/archive/mute is already a strong enough signal on its own:
         * a well-behaved app doesn't tag a reply/call/UI-opening button with one of those.
         */
        fun from(action: Notification.Action): WidgetAction? {
            val pi = action.actionIntent ?: return null
            val label = action.title?.toString()?.takeIf { it.isNotBlank() } ?: return null
            val dismissesOnFire = Build.VERSION.SDK_INT >= 28 &&
                action.semanticAction in DISMISSING_SEMANTIC_ACTIONS
            return WidgetAction(label = label, pendingIntent = pi, dismissesOnFire = dismissesOnFire)
        }
    }
}

/**
 * One Sofascore match as pushed from SofascoreNotificationListenerService.pushWidgetMatches — see
 * its doc. [image] is Sofascore's own combined notification image (may be null, see
 * NotificationImageExtractor) and [contentIntent] is that SPECIFIC notification's own live
 * PendingIntent — tapping a match tile must open Sofascore on that exact match (same as tapping
 * the notification itself) WITHOUT cancelling the source notification, see applySofascoreMatches.
 * Neither [image] nor [contentIntent]/[actions] is persisted (see SofascoreWidgetStore) — only
 * held in memory for the current process, same limitation as [WidgetAction] above and
 * liveAllNotifIntents/liveAllNotifActions below.
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
    val postTimeMillis: Long,
    val title: String,
    val text: String,
    val image: Bitmap?,
    val contentIntent: PendingIntent?,
    val actions: List<WidgetAction> = emptyList(),
    // AUDIT 23/09/2026 — lazy alternative to [image] (see WidgetImageFiles): only invoked for the
    // matches actually kept after sorting/capping, and only when that posting's image isn't
    // already on disk — instead of extracting every active match's image on every event.
    val imageLoader: (() -> Bitmap?)? = null
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
    val image: Bitmap?,
    val contentIntent: PendingIntent?,
    val actions: List<WidgetAction> = emptyList(),
    // NEW 21/09/2026, watch "Notification" complication detail screen (Yann : "quand je clique
    // sur la complication notification ça ouvre une fenêtre [...] pour les notifications comme
    // les messages ou Sofascore, afficher toutes les notifs de l'expéditeur ou toutes celles du
    // match"). Rather than a new persisted history (Yann, précision : "Ils envoient plusieurs
    // notifications dans un seul groupe [...] il suffit de lire le centre de notifs [...] ce que
    // l'application fait déjà normalement"), this is read straight off the SAME live
    // StatusBarNotification already being mirrored, at the exact moment it's pushed:
    // - MirrorNotificationListener, only for a conversation (isConversation=true): each message
    //   text from the notification's own NotificationCompat.MessagingStyle (EXTRA_MESSAGES/
    //   EXTRA_HISTORIC_MESSAGES already bundled by Android into that ONE notification, most
    //   recent first — see MirrorNotificationListener.messageLinesFor). Empty for a non-
    //   conversation GENERIC entry: its single [text] above already covers it, nothing else to
    //   show.
    // - SofascoreNotificationListenerService: the notification's own EXTRA_TEXT_LINES (Inbox
    //   style, already capped at 6 by Android, already most-recent-first — see its collectLines).
    // Same in-memory-only rule as [contentIntent]/[actions] above (see liveAllNotifDetailLines) —
    // lost across a process restart, same already-accepted limitation as those two.
    val detailLines: List<String> = emptyList(),
    // AUDIT 23/09/2026 — same lazy-image idea as SofascoreWidgetMatch.imageLoader.
    val imageLoader: (() -> Bitmap?)? = null
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
 * events, without needing a periodic background refresh — this app has none, by design: every
 * store here is only ever written in reaction to a real notification-listener event, never on a
 * timer.
 */
internal fun <T> List<T>.sortedForWidget(
    nowMillis: Long,
    statusOf: (T) -> String,
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
 * - LATEST ("Dernière notif"): whichever entry is currently MOST RECENT in
 *   WidgetAllNotificationsStore (see applyLatestContent) — any app configured with a mirror mode,
 *   ALL/LATEST alike, AND Sofascore matches, no distinction (MERGED 20/09/2026: this used to be a
 *   separate store, WidgetNotificationStore, written by its own dedicated push and never eligible
 *   for a Sofascore match — see applyLatestContent's own doc for why deriving it from the same
 *   history instead is both simpler and self-healing).
 * - SPORT: up to 5 Sofascore matches from SofascoreWidgetStore, pushed by
 *   SofascoreNotificationListenerService whenever a Sofascore notification changes.
 * - ALL_NOTIFS ("Toutes notifs"): up to 5 recently received notifications from
 *   WidgetAllNotificationsStore, fed by both MirrorNotificationListener (generic) and
 *   SofascoreNotificationListenerService (Sofascore, kept in its match-tile presentation) — the
 *   SAME history LATEST above now reads too.
 *
 * PLUS a fourth, cross-cutting "peek" state (NEW 18/09/2026, see WidgetPeekPrefs' class doc — Yann:
 * "En vue toutes notifs ou sport, cliquer sur une icône doit ouvrir le texte de la notification en
 * question sous le même format que la dernière notif affichée"): tapping a tile's icon in SPORT or
 * ALL_NOTIFS shows that ONE notification full-format, reusing the exact same widget_latest_content
 * block the LATEST view renders with (title, text, image, dismiss button, action buttons), WITHOUT
 * touching WidgetViewModePrefs.currentView — "je précise bien que ça ne change rien à la vue
 * dernière notif [...] elle doit toujours bien montrer la dernière notif". While peeking, the small
 * toggle under the left column keeps showing the rotating-arrows glyph, doubling as a "back to the
 * tile grid" button (Yann: "Mettre les flèches tournantes pour le symboliser"); dismissing the
 * peeked notification, or its disappearing for any other reason (an action button that marked it
 * read/archived it, say), closes the peek automatically (Yann: "revenir automatiquement aux
 * icônes") — see closePeekIfShowing, called from both listener services. ALSO CHANGED 18/09/2026
 * (Yann: "mettre l'icone de l'appli à gauche au lieu des grosses flèches qui tournent") — the big
 * 32dp icon slot (widget_app_icon) shows the peeked entry's own source-app icon instead of also
 * switching to the arrows glyph; see applyPeekLeftColumn.
 *
 * REWORKED 20/09/2026 — the peek's own content tap, and how the peek closes, both went through
 * several Activity-trampoline iterations (closing the peek AND relaying the tap into the real
 * target from one invisible Activity, so a lock-widget host would treat it as a direct,
 * no-manual-swipe tap same as Dernière notif's own) that each looked right on paper but broke on
 * Yann's actual lock-screen host in a new way every time — most recently (today): tapping a tile's
 * icon on the lock screen briefly flashed the peek and then fully unlocked the phone with the real
 * notification never opened, and tapping the peek's own text on the home-screen widget just landed
 * back on the same peek instead of opening the notification and returning to the grid. Yann's own
 * diagnosis: "c'est quand j'ai introduit le mécanisme de retour à la vue toutes icônes après
 * ouverture de la notif que ça a commencé à bugger" — the ENTIRE family of bugs traces back to
 * inserting an extra invisible Activity hop into what used to be a single, direct tap, since
 * *any* Activity a lock-screen widget host launches interacts with the keyguard one way or
 * another, whether or not that Activity's own code asks it to. The fix removes that hop instead of
 * trying to tame it:
 * - Opening a peek (a tile's icon tap) is a plain BROADCAST again (ACTION_OPEN_PEEK below) — no
 *   Activity at all, so nothing for a keyguard to react to. It only ever needs to flip
 *   WidgetPeekPrefs and push a widget update; it was never the one relaying to a second,
 *   real target the way the peek's own tap is, so there's no "second hop needs a direct Activity
 *   tap" problem here to begin with — OpenPeekTrampolineActivity solved a problem this action
 *   never actually had.
 * - The peek's own content tap goes back to being a plain, DIRECT PendingIntent straight to
 *   [ResolvedPeek.openIntent] — exactly the same shape as Dernière notif's own tap, which has
 *   never had any of these issues — instead of being wrapped through an Activity that first
 *   closes the peek and then relays the tap onward.
 * - Since tapping the peek's content no longer closes it as a side effect, closing now happens on
 *   its own timer instead — Yann: "on peut imaginer que au bout de 15 secondes d'affichage de la
 *   vue texte ouverte depuis les icônes, le widget revienne automatiquement à la vue icônes. Comme
 *   ce n'est pas lié au tap, ça simplifie peut-être." Opening a peek schedules a one-shot
 *   ACTION_AUTO_CLOSE_PEEK alarm [AUTO_CLOSE_PEEK_DELAY_MILLIS] later (see scheduleAutoClosePeek);
 *   it closes the peek only if it's still showing the SAME entry that scheduled it (a dismissal,
 *   a re-peek of a different tile, or the peek self-healing away in the meantime all make it a
 *   no-op) — see the ACTION_AUTO_CLOSE_PEEK branch in onReceive. A dismissal, a view toggle, or
 *   ACTION_CLOSE_PEEK all still close the peek immediately as before, via [closePeekAndCancelAlarm],
 *   which also cancels this alarm so it doesn't fire pointlessly (or, worse, against a peek Yann
 *   re-opened on the same tile in the meantime — closePeekAndCancelAlarm always runs before any
 *   later WidgetPeekPrefs.open, so a stale alarm can never outlive the peek it was scheduled for).
 */
class NowBarWidgetProvider : AppWidgetProvider() {

    // NEW 21/09/2026 — result of [fireAction] below. Declared directly on the class (NOT inside
    // companion object, where a nested type isn't reachable as NowBarWidgetProvider.FireActionResult
    // the way a companion FUNCTION is — only NowBarWidgetProvider.Companion.FireActionResult would
    // resolve there; every call site here uses the plain NowBarWidgetProvider.FireActionResult
    // form, which needs it declared here instead).
    enum class FireActionResult { NOT_FOUND, FIRED, FIRED_DISMISS }

    companion object {

        private const val ACTION_TOGGLE_VIEW = "com.yann.nowbarmirror.widget.ACTION_TOGGLE_VIEW"
        private const val ACTION_TOGGLE_SPORT_NOTIFS = "com.yann.nowbarmirror.widget.ACTION_TOGGLE_SPORT_NOTIFS"
        private const val ACTION_CLOSE_PEEK = "com.yann.nowbarmirror.widget.ACTION_CLOSE_PEEK"

        // ACTION_OPEN_PEEK (a tile's icon tap) — REINTRODUCED as a plain broadcast 20/09/2026 (see
        // the class doc's "REWORKED 20/09/2026" section) after a same-day detour through an
        // Activity trampoline (OpenPeekTrampolineActivity, now deleted) turned out to interact with
        // the lock screen's keyguard on Yann's actual device/host in a way this app's own code
        // never asked for and couldn't prevent. This action only ever flips WidgetPeekPrefs and
        // pushes a widget update — no second PendingIntent to relay to, so there was never a
        // "second hop needs a direct Activity tap" problem here to justify an Activity in the
        // first place; a broadcast doesn't touch the keyguard at all, which is exactly what's
        // wanted for an action that's meant to update the widget IN PLACE without leaving the lock
        // screen.
        private const val ACTION_OPEN_PEEK = "com.yann.nowbarmirror.widget.ACTION_OPEN_PEEK"
        private const val EXTRA_PEEK_SOURCE = "mirror.widget.peek_source"
        private const val EXTRA_PEEK_ENTRY_ID = "mirror.widget.peek_entry_id"

        // Fires [AUTO_CLOSE_PEEK_DELAY_MILLIS] after a peek opens and closes it — but only if it's
        // still showing the SAME entry that scheduled it — see the class doc's "REWORKED
        // 20/09/2026" section and the ACTION_AUTO_CLOSE_PEEK branch in onReceive. Replaces closing
        // the peek as a side effect of tapping its own content, which is what all the trampoline
        // back-and-forth above was really trying to make reliable on Yann's lock-screen host.
        private const val ACTION_AUTO_CLOSE_PEEK = "com.yann.nowbarmirror.widget.ACTION_AUTO_CLOSE_PEEK"
        private const val AUTO_CLOSE_PEEK_DELAY_MILLIS = 15_000L
        // Single fixed request code is correct here (unlike the old, now-removed
        // PeekOpenTrampolineActivity one that used to carry a different real target PendingIntent
        // as an extra on every call): this alarm's extras only ever encode WHICH entry it's for, as
        // plain strings, and android.app.AlarmManager.set() with an equivalent PendingIntent
        // (same requestCode/action/component) always replaces whatever alarm was previously
        // scheduled under it — exactly the "only one peek, only one pending auto-close, and a new
        // one always supersedes an older one" behavior wanted here.
        private const val AUTO_CLOSE_PEEK_REQUEST_CODE = 4200

        // Base request codes for the per-tile "open peek" PendingIntents (5 fixed slots per view,
        // see SOFASCORE_SLOT_IDS / ALL_NOTIF_SLOT_IDS below) — distinct ranges so a SPORT tile and
        // an ALL_NOTIFS tile at the same slot index never share a PendingIntent identity. Kept well
        // away from the toggle buttons' own request codes (0/1, see toggleViewPendingIntent/
        // toggleSportNotifsPendingIntent) and the dismiss buttons' (0, different target components
        // so no actual clash, but distinct ranges make this easier to reason about).
        private const val PEEK_REQUEST_CODE_SPORT_BASE = 4300
        private const val PEEK_REQUEST_CODE_ALL_NOTIFS_BASE = 4400

        // Base request code for a "silent" action button's routed PendingIntent (see
        // actionFirePendingIntent) — up to 3 of these (actionIndex 0..2) can be visible at once,
        // unlike the single dismiss button that gets away with a fixed request code of 0.
        private const val ACTION_FIRE_REQUEST_CODE_BASE = 4500

        // NEW 22/09/2026 (Yann, écran verrouillé : "ça marche à peu près [...] mais ça affiche la
        // notification créée par toi. On pourrait s'en passer ?") — voir openEntry's doc : la
        // notification "Aff. sur tél." reste nécessaire pour déclencher le plein écran (l'API
        // Android l'exige), mais une fois le tap montre déjà résolu en plein écran automatique
        // (téléphone verrouillé, voir isDeviceLocked), elle n'a plus aucune utilité — contrairement
        // au cas téléphone déverrouillé, où elle DOIT rester (repli tap manuel, voir openEntry).
        // Elle s'auto-annule donc après un court délai, mais SEULEMENT si le téléphone était
        // verrouillé au moment de l'ouverture : ce délai (au lieu d'une annulation immédiate) laisse
        // le temps au système de déclencher le plein écran avant de faire disparaître la
        // notification qui le porte — l'annuler trop tôt risquerait de supprimer le déclencheur
        // avant qu'Android ait eu l'occasion de l'utiliser.
        private const val ACTION_AUTO_CANCEL_OPEN_ON_PHONE = "com.yann.nowbarmirror.widget.ACTION_AUTO_CANCEL_OPEN_ON_PHONE"
        private const val AUTO_CANCEL_OPEN_ON_PHONE_DELAY_MILLIS = 2_500L
        private const val AUTO_CANCEL_OPEN_ON_PHONE_REQUEST_CODE = 4600

        // Same in-memory-only trick as liveAllNotifIntents/liveAllNotifActions below, for the Sofascore view: one live
        // PendingIntent/action-list per match key, refreshed on every pushSofascoreMatches call. A
        // match whose key isn't in liveSofascoreIntents (stale after a process restart, or never
        // had a usable contentIntent) falls back to launchAppPendingIntent(SOFASCORE_PACKAGE) — see
        // resolvePeek. liveSofascoreActions has no such fallback: a match with nothing recorded
        // there just shows no action row, same as liveAllNotifActions' own reset-on-restart limitation.
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

        // Same in-memory-only/same-lifetime rule as liveAllNotifActions right above (NEW
        // 21/09/2026, watch detail screen — see AllNotifEntryPush.detailLines' doc): one entry's
        // worth of extra history lines (conversation messages / Sofascore match events), keyed
        // the same way (allNotifEntryId), trimmed the same way in [pushToAllNotificationsBatch].
        private var liveAllNotifDetailLines: Map<String, List<String>> = emptyMap()

        /** Composite identity for [liveAllNotifIntents]/[liveAllNotifActions] — see those fields' doc. */
        internal fun allNotifEntryId(key: String, postTimeMillis: Long) = "$key::$postTimeMillis"

        // Guards [syncWatchToLatest] against sending a redundant putDataItem to the watch on
        // every widget rebuild (a view toggle, a peek open/close…) that doesn't actually change
        // which notification is most recent — see that function's doc. null means "never synced
        // yet this process" (or explicitly cleared), so the very first render after a process
        // restart always (re)syncs once, same self-healing spirit as the rest of this file.
        private var lastSyncedWatchIdentity: String? = null

        // AUDIT 23/09/2026 — coalesced widget refresh, see [requestUpdate].
        private const val REFRESH_COALESCE_MILLIS = 300L
        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
        @Volatile private var refreshContext: Context? = null
        private val refreshPending = AtomicBoolean(false)
        private val coalescedRefresh = Runnable {
            refreshContext?.let { refreshAllNow(it) }
        }

        // AUDIT 23/09/2026 — see [pushSofascoreMatches]: what was last saved, to skip identical
        // re-saves (every ApiOverrideFollowService poll ends up here without the widget content
        // having changed, since the widget never shows API overrides).
        private var lastSofascoreSignature: String? = null

        /**
         * Keeps the watch complication "Notification" aligned on whatever the widget's "Dernière
         * notif" view itself shows — both now read the exact SAME thing
         * (WidgetAllNotificationsStore.get(context).firstOrNull(), see applyLatestContent) instead
         * of the old separate WidgetNotificationStore that pushLive() used to write on its own.
         *
         * MERGED 20/09/2026 (Yann: "Fusionne toutes les listes que tu peux pour optimiser [...]
         * Sofascore peut apparaître en dernière notif. Toutes les notifs des applis choisies
         * peuvent y apparaître") — called from the very top of [buildViewsUnsafe], i.e. on every
         * widget rebuild, rather than from a single dedicated push call site that every new
         * mutation path had to remember to call too. That per-call-site duplication is exactly
         * what let "Dernière notif"/la montre drift out of sync with "Toutes notifs" (fixed
         * earlier today for two separate removal paths before this merge made the whole class of
         * bug impossible: there's simply no separate state left to forget to update). Guarded by
         * [lastSyncedWatchIdentity] so a rebuild that doesn't change the most-recent entry (a view
         * toggle, opening/closing a peek…) doesn't also spam the Wear Data Layer API.
         */
        private fun syncWatchToLatest(context: Context) {
            val entry = WidgetAllNotificationsStore.get(context).firstOrNull()
            val identity = entry?.let { "${it.key}::${it.postTimeMillis}" }
            if (identity == lastSyncedWatchIdentity) return
            lastSyncedWatchIdentity = identity

            if (entry == null) {
                WatchNotificationSync.sendCleared(context)
                return
            }
            val content = resolveAllNotifEntryContent(context, entry)
            WatchNotificationSync.send(
                context,
                title = content.title,
                text = content.text,
                packageName = content.iconPackageName ?: entry.packageName.orEmpty(),
                image = content.image,
                // NEW 21/09/2026, watch detail screen (see AllNotifEntryPush.detailLines' doc) —
                // entryKey/entryPostTimeMillis/kind let the watch address its action/dismiss
                // requests back at exactly this entry (see WearActionRelayService); actionLabels
                // is just the label text (no PendingIntent can cross to the watch — see
                // fireAction, which looks the real one back up on this side by that same identity
                // when the watch asks for actionIndex N).
                detailLines = content.detailLines,
                actionLabels = content.actions.map { it.label },
                entryKey = entry.key,
                entryPostTimeMillis = entry.postTimeMillis,
                kind = entry.kind.name
            )
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
         *
         * [matches]' OWN size (before the `.take` below) is [matches]' full, uncapped count of
         * currently active Sofascore matches — passed through to SofascoreWidgetStore.save as
         * [SofascoreWidgetStore.save]'s `activeCount`, so NowBarWidgetProviderTriple's "+X" overflow
         * badge can reflect the real total instead of just [SofascoreWidgetStore.MAX_SLOTS] (see
         * that param's own doc for the "+1 alors qu'il y a 17 matchs" bug this fixes).
         */
        fun pushSofascoreMatches(context: Context, matches: List<SofascoreWidgetMatch>, activeCount: Int = matches.size) {
            val kept = matches.sortedForWidget(
                nowMillis = System.currentTimeMillis(),
                statusOf = { it.status },
                postTimeOf = { it.postTimeMillis }
            ).take(SofascoreWidgetStore.MAX_SLOTS)

            liveSofascoreIntents = kept.mapNotNull { m -> m.contentIntent?.let { m.key to it } }.toMap()
            liveSofascoreActions = kept.associate { m -> m.key to m.actions }

            // AUDIT 23/09/2026 — nothing the widget renders changed (same matches, same postings,
            // same score/status, same count): skip the store write and the 3-widget rebuild.
            val signature = kept.joinToString("|") { m ->
                "${m.key}/${m.postTimeMillis}/${m.homeScore}/${m.awayScore}/${m.lastScorer}/${m.status}/${m.text}"
            } + "#$activeCount"
            if (signature == lastSofascoreSignature) return
            lastSofascoreSignature = signature

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
                        postTimeMillis = m.postTimeMillis,
                        title = m.title,
                        text = m.text,
                        image = m.image,
                        imageLoader = m.imageLoader
                    )
                },
                activeCount = activeCount
            )

            requestUpdate(context)
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
            pushToAllNotificationsBatch(context, listOf(entry))
        }

        /**
         * Batched version of [pushToAllNotifications] for several entries pushed together — added
         * 20/09/2026 for MirrorNotificationListener.refillAllNotifsHistory/
         * SofascoreNotificationListenerService's own equivalent refill, both of which used to call
         * [pushToAllNotifications] once PER eligible notification still active after a dismissal.
         * Each of those calls did its own WidgetAllNotificationsStore.push() (a full
         * read-merge-save) AND its own pushToAllWidgets(buildViews()) — i.e. a full widget redraw —
         * so refilling a history of, say, 8 notifications after dismissing one made the widget
         * visibly flash through 8 intermediate states, oldest notification first (since the refill
         * loops sorted-ascending so the true most-recent one wins the merge last), before settling
         * on the real top-5 (Yann, 20/09/2026: "quand je supprime une notif [...] je vois apparaitre
         * toutes les notifs dans un ordre chronologique croissant avant de voir la dernière reçue
         * [...] ça pourrait de suite montrer la cinquième"). This does the same merge for every
         * [entries] in ONE WidgetAllNotificationsStore.pushAll() call (one read, one save) and
         * pushes exactly ONE widget rebuild at the end, so the widget jumps straight to the final
         * state instead of animating through every step of the refill. [pushToAllNotifications]
         * above is now just this with a single-element list, so both call sites share the exact
         * same live-intent/action bookkeeping below instead of keeping two versions of it in sync.
         */
        fun pushToAllNotificationsBatch(context: Context, entries: List<AllNotifEntryPush>) {
            if (entries.isEmpty()) return

            val kept = WidgetAllNotificationsStore.pushAll(
                context,
                entries.map { entry ->
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
                        image = entry.image,
                        imageLoader = entry.imageLoader
                    )
                }
            )

            // Same rule as the old single-entry version, just applied to every entry in the batch:
            // a PUSHED entry always contributes its OWN live PendingIntent/actions (even if that
            // means nothing, when it has none — never falling back to a stale value from a
            // previous push under the same id), and anything else still KEPT from before this
            // batch but NOT itself part of it keeps whatever live PendingIntent/actions it already
            // had in memory.
            val pushedIds = entries.map { allNotifEntryId(it.key, it.postTimeMillis) }.toSet()
            val keptIds = kept.map { allNotifEntryId(it.key, it.postTimeMillis) }.toSet()
            liveAllNotifIntents = buildMap {
                entries.forEach { entry ->
                    entry.contentIntent?.let { put(allNotifEntryId(entry.key, entry.postTimeMillis), it) }
                }
                keptIds.forEach { id ->
                    if (id !in pushedIds) liveAllNotifIntents[id]?.let { put(id, it) }
                }
            }
            liveAllNotifActions = buildMap {
                entries.forEach { entry ->
                    if (entry.actions.isNotEmpty()) put(allNotifEntryId(entry.key, entry.postTimeMillis), entry.actions)
                }
                keptIds.forEach { id ->
                    if (id !in pushedIds) liveAllNotifActions[id]?.let { put(id, it) }
                }
            }
            liveAllNotifDetailLines = buildMap {
                entries.forEach { entry ->
                    if (entry.detailLines.isNotEmpty()) put(allNotifEntryId(entry.key, entry.postTimeMillis), entry.detailLines)
                }
                keptIds.forEach { id ->
                    if (id !in pushedIds) liveAllNotifDetailLines[id]?.let { put(id, it) }
                }
            }

            requestUpdate(context)
        }

        /**
         * Fires the [actionIndex]-th action button of the entry identified by ([key],
         * [postTimeMillis]) — called both by the widget's own "silent" action buttons (see
         * [WidgetAction.dismissesOnFire]/[actionFirePendingIntent], routed through
         * MirrorNotificationListener/SofascoreNotificationListenerService's ACTION_FIRE_WIDGET_ACTION
         * so the caller can act on the result below) and by WearActionRelayService when the watch's
         * notification detail screen's own action chip is tapped (NEW 21/09/2026). Reuses the exact
         * same live, in-memory [liveAllNotifActions] map the widget's own action buttons already
         * read (see that field's doc) — same PendingIntent, so firing it here does exactly what
         * pressing it on the widget/phone notification itself would.
         *
         * [FireActionResult.FIRED_DISMISS] (NEW 21/09/2026) tells the caller to ALSO cancel the
         * source notification right after, the same way the dismiss button already does — see
         * [WidgetAction.dismissesOnFire]'s doc for why a "silent" action (mark as read/delete/
         * archive/mute) needs that extra step to visibly reflect anything, unlike an action that
         * opens its own UI. [FireActionResult.NOT_FOUND] (silently) if this process was restarted
         * since the entry was last pushed (the PendingIntent is gone, same already-accepted
         * limitation as the widget's own action buttons — see README's Limitations), the index is
         * out of range, or sending it threw.
         */
        fun fireAction(key: String, postTimeMillis: Long, actionIndex: Int): FireActionResult {
            val action = liveAllNotifActions[allNotifEntryId(key, postTimeMillis)]?.getOrNull(actionIndex)
                ?: return FireActionResult.NOT_FOUND
            return try {
                action.pendingIntent.send()
                if (action.dismissesOnFire) FireActionResult.FIRED_DISMISS else FireActionResult.FIRED
            } catch (_: Throwable) {
                FireActionResult.NOT_FOUND
            }
        }

        /**
         * Dismisses the entry identified by ([kindName], [key], [postTimeMillis]) — called by
         * WearActionRelayService when the watch's notification detail screen's own delete button
         * is tapped (NEW 21/09/2026). Routes to the SAME service each kind's own widget dismiss
         * button already targets (see [dismissPendingIntent]/[sofascoreDismissPendingIntent] just
         * below) — cancelling the real source notification by key, not a live in-memory
         * PendingIntent, so unlike [fireAction] this keeps working across a process restart
         * (exactly like the widget's own dismiss button already does).
         */
        fun dismissEntry(context: Context, kindName: String, key: String, postTimeMillis: Long) {
            val isMatch = kindName == WidgetAllNotificationsStore.Kind.SOFASCORE_MATCH.name
            val intent = if (isMatch) {
                Intent(context, SofascoreNotificationListenerService::class.java).apply {
                    action = SofascoreNotificationListenerService.ACTION_DISMISS_WIDGET
                    putExtra(SofascoreNotificationListenerService.EXTRA_DISMISS_KEY, key)
                    putExtra(SofascoreNotificationListenerService.EXTRA_DISMISS_POST_TIME, postTimeMillis)
                }
            } else {
                Intent(context, MirrorNotificationListener::class.java).apply {
                    action = MirrorNotificationListener.ACTION_DISMISS_WIDGET
                    putExtra(MirrorNotificationListener.EXTRA_DISMISS_KEY, key)
                    putExtra(MirrorNotificationListener.EXTRA_DISMISS_POST_TIME, postTimeMillis)
                }
            }
            context.startService(intent)
        }

        // NEW 22/09/2026 — canal dédié à la notification que [openEntry] poste (PAS ongoing —
        // distinct du canal "mirror" de MirrorNotificationListener, IMPORTANCE_LOW).
        //
        // UPDATED 22/09/2026, troisième passe : renommé "open_on_phone" -> "open_on_phone_v2" en
        // passant son importance à HIGH (voir openEntry's et ensureOpenOnPhoneChannel's doc) —
        // l'importance d'un canal Android est figée à sa création et n'est PLUS modifiable par le
        // code une fois le canal créé une première fois (seul l'utilisateur peut la changer, dans
        // les réglages système) ; comme ce canal existait déjà (IMPORTANCE_DEFAULT) sur tout
        // appareil ayant déjà testé la passe précédente, ré-appeler createNotificationChannel avec
        // une importance différente sur le MÊME id n'aurait silencieusement rien changé. Un
        // nouvel id force la création d'un canal réellement neuf avec la bonne importance.
        private const val OPEN_ON_PHONE_CHANNEL_ID = "open_on_phone_v2"
        private const val OPEN_ON_PHONE_NOTIFICATION_ID = 916_001

        /**
         * NEW 22/09/2026 — ouvre côté TÉLÉPHONE l'entrée actuellement affichée sur l'écran de
         * détail montre (Yann : "je veux aussi qu'il y ait afficher sur téléphone pour ouvrir la
         * notification sur le téléphone"), appelée par WearActionRelayService quand la pilule
         * "Aff. sur tél." (wear/NotificationDetailActivity.kt) est tapée.
         *
         * FIXED 22/09/2026 (Yann : "le bouton afficher sur téléphone ne fait rien") — la première
         * version appelait `.send()` directement sur le PendingIntent (comme [fireAction] le fait
         * déjà pour un bouton d'action) : ça ne marche PAS de façon fiable ici, parce que ce
         * service tourne en arrière-plan pur (déclenché par un message Bluetooth de la montre,
         * sans fenêtre visible) — Android bloque le démarrage direct d'une ACTIVITY depuis un tel
         * contexte ("Background Activity Launch restrictions", en vigueur depuis Android 10) sauf
         * pour un PendingIntent qui bénéficie ENCORE de l'autorisation temporaire accordée par le
         * système au moment où sa notification source a été postée — une fenêtre courte, souvent
         * déjà expirée. Le repli [launchAppPendingIntent] (créé par CETTE app, jamais rattaché à
         * aucune notification) n'a lui JAMAIS cette autorisation — exactement le cas d'un match
         * Sofascore, qui n'a souvent pas de contentIntent propre, d'où "ne fait rien".
         *
         * La seule chose qu'une app en arrière-plan peut TOUJOURS faire de façon fiable, c'est
         * POSTER une notification. Donc au lieu d'essayer d'ouvrir quoi que ce soit directement,
         * ceci poste une notification normale (pas ongoing, auto-annulée au tap) reprenant
         * titre/texte/image déjà calculés par [resolveAllNotifEntryContent] pour cette même
         * entrée (widget "Dernière notif" / synchro montre), avec le même openIntent (résolu ou de
         * repli) comme cible de tap — un tap RÉEL de l'utilisateur sur cette notification, lui,
         * profite bien de l'autorisation système normale, exactement comme l'app le fait déjà pour
         * sa propre notification miroir (voir MirrorNotificationListener.mirror). Retourne false
         * (silencieusement — même raisonnement "best effort" que [PhoneRelay]) si l'entrée est
         * sortie de l'historique plafonné, ou si aucune cible d'ouverture n'a pu être résolue.
         *
         * NEW 22/09/2026 (Yann : "je veux que ça ouvre l'article/le message sur le téléphone sans
         * la supprimer [l'originale, déjà le cas] ; là ça me crée une notification identique") —
         * la notification relais restait quelque chose qu'il fallait taper manuellement, perçue
         * comme un doublon de la notification source. Elle est maintenant aussi posée avec
         * [NotificationCompat.Builder.setFullScreenIntent] (même [openIntent], voir
         * ensureOpenOnPhoneChannel's doc pour le canal HIGH que ça nécessite) : sur Android 14+
         * (notre cible), ceci ouvre automatiquement la cible — sans tap — SI Yann a accordé
         * l'accès "Notifications plein écran" à l'app (bouton dédié dans PermissionsActivity ; pas
         * auto-accordé sur 14+ pour une app hors téléphonie/alarme). Tant que ce n'est pas
         * accordé, ou si les conditions d'auto-lancement plein écran ne sont pas réunies (p. ex.
         * téléphone déjà déverrouillé et à l'écran), le système se rabat de lui-même sur le
         * heads-up normal — [content.openIntent] reste donc aussi posé via setContentIntent
         * juste en dessous, pour que le tap manuel continue de marcher dans ce cas.
         *
         * NEW 22/09/2026, deuxième passe (Yann, téléphone verrouillé : "ça marche à peu près [...]
         * mais ça affiche la notification créée par toi. On pourrait s'en passer ?") — quand le
         * téléphone est verrouillé (voir [isDeviceLocked]), le plein écran fait déjà tout le
         * travail : la notification qui le porte n'a plus besoin de rester visible ensuite, donc
         * [scheduleAutoCancelOpenOnPhone] la fait disparaître d'elle-même peu après. Quand il ne
         * l'est PAS, elle reste (comme avant) : c'est le seul moyen d'ouvrir la cible dans ce cas
         * (voir la doc juste au-dessus), l'auto-annuler la ferait disparaître avant que Yann ait pu
         * taper dessus.
         */
        // UPDATED 22/09/2026, troisième passe (Yann : "je n'arrive toujours pas à faire marcher
        // afficher sur téléphone. Rien ne se passe quand je le fais.") : la version précédente
        // (poster une notification normale plutôt que .send() le PendingIntent directement, seule
        // façon fiable de démarrer une Activity depuis ce contexte pur arrière-plan — voir
        // toujours la doc juste en dessous pour ce raisonnement, inchangé) était logiquement
        // correcte, mais deux choses pouvaient la faire passer inaperçue :
        // 1) [entry] n'est retrouvée que si elle est encore dans l'historique plafonné à 5 de
        //    WidgetAllNotificationsStore — si l'écran de détail montre affichait une notification
        //    déjà périmée/évincée (le bug corrigé ci-dessus, voir NotificationInfoStore.
        //    updateIfNotOlder), "Aff. sur tél." visait alors une entrée qui n'existe déjà plus
        //    côté téléphone : `return false` silencieux, rien à voir avec ce bouton lui-même.
        // 2) IMPORTANCE_DEFAULT ne déclenche ni pop-up ("heads-up") ni son : la notification était
        //    bel et bien postée, mais atterrissait silencieusement dans le volet, indiscernable
        //    de "rien ne s'est passé" si l'écran du téléphone était éteint ou ailleurs.
        // Le point 1) devrait déjà être largement résolu par le correctif de fraîcheur ci-dessus.
        // Pour le point 2), IMPORTANCE_HIGH (+ vibration) donne une confirmation immédiate et
        // indiscutable que l'appui a bien été relayé, même écran éteint.
        fun openEntry(context: Context, key: String, postTimeMillis: Long): Boolean {
            val entry = WidgetAllNotificationsStore.get(context)
                .firstOrNull { it.key == key && it.postTimeMillis == postTimeMillis } ?: return false
            val content = resolveAllNotifEntryContent(context, entry)
            val openIntent = content.openIntent ?: return false
            return postOpenOnPhone(context, content.title, content.text, content.image, openIntent)
        }

        /**
         * The relay-notification + full-screen-intent mechanism of [openEntry] (see its doc),
         * extracted 24/09/2026 so the watch "Messages" list can reuse it
         * (MirrorNotificationListener.openMessageOnPhone).
         */
        fun postOpenOnPhone(context: Context, title: String, text: String, image: Bitmap?, openIntent: PendingIntent): Boolean {
            // UPDATED 24/09/2026 — watch associated (WatchCompanionLink, kept across updates unlike
            // the full-screen access): open directly. Unlocked, that's all; locked, the relay
            // notification below is still posted (full-screen if granted, auto-cancelled).
            val locked = isDeviceLocked(context)
            val openedDirectly = WatchCompanionLink.openDirect(context, openIntent)
            if (openedDirectly && !locked) return true
            return try {
                ensureOpenOnPhoneChannel(context)
                val builder = NotificationCompat.Builder(context, OPEN_ON_PHONE_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_mirror)
                    .setContentTitle(title.ifBlank { context.getString(R.string.app_name) })
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setAutoCancel(true)
                    .setContentIntent(openIntent)
                    // NEW 22/09/2026 — auto-ouvre sans tap si la permission plein écran est
                    // accordée (voir doc juste au-dessus) ; se dégrade tout seul en heads-up
                    // normal sinon, d'où setContentIntent conservé ci-dessus comme repli.
                    .setFullScreenIntent(openIntent, /* highPriority = */ true)
                image?.let { builder.setLargeIcon(it) }
                NotificationManagerCompat.from(context).notify(OPEN_ON_PHONE_NOTIFICATION_ID, builder.build())
                if (locked) scheduleAutoCancelOpenOnPhone(context)
                true
            } catch (_: Throwable) {
                openedDirectly
            }
        }

        /** Crée (idempotent) le canal de [openEntry] — même schéma que MirrorNotificationListener.createChannel. */
        private fun ensureOpenOnPhoneChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val channel = NotificationChannel(
                OPEN_ON_PHONE_CHANNEL_ID,
                context.getString(R.string.open_on_phone_channel_name),
                // UPDATED 22/09/2026 : IMPORTANCE_HIGH (au lieu de DEFAULT) — voir openEntry's doc
                // juste au-dessus, point 2). IMPORTANCE_DEFAULT ne déclenche ni pop-up ni son,
                // donc rien ne distinguait visuellement "ça a marché" de "rien ne s'est passé".
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.open_on_phone_channel_description)
                enableVibration(true)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        /** True while the lock screen is showing — see [openEntry]'s "deuxième passe" doc. */
        private fun isDeviceLocked(context: Context): Boolean =
            context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

        /** Schedules [ACTION_AUTO_CANCEL_OPEN_ON_PHONE] — see its own doc and [openEntry]'s "deuxième passe". */
        private fun scheduleAutoCancelOpenOnPhone(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val intent = Intent(context, NowBarWidgetProvider::class.java).apply {
                action = ACTION_AUTO_CANCEL_OPEN_ON_PHONE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                AUTO_CANCEL_OPEN_ON_PHONE_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // AUDIT 23/09/2026: non-wakeup — never worth waking a sleeping phone just to remove a
            // notification; delivered as soon as the device is awake anyway (and it is, right
            // after a full-screen launch).
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + AUTO_CANCEL_OPEN_ON_PHONE_DELAY_MILLIS,
                pendingIntent
            )
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
         * key-prefix match. The true-LATEST dismiss button now always supplies its real
         * postTimeMillis too (MERGED 20/09/2026: it reads the same WidgetAllNotificationsStore.Data
         * as an ALL_NOTIFS peek, which always has one — see resolveAllNotifEntryContent), so -1L is
         * kept only as a defensive default for any future caller that genuinely doesn't have one.
         * No-op, safe to call unconditionally, if no peek is
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
                closePeekAndCancelAlarm(context)
                requestUpdate(context)
            }
        }

        /**
         * Closes the peek (if any) and cancels its pending [ACTION_AUTO_CLOSE_PEEK] alarm, if one
         * is scheduled — the one place every "close the peek" path in this file should go through
         * (REWORKED 20/09/2026, see the class doc), so a leftover alarm from a peek that already
         * closed some other way (dismissal, view toggle, self-heal) never fires later against
         * whatever happens to be peeking by then. Safe to call unconditionally, including when
         * nothing is currently peeking or scheduled.
         */
        internal fun closePeekAndCancelAlarm(context: Context) {
            WidgetPeekPrefs.close(context)
            cancelAutoClosePeekAlarm(context)
        }

        /** Schedules [ACTION_AUTO_CLOSE_PEEK] [AUTO_CLOSE_PEEK_DELAY_MILLIS] from now for the entry just peeked — see the class doc's "REWORKED 20/09/2026" section. Replaces any previously scheduled auto-close (same requestCode/action/component — android.app.AlarmManager.set() always supersedes an equivalent pending alarm), so re-peeking the same or a different tile always gets a fresh 15s window. An inexact alarm is deliberate: this is a UI nicety, not something that needs to survive Doze/App Standby down to the second, and inexact alarms need no special permission. */
        private fun scheduleAutoClosePeek(context: Context, source: WidgetPeekPrefs.Source, entryId: String) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            // AUDIT 23/09/2026: non-wakeup — a peek is only visible while the screen is on, so
            // there's no point waking the device to close it; it closes at the next wake-up.
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + AUTO_CLOSE_PEEK_DELAY_MILLIS,
                autoClosePeekPendingIntent(context, source, entryId)
            )
        }

        /** Cancels a pending [ACTION_AUTO_CLOSE_PEEK] alarm, if any — see [closePeekAndCancelAlarm]. FLAG_NO_CREATE makes getBroadcast() return null instead of creating a new PendingIntent when none is currently scheduled, so this is a safe no-op in that case. */
        private fun cancelAutoClosePeekAlarm(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val intent = Intent(context, NowBarWidgetProvider::class.java).apply { action = ACTION_AUTO_CLOSE_PEEK }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                AUTO_CLOSE_PEEK_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            ) ?: return
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }

        private fun autoClosePeekPendingIntent(context: Context, source: WidgetPeekPrefs.Source, entryId: String): PendingIntent {
            val intent = Intent(context, NowBarWidgetProvider::class.java).apply {
                action = ACTION_AUTO_CLOSE_PEEK
                putExtra(EXTRA_PEEK_SOURCE, source.name)
                putExtra(EXTRA_PEEK_ENTRY_ID, entryId)
            }
            return PendingIntent.getBroadcast(
                context,
                AUTO_CLOSE_PEEK_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * Rebuilds and pushes from whatever the stores currently hold, with no live
         * PendingIntent available (used when just clearing the widget, refreshing after a
         * staleness check, or when the system calls onUpdate() independently of any specific
         * notification event). In the latest-notif view the tap falls back to opening the
         * source app, and the actions row — which has no such fallback — simply stays hidden;
         * in the other two views/the peek state, a tile/the open target falls back the same way
         * (see resolvePeek / applySofascoreMatches / applyAllNotifs).
         *
         * COALESCED (AUDIT 23/09/2026 — battery): every listener-driven change goes through here
         * and is applied [REFRESH_COALESCE_MILLIS] later, in ONE refresh. A single notification
         * event used to rebuild all three widgets 2-3 times (store push, refill after a removal,
         * closePeekIfShowing, the Sofascore listener's own pushes for the same event…), each
         * rebuild re-decoding every tile image and re-sending to the watch. The stores are
         * written synchronously as before, so the one refresh that runs always renders the final
         * state. Direct user taps on the widget (onReceive) still refresh immediately
         * ([refreshAllNow]).
         */
        fun requestUpdate(context: Context) {
            refreshContext = context.applicationContext
            // Throttle rather than debounce: a refresh already scheduled absorbs this request
            // (it reads the stores when it runs), so a continuous burst can't postpone the redraw
            // indefinitely — at most REFRESH_COALESCE_MILLIS of latency. Safe from any thread
            // (ApiOverrideFollowService's polling ends up here from a background thread).
            if (refreshPending.compareAndSet(false, true)) {
                mainHandler.postDelayed(coalescedRefresh, REFRESH_COALESCE_MILLIS)
            }
        }

        /**
         * The one choke point every widget redraw goes through — the 4x1 itself plus the compact
         * 4x2 (NowBarWidgetProviderCompact) and the double-icon-row 4x2 (NowBarWidgetProviderTriple),
         * each a no-op when that widget isn't currently placed (AUDIT 23/09/2026: the 4x1's own
         * views used to be built even when no 4x1 was placed). Also keeps the watch's
         * "Notification" complication aligned ([syncWatchToLatest]) — moved here from
         * buildViewsUnsafe so it still runs when only the 4x2 widgets (or none) are placed.
         */
        internal fun refreshAllNow(context: Context) {
            mainHandler.removeCallbacks(coalescedRefresh)
            refreshPending.set(false)
            try {
                syncWatchToLatest(context)
            } catch (_: Throwable) {
                // The watch sync is a nice-to-have on top of the widgets — never let it take the
                // caller (a listener service or this receiver) down.
            }
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NowBarWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                val views = buildViews(context)
                ids.forEach { id -> manager.updateAppWidget(id, views) }
            }
            NowBarWidgetProviderCompact.refreshAll(context)
            NowBarWidgetProviderTriple.refreshAll(context)
        }

        private fun buildViews(context: Context): RemoteViews {
            return try {
                buildViewsUnsafe(context)
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

        private fun buildViewsUnsafe(context: Context): RemoteViews {
            // syncWatchToLatest used to be called here — moved to [refreshAllNow] (AUDIT
            // 23/09/2026) so it runs once per refresh whichever widgets are placed.
            val views = RemoteViews(context.packageName, R.layout.widget_now_bar)

            // "Peek" takes priority over the three normal views when active — see the class doc
            // and WidgetPeekPrefs. Self-heals if the peeked entry has vanished (aged out of its
            // store's top-5, or removed elsewhere) instead of ever getting stuck.
            val peek = WidgetPeekPrefs.current(context)
            val resolvedPeek = peek?.let { resolvePeek(context, it) }
            if (peek != null && resolvedPeek == null) {
                closePeekAndCancelAlarm(context)
            }

            if (resolvedPeek != null) {
                views.setViewVisibility(R.id.widget_sofascore_content, View.GONE)
                views.setViewVisibility(R.id.widget_all_notifs_content, View.GONE)
                views.setViewVisibility(R.id.widget_latest_content, View.VISIBLE)

                renderLatestFormat(
                    context,
                    views,
                    title = resolvedPeek.title,
                    text = resolvedPeek.text,
                    image = resolvedPeek.image,
                    dismissIntent = resolvedPeek.dismissIntent,
                    // Direct tap-to-open again (REWORKED 20/09/2026, see the class doc) — no
                    // longer wrapped through an Activity that closes the peek before relaying the
                    // tap: the peek now closes on its own timer (scheduleAutoClosePeek) instead of
                    // as a side effect of this tap, so this can just be the real target, exactly
                    // like Dernière notif's own tap-to-open above.
                    openIntent = resolvedPeek.openIntent,
                    actions = resolvedPeek.actions,
                    entryKey = resolvedPeek.entryKey,
                    entryPostTimeMillis = resolvedPeek.entryPostTimeMillis,
                    isMatch = resolvedPeek.isMatch
                )
                applyPeekLeftColumn(context, views, resolvedPeek.iconPackageName)
                views.setViewVisibility(R.id.widget_view_toggle_right, View.GONE)
                return views
            }

            val sofascoreMatches = SofascoreWidgetStore.get(context).sortedForWidget(
                nowMillis = System.currentTimeMillis(),
                statusOf = { it.status },
                postTimeOf = { it.postTimeMillis }
            )
            val allNotifs = WidgetAllNotificationsStore.get(context)
            val currentView = WidgetViewModePrefs.currentView(context)

            views.setViewVisibility(R.id.widget_latest_content, if (currentView == WidgetViewModePrefs.WidgetView.LATEST) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_sofascore_content, if (currentView == WidgetViewModePrefs.WidgetView.SPORT) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_all_notifs_content, if (currentView == WidgetViewModePrefs.WidgetView.ALL_NOTIFS) View.VISIBLE else View.GONE)

            when (currentView) {
                WidgetViewModePrefs.WidgetView.LATEST -> applyLatestContent(context, views)
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

        /**
         * MERGED 20/09/2026 (Yann: "Fusionne toutes les listes que tu peux pour optimiser [...]
         * Sofascore peut apparaître en dernière notif. Toutes les notifs des applis choisies
         * peuvent y apparaître") — "Dernière notif" is no longer its own separately-written store
         * (the old WidgetNotificationStore/pushLive()): it's simply whichever entry is currently
         * MOST RECENT in the shared "Toutes notifs" history, of EITHER kind — a Sofascore match is
         * exactly as eligible as a generic mirrored notification, per Yann's remark above. Reuses
         * [resolveAllNotifEntryContent], the exact same resolver an ALL_NOTIFS "peek" already used
         * to show one of these entries full-format — "Dernière notif" is now just that same
         * rendering applied to entry #1 instead of a tapped one.
         */
        internal fun applyLatestContent(context: Context, views: RemoteViews) {
            val entry = WidgetAllNotificationsStore.get(context).firstOrNull()

            if (entry == null) {
                views.setImageViewResource(R.id.widget_app_icon, R.drawable.ic_notification_bell)
                renderLatestFormat(
                    context,
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

            val content = resolveAllNotifEntryContent(context, entry)
            applyLatestIcon(context, views, content.iconPackageName)
            // Bound to widget_latest_content specifically (not the whole widget_root any more —
            // widget_root also contains the left/right columns, shared with the other views,
            // which must NOT open this notification when tapped) inside renderLatestFormat.
            renderLatestFormat(
                context,
                views,
                title = content.title,
                text = content.text,
                image = content.image,
                dismissIntent = content.dismissIntent,
                openIntent = content.openIntent,
                actions = content.actions,
                entryKey = content.entryKey,
                entryPostTimeMillis = content.entryPostTimeMillis,
                isMatch = content.isMatch
            )
        }

        /**
         * widget_app_icon for the TRUE LATEST view — "Idem quand il n'y a pas de notifs en vue
         * texte" (18/09/2026): the bell (applied directly by the caller when there's no entry at
         * all) replaces this app's own icon specifically for the "nothing to show" case; a real
         * entry whose icon lookup itself fails (or names no package) keeps the pre-existing
         * this-app-icon fallback instead, unrelated to that request. [iconPackageName] is the
         * resolved entry's own source app (the mirrored app, or Sofascore for a match — see
         * [resolveAllNotifEntryContent]).
         */
        private fun applyLatestIcon(context: Context, views: RemoteViews, iconPackageName: String?) {
            val appIcon = iconPackageName?.let { BitmapUtils.AppIcons.getCircular(context, it) }
            if (appIcon != null) {
                views.setImageViewBitmap(R.id.widget_app_icon, appIcon)
            } else {
                views.setImageViewResource(R.id.widget_app_icon, R.drawable.ic_stat_mirror)
            }
        }

        /**
         * CHANGED 18/09/2026 (Yann: "Afficher le même signe en plus grand sur la gauche en vue
         * sport") — used to show Sofascore's own app icon; now shows the stylized football-pitch
         * glyph instead (same one used on the right toggle for ALL_NOTIFS, see
         * applyRightToggleIcon/ic_football_pitch.xml), at the left column's full 32dp size.
         *
         * [targetViewId] (NEW 23/09/2026, `internal`) defaults to the main widget's own
         * widget_app_icon — the compact 4x2 widget's row 1 has its own, differently-id'd left icon
         * (widget_app_icon is already taken by ITS row 2), so NowBarWidgetProviderCompact passes
         * that id instead, reusing this exact same icon-picking logic rather than duplicating it.
         */
        internal fun applySofascoreIcon(context: Context, views: RemoteViews, targetViewId: Int = R.id.widget_app_icon) {
            views.setImageViewResource(targetViewId, R.drawable.ic_football_pitch)
        }

        /**
         * No single source app for a merged "Toutes notifs" feed — replaced 18/09/2026 (Yann:
         * "Remplacer l'icône sur la gauche en vue toutes notifs par une cloche représentant
         * notification") with a generic notification-bell glyph instead of this app's own icon.
         *
         * [targetViewId] — see [applySofascoreIcon]'s doc.
         */
        internal fun applyAllNotifsIcon(context: Context, views: RemoteViews, targetViewId: Int = R.id.widget_app_icon) {
            views.setImageViewResource(targetViewId, R.drawable.ic_notification_bell)
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
         *
         * [peekRequestCodeBase] (NEW 23/09/2026, `internal`) defaults to this widget's own
         * [PEEK_REQUEST_CODE_SPORT_BASE]. NowBarWidgetProviderCompact — whose row 1 "il faut que ça
         * soit exactement la même chose que la vue icône du widget 4x1" reuses this exact rendering
         * — passes its OWN distinct base instead, so its tiles never share a PendingIntent identity
         * with this widget's own tiles at the same slot index (see that class' own request-code
         * constants' doc).
         */
        internal fun applySofascoreMatches(
            context: Context,
            views: RemoteViews,
            matches: List<SofascoreWidgetStore.Data>,
            peekRequestCodeBase: Int = PEEK_REQUEST_CODE_SPORT_BASE
        ) {
            views.setViewVisibility(R.id.widget_sofascore_empty, if (matches.isEmpty()) View.VISIBLE else View.GONE)

            for (i in SOFASCORE_SLOT_IDS.indices) {
                val slotId = SOFASCORE_SLOT_IDS[i]
                val match = matches.getOrNull(i)
                if (match == null) {
                    views.setViewVisibility(slotId, View.GONE)
                    continue
                }
                views.setViewVisibility(slotId, View.VISIBLE)

                val imageBitmap = BitmapUtils.ImageFiles.decode(match.imageFile)
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
                    SofascoreMatchPresentation.periodLabel(match.status)
                )

                views.setOnClickPendingIntent(
                    slotId,
                    openPeekPendingIntent(context, WidgetPeekPrefs.Source.SPORT, match.key, peekRequestCodeBase + i)
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
         *
         * [peekRequestCodeBase] — see [applySofascoreMatches]'s doc, same reasoning, defaults to
         * this widget's own [PEEK_REQUEST_CODE_ALL_NOTIFS_BASE].
         */
        internal fun applyAllNotifs(
            context: Context,
            views: RemoteViews,
            entries: List<WidgetAllNotificationsStore.Data>,
            peekRequestCodeBase: Int = PEEK_REQUEST_CODE_ALL_NOTIFS_BASE
        ) {
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
                    openPeekPendingIntent(context, WidgetPeekPrefs.Source.ALL_NOTIFS, entryId, peekRequestCodeBase + i)
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

            val imageBitmap = BitmapUtils.ImageFiles.decode(entry.imageFile)
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
                SofascoreMatchPresentation.periodLabel(entry.status.orEmpty())
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

            val roundImage = BitmapUtils.ImageFiles.decodeCircular(entry.imageFile)
            val appIcon = entry.packageName?.let { BitmapUtils.AppIcons.getCircular(context, it) }
            if (roundImage != null) {
                views.setImageViewBitmap(ids.photo, roundImage)
                if (appIcon != null) {
                    views.setImageViewBitmap(ids.badge, appIcon)
                    views.setViewVisibility(ids.badge, View.VISIBLE)
                } else {
                    views.setViewVisibility(ids.badge, View.GONE)
                }
            } else {
                // No notification image: the app icon fills the main slot directly instead —
                // never both at once, so no badge here either.
                views.setViewVisibility(ids.badge, View.GONE)
                if (appIcon != null) {
                    views.setImageViewBitmap(ids.photo, appIcon)
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
         *
         * `internal` (NEW 23/09/2026) — NowBarWidgetProviderCompact's row 1 reuses this UNCHANGED
         * for its own widget_view_toggle_right (same id, present in its layout too), since [currentView]
         * there is always SPORT or ALL_NOTIFS (never LATEST, see WidgetViewModePrefs.sportOrAllNotifsView),
         * so this is always visible there — matching "comme aujourd'hui" exactly.
         */
        internal fun applyRightToggle(context: Context, views: RemoteViews, currentView: WidgetViewModePrefs.WidgetView) {
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
        internal fun applyPeekLeftColumn(context: Context, views: RemoteViews, iconPackageName: String?) {
            val appIcon = iconPackageName?.let { BitmapUtils.AppIcons.getCircular(context, it) }
            if (appIcon != null) {
                views.setImageViewBitmap(R.id.widget_app_icon, appIcon)
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

        internal fun toggleSportNotifsPendingIntent(context: Context): PendingIntent {
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
         * Opens a "peek" for one tile — see WidgetPeekPrefs' class doc. [requestCode] must be
         * distinct per rendered tile (see PEEK_REQUEST_CODE_SPORT_BASE/PEEK_REQUEST_CODE_ALL_NOTIFS_BASE's
         * doc) so up to 5 simultaneously-visible tiles each keep their own correctly-bound click.
         *
         * REWORKED 20/09/2026 (see the class doc's "REWORKED 20/09/2026" section) — briefly went
         * through an Activity trampoline (OpenPeekTrampolineActivity, now deleted) on the theory
         * that a lock-widget host only skips the manual-swipe keyguard dismissal for a direct
         * Activity tap, not a broadcast — true for the peek's OWN "open the real notification" tap
         * (a genuine second hop to a different target), but this action was never that: it only
         * ever flips WidgetPeekPrefs and pushes a widget update, so wrapping it in an Activity just
         * gave the keyguard something to react to for no reason, and Yann's actual host ended up
         * fully unlocking on tap instead of just showing the peek in place. Back to a plain
         * broadcast, same as ACTION_TOGGLE_VIEW/ACTION_CLOSE_PEEK below.
         */
        internal fun openPeekPendingIntent(context: Context, source: WidgetPeekPrefs.Source, entryId: String, requestCode: Int): PendingIntent {
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
         * Shared renderer for widget_latest_content — used both by the TRUE LATEST view
         * (applyLatestContent) and by a "peek" (resolvePeek) showing a SPORT/ALL_NOTIFS tile
         * full-format instead of its grid. Populates title/text/image/dismiss/actions and the "tap
         * anywhere else to open" click target — exactly the shape Yann asked to unify ("le format
         * qui est uniformisé"). Never touches widget_app_icon, which callers set separately per
         * state (applyLatestIcon / applySofascoreIcon / applyAllNotifsIcon / applyPeekLeftColumn).
         */
        internal fun renderLatestFormat(
            context: Context,
            views: RemoteViews,
            title: String,
            text: String,
            image: Bitmap?,
            dismissIntent: PendingIntent?,
            openIntent: PendingIntent?,
            actions: List<WidgetAction>,
            entryKey: String? = null,
            entryPostTimeMillis: Long = -1L,
            isMatch: Boolean = false
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

            applyActionButtons(context, views, entryKey, entryPostTimeMillis, isMatch, actions)

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
        private fun applyActionButtons(
            context: Context,
            views: RemoteViews,
            entryKey: String?,
            entryPostTimeMillis: Long,
            isMatch: Boolean,
            actions: List<WidgetAction>
        ) {
            val slots = listOf(
                R.id.widget_action_1 to actions.getOrNull(0),
                R.id.widget_action_2 to actions.getOrNull(1),
                R.id.widget_action_3 to actions.getOrNull(2)
            )
            for ((index, slot) in slots.withIndex()) {
                val (viewId, action) = slot
                if (action == null) {
                    views.setViewVisibility(viewId, View.GONE)
                    continue
                }
                views.setViewVisibility(viewId, View.VISIBLE)
                views.setTextViewText(viewId, action.label)
                // "Silent" actions (mark as read/delete/archive/mute — see
                // WidgetAction.dismissesOnFire's doc) are routed through actionFirePendingIntent
                // instead of the raw captured PendingIntent, so firing them can also cancel the
                // source notification afterward. Every other action (e.g. "Répondre"/"Appeler") is
                // bound exactly as before — untouched, since those already work fine as a direct
                // replay.
                val target = if (action.dismissesOnFire && entryKey != null) {
                    actionFirePendingIntent(context, isMatch, entryKey, entryPostTimeMillis, index)
                } else {
                    action.pendingIntent
                }
                views.setOnClickPendingIntent(viewId, target)
            }

            views.setViewVisibility(
                R.id.widget_actions_container,
                if (actions.isEmpty()) View.GONE else View.VISIBLE
            )
        }

        /**
         * RENAMED from ResolvedPeek 20/09/2026 when this stopped being peek-only — see
         * [resolveAllNotifEntryContent]'s doc.
         */
        internal data class ResolvedNotifContent(
            val title: String,
            val text: String,
            val image: Bitmap?,
            val dismissIntent: PendingIntent?,
            val openIntent: PendingIntent?,
            val actions: List<WidgetAction>,
            // NEW 18/09/2026 — the entry's own source-app package, used by applyPeekLeftColumn
            // (peek) / applyLatestIcon (Dernière notif) to show that app's icon on
            // widget_app_icon (Yann: "mettre l'icone de l'appli à gauche"). Sofascore for a SPORT
            // match or a SOFASCORE_MATCH entry, the mirrored notification's own package otherwise.
            val iconPackageName: String?,
            // NEW 21/09/2026, watch detail screen — see AllNotifEntryPush.detailLines' doc. Only
            // ever non-empty for the ALL_NOTIFS resolution path (resolveAllNotifEntryContent);
            // a SPORT peek (resolvePeek) doesn't set it — the "Score en direct"/Sport tab's own
            // tap already opens Sofascore itself, this is only for the "Notification" complication.
            val detailLines: List<String> = emptyList(),
            // NEW 21/09/2026 (fix for "silent" action buttons — see WidgetAction.dismissesOnFire's
            // doc) — this entry's own identity/kind, passed down to renderLatestFormat so a
            // dismissesOnFire action can be routed through actionFirePendingIntent instead of bound
            // directly. null/false when there's nothing to act on (the "no entry yet" placeholder).
            val entryKey: String? = null,
            val entryPostTimeMillis: Long = -1L,
            val isMatch: Boolean = false
        )

        /**
         * Resolves one [WidgetAllNotificationsStore.Data] entry (any [WidgetAllNotificationsStore.Kind])
         * into everything [renderLatestFormat] needs — shared by an ALL_NOTIFS "peek" (below) and,
         * since the 20/09/2026 merge, [applyLatestContent] as well: "Dernière notif" is just this
         * SAME resolution applied to whichever entry is currently most recent, instead of a
         * separately-peeked one. This is what lets a Sofascore match become "Dernière notif" too
         * (Yann: "Sofascore peut apparaître en dernière notif") — it was already exactly how a
         * SOFASCORE_MATCH entry rendered as a peek, long before this merge.
         */
        private fun resolveAllNotifEntryContent(context: Context, entry: WidgetAllNotificationsStore.Data): ResolvedNotifContent {
            val entryId = allNotifEntryId(entry.key, entry.postTimeMillis)
            val isMatch = entry.kind == WidgetAllNotificationsStore.Kind.SOFASCORE_MATCH

            val dismissIntent = if (isMatch) {
                sofascoreDismissPendingIntent(context, entry.key, entry.postTimeMillis)
            } else {
                dismissPendingIntent(context, entry.key, entry.postTimeMillis)
            }
            val fallbackPackage = if (isMatch) SofascoreNotificationListenerService.SOFASCORE_PACKAGE else entry.packageName
            val title = entry.title ?: (if (isMatch) "${entry.homeTeam} - ${entry.awayTeam}" else "")

            return ResolvedNotifContent(
                title = title,
                text = entry.text.orEmpty(),
                image = BitmapUtils.ImageFiles.decode(entry.imageFile),
                dismissIntent = dismissIntent,
                openIntent = liveAllNotifIntents[entryId] ?: fallbackPackage?.let { launchAppPendingIntent(context, it) },
                actions = liveAllNotifActions[entryId] ?: emptyList(),
                iconPackageName = fallbackPackage,
                detailLines = liveAllNotifDetailLines[entryId] ?: emptyList(),
                entryKey = entry.key,
                entryPostTimeMillis = entry.postTimeMillis,
                isMatch = isMatch
            )
        }

        /**
         * Resolves a [WidgetPeekPrefs.Peek] pointer into everything [renderLatestFormat] needs, by
         * looking the entry up in whichever store still holds it — see WidgetPeekPrefs' class doc
         * for why the peek pointer itself carries no content. Returns null if the entry is no
         * longer there (aged out of the capped top-5, or removed elsewhere) — buildViewsUnsafe
         * treats that as "close the peek and fall back to that view's tile grid".
         */
        internal fun resolvePeek(context: Context, peek: WidgetPeekPrefs.Peek): ResolvedNotifContent? {
            return when (peek.source) {
                WidgetPeekPrefs.Source.SPORT -> {
                    val match = SofascoreWidgetStore.get(context).firstOrNull { it.key == peek.entryId } ?: return null
                    ResolvedNotifContent(
                        title = match.title,
                        text = match.text,
                        image = BitmapUtils.ImageFiles.decode(match.imageFile),
                        dismissIntent = sofascoreDismissPendingIntent(context, match.key, match.postTimeMillis),
                        openIntent = liveSofascoreIntents[match.key]
                            ?: launchAppPendingIntent(context, SofascoreNotificationListenerService.SOFASCORE_PACKAGE),
                        actions = liveSofascoreActions[match.key] ?: emptyList(),
                        iconPackageName = SofascoreNotificationListenerService.SOFASCORE_PACKAGE,
                        entryKey = match.key,
                        entryPostTimeMillis = match.postTimeMillis,
                        isMatch = true
                    )
                }
                WidgetPeekPrefs.Source.ALL_NOTIFS -> {
                    val entry = WidgetAllNotificationsStore.get(context)
                        .firstOrNull { allNotifEntryId(it.key, it.postTimeMillis) == peek.entryId } ?: return null
                    resolveAllNotifEntryContent(context, entry)
                }
            }
        }

        /**
         * Dismiss target for a GENERIC notification — the true LATEST view's own dismiss button
         * (via [applyLatestContent]/[resolveAllNotifEntryContent]) or a GENERIC "peek" alike, both
         * always supplying the entry's real [postTimeMillis] since the 20/09/2026 merge (both now
         * come from the same WidgetAllNotificationsStore.Data, which always has one — the
         * true-LATEST call site used to have no postTime of its own to send, back when it read a
         * separate WidgetNotificationStore that didn't track it, hence the -1L/"unknown" default
         * kept here for any other caller that genuinely doesn't have one). Routed through
         * MirrorNotificationListener. [postTimeMillis] (NEW 18/09/2026) lets
         * MirrorNotificationListener.closePeekIfShowing match an ALL_NOTIFS peek precisely — see
         * EXTRA_DISMISS_POST_TIME's doc there.
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
         * Routed target for a "silent" widget action button (see [WidgetAction.dismissesOnFire]) —
         * instead of binding the notification's own captured PendingIntent directly to the button
         * (still what every OTHER action does, see [applyActionButtons]), this goes through the
         * same MirrorNotificationListener/SofascoreNotificationListenerService the dismiss button
         * already targets, so firing it can ALSO cancel the source notification afterward
         * ([fireAction]'s FIRED_DISMISS result, handled in each listener's ACTION_FIRE_WIDGET_ACTION
         * branch) — reproducing, for these actions, the same "the notification disappears" result
         * tapping them for real in the Now Bar already has. Symmetric to [dismissPendingIntent]/
         * [sofascoreDismissPendingIntent] above; kept in its own request-code range
         * ([ACTION_FIRE_REQUEST_CODE_BASE]) since up to 3 of these can be visible at once, unlike
         * the single dismiss button.
         */
        private fun actionFirePendingIntent(
            context: Context,
            isMatch: Boolean,
            key: String,
            postTimeMillis: Long,
            actionIndex: Int
        ): PendingIntent {
            val intent = if (isMatch) {
                Intent(context, SofascoreNotificationListenerService::class.java).apply {
                    action = SofascoreNotificationListenerService.ACTION_FIRE_WIDGET_ACTION
                    putExtra(SofascoreNotificationListenerService.EXTRA_ACTION_KEY, key)
                    putExtra(SofascoreNotificationListenerService.EXTRA_ACTION_POST_TIME, postTimeMillis)
                    putExtra(SofascoreNotificationListenerService.EXTRA_ACTION_INDEX, actionIndex)
                }
            } else {
                Intent(context, MirrorNotificationListener::class.java).apply {
                    action = MirrorNotificationListener.ACTION_FIRE_WIDGET_ACTION
                    putExtra(MirrorNotificationListener.EXTRA_ACTION_KEY, key)
                    putExtra(MirrorNotificationListener.EXTRA_ACTION_POST_TIME, postTimeMillis)
                    putExtra(MirrorNotificationListener.EXTRA_ACTION_INDEX, actionIndex)
                }
            }
            return PendingIntent.getService(
                context,
                ACTION_FIRE_REQUEST_CODE_BASE + actionIndex,
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

        /** Cached (see BitmapUtils.AppIcons) — AUDIT 23/09/2026. */
        internal fun appIconBitmap(context: Context, packageName: String): Bitmap? =
            BitmapUtils.AppIcons.get(context, packageName)

        /** Crops [source] into a circle — see BitmapUtils.circular (single shared implementation, AUDIT 23/09/2026). */
        internal fun circularBitmap(source: Bitmap): Bitmap = BitmapUtils.circular(source)

        /**
         * Shared by NowBarWidgetProviderCompact and NowBarWidgetProviderTriple (AUDIT 23/09/2026 —
         * this block used to be copy-pasted in both): renders their bottom "Dernière notif"/peek
         * row, exactly like the 4x1's own LATEST view / peek.
         */
        internal fun renderPeekOrLatestRow(context: Context, views: RemoteViews) {
            val peek = WidgetPeekPrefs.current(context)
            val resolvedPeek = peek?.let { resolvePeek(context, it) }
            if (peek != null && resolvedPeek == null) {
                closePeekAndCancelAlarm(context)
            }
            if (resolvedPeek != null) {
                renderLatestFormat(
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
                applyPeekLeftColumn(context, views, resolvedPeek.iconPackageName)
            } else {
                applyLatestContent(context, views)
                views.setViewVisibility(R.id.widget_view_toggle, View.GONE)
            }
        }

        /** Shared refreshAll body of the two 4x2 widgets (AUDIT 23/09/2026) — no-op when [providerClass] isn't placed. */
        internal fun refreshProvider(context: Context, providerClass: Class<*>, build: (Context) -> RemoteViews) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, providerClass))
            if (ids.isEmpty()) return
            val views = build(context)
            ids.forEach { id -> manager.updateAppWidget(id, views) }
        }

        // whiteSilhouette/sofascoreToggleIconBitmap (composited the rotating-arrows glyph with a
        // white silhouette of Sofascore's own icon for the ALL_NOTIFS-view right toggle) were
        // REMOVED 18/09/2026 along with ic_widget_switch_to_notifs.xml — see applyRightToggleIcon
        // for why the right toggle no longer needs either the arrows or a runtime-derived bitmap.
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        try {
            syncWatchToLatest(context)
        } catch (_: Throwable) {
        }
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
                closePeekAndCancelAlarm(context)
                WidgetViewModePrefs.toggleLatest(context)
                refreshAllNow(context)
                return
            }
            ACTION_TOGGLE_SPORT_NOTIFS -> {
                // NEW 22/09/2026 — toggleSportOrAllNotifsView (instead of the plain
                // toggleSportAllNotifs) so this same broadcast/PendingIntent, now ALSO bound to the
                // compact widget's own toggle button, still flips the Sport/Toutes-notifs choice
                // even while the main widget itself is sitting on LATEST (where its own RIGHT
                // button is hidden and toggleSportAllNotifs alone would no-op) — see that
                // function's doc.
                closePeekAndCancelAlarm(context)
                WidgetViewModePrefs.toggleSportOrAllNotifsView(context)
                refreshAllNow(context)
                return
            }
            ACTION_CLOSE_PEEK -> {
                closePeekAndCancelAlarm(context)
                refreshAllNow(context)
                return
            }
            // Opens a peek for one tile (a tile's icon tap) — see openPeekPendingIntent's doc for
            // why this is a plain broadcast again (REWORKED 20/09/2026, no Activity trampoline).
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
                    scheduleAutoClosePeek(context, source, entryId)
                    refreshAllNow(context)
                }
                return
            }
            // Fires AUTO_CLOSE_PEEK_DELAY_MILLIS after a peek opens (scheduleAutoClosePeek) — see
            // the class doc's "REWORKED 20/09/2026" section. Only actually closes the peek if it's
            // STILL showing the exact entry that scheduled this alarm: a dismissal, a view toggle,
            // or a re-peek of a different (or the same) tile in the meantime all cancel/replace
            // this alarm already (closePeekAndCancelAlarm / scheduleAutoClosePeek), but this check
            // is kept as a defensive second guard rather than trusting that alone.
            ACTION_AUTO_CLOSE_PEEK -> {
                val sourceName = intent.getStringExtra(EXTRA_PEEK_SOURCE)
                val entryId = intent.getStringExtra(EXTRA_PEEK_ENTRY_ID)
                val current = WidgetPeekPrefs.current(context)
                if (current != null && current.source.name == sourceName && current.entryId == entryId) {
                    WidgetPeekPrefs.close(context)
                    refreshAllNow(context)
                }
                return
            }
            // See ACTION_AUTO_CANCEL_OPEN_ON_PHONE's own doc and openEntry's "deuxième passe" one —
            // no entry/state to check here unlike ACTION_AUTO_CLOSE_PEEK above: a single fixed
            // notification id (OPEN_ON_PHONE_NOTIFICATION_ID) and request code mean a fresh
            // openEntry call always supersedes whatever cancel was previously scheduled (same
            // reasoning as AUTO_CLOSE_PEEK_REQUEST_CODE's own doc), so this can only ever be
            // cancelling the entry that scheduled it.
            ACTION_AUTO_CANCEL_OPEN_ON_PHONE -> {
                NotificationManagerCompat.from(context).cancel(OPEN_ON_PHONE_NOTIFICATION_ID)
                return
            }
            // ACTION_OPEN_PEEK_CONTENT (a same-day attempt at closing the peek on tap-to-open by
            // routing through this app's own onReceive) and PeekOpenTrampolineActivity (the
            // Activity that replaced it, then was itself removed 20/09/2026) are both gone — the
            // peek's own content tap is a plain, direct PendingIntent again, see buildViewsUnsafe's
            // renderLatestFormat call and the class doc's "REWORKED 20/09/2026" section.
        }
        super.onReceive(context, intent)
    }
}
