package com.yann.nowbarmirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import android.widget.Toast
import com.yann.nowbarmirror.settings.AppMirrorPrefs
import com.yann.nowbarmirror.settings.LatestModePrefs
import com.yann.nowbarmirror.settings.MirrorMode
import com.yann.nowbarmirror.settings.ServicePrefs
import com.yann.nowbarmirror.settings.WidgetActionsPrefs
import com.yann.nowbarmirror.sport.SofascoreNotificationListenerService
import com.yann.nowbarmirror.widget.AllNotifEntryPush
import com.yann.nowbarmirror.widget.NowBarWidgetProvider
import com.yann.nowbarmirror.widget.WidgetAction
import com.yann.nowbarmirror.widget.WidgetAllNotificationsStore
import java.util.concurrent.atomic.AtomicBoolean

class MirrorNotificationListener : NotificationListenerService() {

    companion object {
        const val CHANNEL_ID = "mirror"
        const val MIRROR_ID = 9001          // fixed slot shared by every app set to "Dernière notif"
        const val ALL_MODE_ID_BASE = 9100   // "Toutes" mirrors get their own id, allocated from here
        const val EXTRA_ORIGINAL_KEY = "mirror.original.key"
        const val EXTRA_MIRROR = "mirror.is_mirror"
        const val ACTION_DISMISS_WIDGET = "com.yann.nowbarmirror.widget.ACTION_DISMISS"
        const val EXTRA_DISMISS_KEY = "mirror.widget.dismiss_key"
        // NEW 18/09/2026, "peek" feature (see WidgetPeekPrefs' class doc) — carries the exact
        // posting being dismissed, letting closePeekIfShowing below match an ALL_NOTIFS peek's
        // exact (key, postTime) identity precisely instead of falling back to a key-prefix match.
        const val EXTRA_DISMISS_POST_TIME = "mirror.widget.dismiss_post_time"

        // NEW 21/09/2026, fix for "silent" widget action buttons (see
        // widget.WidgetAction.dismissesOnFire's doc) — a mark-as-read/delete/archive/mute action
        // button targets this service directly, same PendingIntent.getService reasoning as
        // ACTION_DISMISS_WIDGET above (keeps working even if this process had been killed), but
        // fires the real action FIRST (see widget.NowBarWidgetProvider.fireAction) and only then
        // cancels the source notification, instead of just cancelling outright.
        const val ACTION_FIRE_WIDGET_ACTION = "com.yann.nowbarmirror.widget.ACTION_FIRE_ACTION"
        const val EXTRA_ACTION_KEY = "mirror.widget.action_key"
        const val EXTRA_ACTION_POST_TIME = "mirror.widget.action_post_time"
        const val EXTRA_ACTION_INDEX = "mirror.widget.action_index"

        // NEW 21/09/2026, watch detail screen — see [messageLinesFor]'s doc.
        private const val MAX_DETAIL_LINES = 10
    }

    private val ready = AtomicBoolean(false)

    // Set by onStartCommand() when a widget dismiss arrives before onListenerConnected() has
    // fired (e.g. this process was just woken up to handle the tap) — processed as soon as the
    // listener is actually connected, instead of silently failing to cancel anything.
    private var pendingDismissKey: String? = null

    // LATEST mode: one shared slot; whichever LATEST-mode app posted last occupies it.
    private var latestOriginalKey: String? = null

    // ALL mode: every distinct original notification key gets its own persistent mirror id.
    private val allModeMirrors = mutableMapOf<String, Int>()
    private var nextAllModeMirrorId = ALL_MODE_ID_BASE

    // LATEST mode: every currently active (not-yet-removed) original from a LATEST-mode app,
    // kept in insertion order so the last entry is always the most recent -> this is the
    // fallback queue used to promote the next-most-recent one when the current mirror or its
    // original gets dismissed, instead of just clearing the shared slot.
    private val latestModeActive = LinkedHashMap<String, StatusBarNotification>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        ready.set(true)
        createChannel()
        // This service is not a foreground service, so Android (One UI in particular) can and
        // does kill its process in the background. When it comes back, onListenerConnected()
        // fires again but latestOriginalKey / allModeMirrors have been reset to empty — any
        // mirror already on screen from before the kill is now "orphaned" in memory, so
        // swiping it never calls cancelOriginal() and the delete sync silently breaks. Fix:
        // rebuild the bookkeeping from what's actually posted, using the original key we
        // already stamp into each mirror's extras, instead of trusting in-memory state that
        // may not have survived.
        rebuildStateFromActiveNotifications()
        pendingDismissKey?.let { key ->
            pendingDismissKey = null
            cancelOriginal(key)
        }
    }

    /**
     * The system keeps this service bound for the notification-listener callbacks, but a bound
     * service can also be started explicitly — that's how the widget's dismiss button reaches
     * it: its PendingIntent (PendingIntent.getService) targets this component directly with
     * ACTION_DISMISS_WIDGET, so the tap keeps working even if this process had been killed and
     * needs the system to spin it back up first. ACTION_FIRE_WIDGET_ACTION (NEW 21/09/2026, same
     * PendingIntent.getService reasoning) is a "silent" action button (mark as read/delete/
     * archive/mute — see widget.WidgetAction.dismissesOnFire's doc): fires the real action first,
     * then — only if [NowBarWidgetProvider.fireAction] says to — runs the exact same
     * cancel-and-sync-the-widget tail as ACTION_DISMISS_WIDGET, via [cancelOriginalAndSyncWidget].
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISMISS_WIDGET) {
            val key = intent.getStringExtra(EXTRA_DISMISS_KEY)
            val postTimeMillis = intent.getLongExtra(EXTRA_DISMISS_POST_TIME, -1L)
            if (key != null) {
                cancelOriginalAndSyncWidget(key, postTimeMillis)
            }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_FIRE_WIDGET_ACTION) {
            val key = intent.getStringExtra(EXTRA_ACTION_KEY)
            val postTimeMillis = intent.getLongExtra(EXTRA_ACTION_POST_TIME, -1L)
            val actionIndex = intent.getIntExtra(EXTRA_ACTION_INDEX, -1)
            if (key != null && actionIndex >= 0 &&
                NowBarWidgetProvider.fireAction(key, postTimeMillis, actionIndex) ==
                    NowBarWidgetProvider.FireActionResult.FIRED_DISMISS
            ) {
                cancelOriginalAndSyncWidget(key, postTimeMillis)
            }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * Shared tail of ACTION_DISMISS_WIDGET and a FIRED_DISMISS result from ACTION_FIRE_WIDGET_ACTION
     * (extracted 21/09/2026 so both share the exact same "make it disappear" behavior instead of
     * two copies drifting apart) — cancels the real original notification (or defers it until
     * onListenerConnected if this process isn't ready yet), then clears the widget/watch right
     * away instead of waiting for the onNotificationRemoved round-trip, so the tap always feels
     * instant even when cancelOriginal() above is deferred or silently no-ops (original already
     * gone). "Dernière notif"/la montre n'ont plus d'état séparé à vider (MERGED 20/09/2026, voir
     * WidgetAllNotificationsStore's class doc) — retirer directement l'entrée de l'historique
     * partagé suffit : requestUpdate() la redérivera correctement, et repeuplera "Dernière notif"
     * à partir de l'entrée suivante si elle existait déjà. No-op si [key] ne correspond à aucune
     * entrée suivie ici (ex. app non mirorée dans "Toutes notifs"). Also closes a "peek" (see
     * WidgetPeekPrefs' class doc) showing this same entry, independently — Yann: "Si je supprime
     * la notification ou la fais disparaitre en marquant lu ou supprimer avec les boutons
     * d'actions, revenir automatiquement aux icônes."
     */
    private fun cancelOriginalAndSyncWidget(key: String, postTimeMillis: Long) {
        if (ready.get()) cancelOriginal(key) else pendingDismissKey = key
        try {
            WidgetAllNotificationsStore.remove(applicationContext, key, postTimeMillis)
            NowBarWidgetProvider.requestUpdate(applicationContext)
        } catch (_: Throwable) {
            // The widget is a nice-to-have on top of the core mirror — never let a failure here
            // take down this service.
        }
        try {
            NowBarWidgetProvider.closePeekIfShowing(applicationContext, key, postTimeMillis)
        } catch (_: Throwable) {
        }
    }

    private fun rebuildStateFromActiveNotifications() {
        latestOriginalKey = null
        allModeMirrors.clear()
        latestModeActive.clear()

        val all = try {
            activeNotifications ?: return
        } catch (_: Throwable) {
            return
        }

        // Rebuild the LATEST-mode fallback queue from every currently posted notification
        // belonging to an app set to "Dernière notif" (same eligibility filters as
        // onNotificationPosted), oldest -> newest, so a swipe right after a process restart
        // still has the right candidate to promote.
        all.asSequence()
            .filter { it.packageName != packageName }
            .filter { !it.isOngoing }
            .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .filter { !isMediaPlaybackNotification(it) }
            .filter { AppMirrorPrefs.getMode(applicationContext, it.packageName) == MirrorMode.LATEST }
            .sortedBy { it.postTime }
            .forEach { latestModeActive[it.key] = it }

        // Drops any "Toutes notifs" entry whose notification is no longer actually posted — a
        // removal missed while this process was dead (Gmail marked read/deleted/opened from
        // within Gmail itself, a calendar event deleted, or just this app being force-stopped)
        // otherwise leaves that tile behind forever, since nothing else ever re-checks it against
        // reality (18/09/2026, Yann, after a phone reboot: "il y avait 2 notifs dont une gmail
        // alors que j'en avais aucune [...] il faut vraiment que l'appli lise l'existant"). See
        // WidgetAllNotificationsStore.pruneAgainstActive's doc.
        try {
            WidgetAllNotificationsStore.pruneAgainstActive(applicationContext, all.toList())
        } catch (_: Throwable) {
            // The widget is a nice-to-have on top of the core mirror — never let a failure
            // here take down this service.
        }

        // "Dernière notif" (widget + complication montre) n'a plus d'état séparé à revérifier ici
        // (MERGED 20/09/2026, voir WidgetAllNotificationsStore's class doc) — elle se dérive
        // maintenant de cette même liste, déjà nettoyée juste au-dessus par pruneAgainstActive.
        val ourMirrors = all.filter { it.packageName == packageName }

        for (mirrorSbn in ourMirrors) {
            val extras = mirrorSbn.notification.extras
            if (!extras.getBoolean(EXTRA_MIRROR, false)) continue
            val originalKey = extras.getString(EXTRA_ORIGINAL_KEY) ?: continue

            val originalStillPosted = all.any { it.key == originalKey }
            if (!originalStillPosted) {
                // The original disappeared while this service's process was dead, so we never
                // got the removal event for it. Don't leave a stale mirror behind.
                cancelMirror(mirrorSbn.id)
                continue
            }

            if (mirrorSbn.id == MIRROR_ID) {
                latestOriginalKey = originalKey
            } else {
                allModeMirrors[originalKey] = mirrorSbn.id
                if (mirrorSbn.id >= nextAllModeMirrorId) {
                    nextAllModeMirrorId = mirrorSbn.id + 1
                }
            }
        }

        // Bootstrap mirrors for notifications that were ALREADY active before this listener
        // (re)connected — first install, access just granted, an app just switched from NONE to
        // ALL/LATEST, or a process restart that raced a new notification. Before this, a mirror
        // was only ever created in onNotificationPosted(), i.e. for notifications posted AFTER
        // the listener was connected — ones already sitting in the shade were silently ignored.
        // Yann compared this to Sport Watch Complication's Sofascore listener, which always reads
        // activeNotifications() on connect and mirrors whatever's already there, and asked for
        // that behavior here too (see README.md, section "Fusion avec Sport Watch Complication").
        // Skipped while the service is paused (ServicePrefs), same as onNotificationPosted().
        if (!ServicePrefs.isEnabled(applicationContext)) return

        // Catch up "Toutes notifs" with EVERY currently-active LATEST-mode notification, not just
        // the single most recent one that gets promoted into the shared system-mirror slot below
        // (17/09/2026, Yann: "le comportement doit bien être de lire toutes les notifs dans le
        // centre de notif et non plus uniquement celles reçues après installation de l'appli ou
        // mise à jour" — this is the LATEST-mode half of that fix for the widget; the ALL-mode
        // half is already covered below, since every ALL-mode bootstrap notification gets its own
        // mirror() call, which already pushes to this same history). Safe to call for all of them,
        // including the one that's about to be promoted: pushAllNotifsHistoryOnly()/mirror() key
        // their push by (key, postTime) — see WidgetAllNotificationsStore's IDENTITY section — so
        // pushing the same sbn twice just updates that one tile in place rather than duplicating
        // it.
        latestModeActive.values.forEach { sbn -> pushAllNotifsHistoryOnly(sbn) }

        all.asSequence()
            .filter { it.packageName != packageName }
            .filter { !it.isOngoing }
            .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .filter { !isMediaPlaybackNotification(it) }
            .filter { AppMirrorPrefs.getMode(applicationContext, it.packageName) == MirrorMode.ALL }
            .filter { it.key !in allModeMirrors }
            .sortedBy { it.postTime }
            .forEach { sbn ->
                val mirrorId = allModeMirrors.getOrPut(sbn.key) { nextAllModeMirrorId++ }
                mirror(sbn, mirrorId)
            }

        // The LATEST-mode fallback queue was already rebuilt above (before the ourMirrors loop);
        // if none of those survivors already occupies the shared slot, show the most recent one
        // now rather than leaving the slot empty until the next new notification arrives.
        if (latestOriginalKey == null) {
            latestModeActive.values.lastOrNull()?.let { sbn ->
                latestOriginalKey = sbn.key
                mirror(sbn, MIRROR_ID)
            }
        }

        // Belt-and-braces top-up: everything eligible that's ALREADY active gets one more pass
        // through "Toutes notifs" here, in case any of it was missed above (e.g. an ALL-mode
        // notification that was already in allModeMirrors from before this reconnect, and so
        // skipped the ALL-mode loop's `it.key !in allModeMirrors` filter, but had never actually
        // reached "Toutes notifs" — same class of bug as the one fixed in onNotificationRemoved,
        // see refillAllNotifsHistory's doc).
        refillAllNotifsHistory(all.toList())
    }

    /**
     * Tops the shared "Toutes notifs" history back up to WidgetAllNotificationsStore.MAX_SLOTS
     * using notifications that are ALREADY active in the shade right now but that history hasn't
     * (or no longer has) captured — every currently active, eligible (mirror mode != NONE, not
     * ongoing, not a group summary, not media playback, not this app's own mirrors — see
     * onNotificationPosted's own filters, duplicated here since this runs from a fresh
     * activeNotifications() snapshot rather than from a single posted event) notification gets
     * pushed again through [buildAllNotifEntryPush], which is also where Sofascore's package is
     * excluded (see that function's doc) — no need to repeat that filter here.
     *
     * Added 18/09/2026 — Yann: "le widget doit afficher toutes les notifications dans le centre de
     * notification [...] aujourd'hui je dois forcer l'arrêt pour que l'application enregistre les 5
     * derniers puis le nombre se réduit alors que j'ai généralement plus de 5 notifs donc ça
     * devrait toujours être plein." Root cause: WidgetAllNotificationsStore.push() only ever runs
     * from onNotificationPosted/the bootstrap above — dismissing one of the 5 tracked entries just
     * shrinks the list, even when OTHER eligible notifications are still sitting in the shade,
     * because nothing ever re-derives the history from what's actually still posted. Calling this
     * with a fresh snapshot after ANY removal (see onNotificationRemoved) — not just at listener
     * reconnect — is what keeps the widget "always a reflection of the notification center" rather
     * than only catching up when the app gets force-stopped. Safe to call repeatedly and with
     * already-tracked notifications included: push() keys each entry by (key, postTimeMillis) (or
     * by key alone for a collapsing kind — see WidgetAllNotificationsStore's IDENTITY section), so
     * re-pushing one already tracked just updates that tile in place, and the merge+cap-to-5 in
     * push() naturally promotes whichever candidates are actually the most recent.
     *
     * FIXED 20/09/2026 (Yann: "quand je supprime une notif, dans l'emplacement de la cinquième, je
     * vois apparaitre toutes les notifs dans un ordre chronologique croissant avant de voir la
     * dernière reçue [...] ça pourrait de suite montrer la cinquième") — this used to push each
     * eligible notification one at a time via [pushAllNotifsHistoryOnly], which round-trips the
     * store AND redraws the whole widget on every single call (see
     * NowBarWidgetProvider.pushToAllNotificationsBatch's doc). With several eligible notifications
     * in the shade, dismissing just one made this loop re-push all the others, oldest-first (the
     * `sortedBy { it.postTime }` below, kept from the old ordering — harmless now, but no longer
     * load-bearing: see [buildAllNotifEntryPush]/pushToAllNotificationsBatch's own merge, which
     * settles on the correct top-5 regardless of push order), and each intermediate push briefly
     * became the widget's on-screen state — visible as "toutes les notifs dans un ordre
     * chronologique croissant" flashing by before the real top-5 appeared, and proportionally
     * heavier the more notifications there were to refill. Now builds every entry first (pure, no
     * store/widget I/O — [buildAllNotifEntryPush]) and hands the whole batch to
     * [NowBarWidgetProvider.pushToAllNotificationsBatch] in one call, which does one store write
     * and one widget redraw for the lot — the widget jumps straight to the final state.
     */
    private fun refillAllNotifsHistory(all: List<StatusBarNotification>) {
        if (!ServicePrefs.isEnabled(applicationContext)) {
            WidgetAllNotificationsStore.saveActiveGenericCount(applicationContext, 0)
            return
        }

        val eligible = all.asSequence()
            .filter { it.packageName != packageName }
            // Sofascore's own package is excluded here too, not just inside buildAllNotifEntryPush
            // below — FIXED 23/09/2026 (Yann: "le compteur des notifications ne doit pas compter
            // celles de sofascore sinon ça fait doublon"): eligible.size now feeds the "+X" overflow
            // count (see below), and without this filter it would have counted every active
            // Sofascore match TWICE — once here (toward the generic badge) and once more toward
            // Sofascore's own true count (SofascoreWidgetStore.getActiveCount) — even though a
            // Sofascore match was never actually going to occupy a generic "Toutes notifs" slot in
            // the first place (buildAllNotifEntryPush already drops it for that exact reason, see
            // its own doc). No behavior change to [entries] below: buildAllNotifEntryPush already
            // returned null for these, so they were never actually part of the pushed batch either.
            .filter { it.packageName != SofascoreNotificationListenerService.SOFASCORE_PACKAGE }
            .filter { !it.isOngoing }
            .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .filter { !isMediaPlaybackNotification(it) }
            .filter { AppMirrorPrefs.getMode(applicationContext, it.packageName) != MirrorMode.NONE }
            .sortedBy { it.postTime }
            .toList()

        // See WidgetAllNotificationsStore.saveActiveGenericCount's doc — eligible.size (before the
        // mapNotNull below, which can drop an entry buildAllNotifEntryPush fails to build for some
        // OTHER reason) is the TRUE count NowBarWidgetProviderTriple's "+X" badge needs,
        // independent of the 6-slot cap [entries] below is about to be capped to.
        WidgetAllNotificationsStore.saveActiveGenericCount(applicationContext, eligible.size)

        val entries = eligible.mapNotNull { sbn -> buildAllNotifEntryPush(sbn) }

        try {
            NowBarWidgetProvider.pushToAllNotificationsBatch(applicationContext, entries)
        } catch (_: Throwable) {
            // Same reasoning as pushAllNotifsHistoryOnly's own try/catch: never let this widget
            // nice-to-have crash the listener.
        }
    }

    /**
     * NEW 23/09/2026 (Yann, after the same fix was applied to Sofascore's own overflow badge: "je
     * voulais le même mécanisme pour les notifications autre que sofascore") — lightweight sibling
     * of [refillAllNotifsHistory] for [onNotificationPosted]: that function's own doc explains why
     * a full history refill only runs at listener reconnect and after a removal, not on every
     * single posted notification (repushing the whole "Toutes notifs" batch on every post would be
     * needless extra work there, on top of the already-correct incremental [mirror] push).
     * NowBarWidgetProviderTriple's "+X" overflow badge still needs to stay just as fresh as
     * Sofascore's own count does (SofascoreNotificationListenerService.refresh() recomputes its
     * full active list on every post/removal too) — so this recomputes ONLY the count (a pure read
     * + one SharedPreferences write via WidgetAllNotificationsStore.saveActiveGenericCount, no
     * store/widget rebuild of its own), which is cheap enough to call on every posted notification
     * as well, unlike a full [refillAllNotifsHistory] run.
     *
     * Same eligibility filter as [refillAllNotifsHistory] — kept separate (rather than having this
     * call that function and just ignore its side effects) since the two run at different moments
     * and for different reasons; duplicating six one-line filters is cheaper to keep in sync here
     * than reusing a function whose own doc is entirely about avoiding a full refill on this exact
     * event.
     *
     * Excludes Sofascore's own package (FIXED 23/09/2026, same "doublon" fix and reasoning as
     * [refillAllNotifsHistory]'s own — see its doc): without it, a Sofascore match would count
     * toward BOTH this badge and Sofascore's own true count, despite never actually being able to
     * occupy a generic "Toutes notifs" slot in the first place.
     */
    private fun refreshActiveGenericCount() {
        try {
            val all = activeNotifications ?: return
            val count = all.asSequence()
                .filter { it.packageName != packageName }
                .filter { it.packageName != SofascoreNotificationListenerService.SOFASCORE_PACKAGE }
                .filter { !it.isOngoing }
                .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
                .filter { !isMediaPlaybackNotification(it) }
                .filter { AppMirrorPrefs.getMode(applicationContext, it.packageName) != MirrorMode.NONE }
                .count()
            WidgetAllNotificationsStore.saveActiveGenericCount(applicationContext, count)
        } catch (_: Throwable) {
            // Same reasoning as refillAllNotifsHistory's own try/catch: never let this widget
            // nice-to-have take the service down.
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!ready.get()) return
        if (!ServicePrefs.isEnabled(applicationContext)) return
        if (sbn.packageName == packageName) return
        if (sbn.isOngoing) return
        // Group-summary notifications (e.g. WhatsApp's "X new messages" bundle) carry no
        // per-conversation photo or actions — skip them so they don't overwrite the real one.
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        // Media-playback controls (e.g. a radio "now playing" notification) shouldn't
        // overwrite a real mirror just because the source app also sends normal alerts
        // (e.g. France Info: articles vs. its radio player). Some apps don't mark this
        // notification ongoing, so isOngoing() above can't be relied on alone.
        if (isMediaPlaybackNotification(sbn)) return

        // See refreshActiveGenericCount's own doc — BEFORE the mirror() call below, which is what
        // actually triggers the widget rebuild for this event: saving the fresh count first means
        // that rebuild picks up THIS notification's effect on the count, instead of the rebuild
        // running against whatever count was last saved before this event arrived.
        refreshActiveGenericCount()

        when (AppMirrorPrefs.getMode(applicationContext, sbn.packageName)) {
            MirrorMode.ALL -> {
                val mirrorId = allModeMirrors.getOrPut(sbn.key) { nextAllModeMirrorId++ }
                mirror(sbn, mirrorId)
            }
            MirrorMode.LATEST -> {
                // Remove-then-reinsert bumps this key to the end of the queue on an update
                // (same original key reposted), so insertion order always tracks recency.
                latestModeActive.remove(sbn.key)
                latestModeActive[sbn.key] = sbn
                latestOriginalKey = sbn.key
                mirror(sbn, MIRROR_ID)
            }
            MirrorMode.NONE -> Unit
        }
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: RankingMap?,
        reason: Int
    ) {
        if (!ready.get()) return

        if (sbn.packageName == packageName) {
            // Only react to a *genuine* dismissal of one of our mirrors (user swipe, or
            // "clear all"). REASON_APP_CANCEL means we cancelled it ourselves. The previous
            // version always cancelled-then-reposted the shared slot to swap its content, which
            // fired this same callback for our own cancel and raced against the state update —
            // by the time the async removal event arrived, the tracked key had often already
            // moved on to the *new* original, so that new original got wrongly cancelled.
            // Filtering by reason removes the race entirely: an original is only ever cancelled
            // when its mirror was actually swiped away by the user.
            val userDismissed = reason == REASON_CANCEL || reason == REASON_CANCEL_ALL
            if (!userDismissed) return

            if (sbn.id == MIRROR_ID) {
                // User swiped the shared slot: cancel the original that was showing in it,
                // drop it from the fallback queue, then promote whatever is now the most
                // recent survivor instead of leaving the slot empty.
                latestOriginalKey?.let { key ->
                    cancelOriginal(key)
                    latestModeActive.remove(key)
                }
                latestOriginalKey = null
                promoteNextLatestMode()
            } else {
                val originalKey = allModeMirrors.entries.firstOrNull { it.value == sbn.id }?.key
                if (originalKey != null) {
                    cancelOriginal(originalKey)
                    allModeMirrors.remove(originalKey)
                }
            }
            return
        }

        // "Toutes notifs" (17/09/2026, Yann: "Si une notification a été supprimée du centre de
        // notifs, elle ne doit plus apparaître dans le widget") — unlike the single "latest" slot
        // above, this history can hold this notification even when it ISN'T the current latest
        // one, so it's checked/dropped unconditionally rather than only when it matches
        // widgetData.key. Matched on (key, postTime) together, not key alone (see
        // WidgetAllNotificationsStore's IDENTITY section) — a "Dernière notif"-mode app reusing
        // its notification id across items can have several OTHER tiles sharing this same key,
        // and only the one that was actually just dismissed (this exact posting) should go; the
        // rest stay until they age out of the capped history. remove() is a no-op if this exact
        // (key, postTime) pair was never in there (app not mirrored, or already pushed out by
        // newer entries).
        try {
            WidgetAllNotificationsStore.remove(applicationContext, sbn.key, sbn.postTime)
            // Removing a tracked entry just shrinks the widget's "Toutes notifs" list unless
            // something re-derives it from what's actually still posted — refill the freed slot
            // (if any) from the notification center right now, instead of only catching up the
            // next time this listener reconnects. See refillAllNotifsHistory's doc, 18/09/2026.
            activeNotifications?.let { all ->
                val allList = all.toList()
                WidgetAllNotificationsStore.pruneAgainstActive(applicationContext, allList)
                refillAllNotifsHistory(allList)
            }
            // "Peek" (NEW 18/09/2026, see WidgetPeekPrefs' class doc): this notification might be
            // the one currently peeked from ALL_NOTIFS, removed by something other than the
            // widget's own dismiss button (the source app's own action, "clear all", the user
            // swiping it from the shade directly) — Yann: "Si je supprime la notification ou la
            // fais disparaitre en marquant lu ou supprimer avec les boutons d'actions, revenir
            // automatiquement aux icônes."
            NowBarWidgetProvider.closePeekIfShowing(applicationContext, sbn.key, sbn.postTime)
            NowBarWidgetProvider.requestUpdate(applicationContext)
        } catch (_: Throwable) {
            // Same reasoning as above: never let this widget nice-to-have take the service down.
        }

        // The original notification itself was removed (by its app, the user, whatever reason).
        if (latestModeActive.remove(sbn.key) != null) {
            // It belonged to the LATEST-mode queue. If it was the one currently shown in the
            // shared slot, promote the next-most-recent survivor; if it wasn't (e.g. it was
            // sitting in the shade behind the current mirror and got swiped on its own), just
            // drop it from the queue and leave the current mirror untouched.
            if (sbn.key == latestOriginalKey) {
                latestOriginalKey = null
                promoteNextLatestMode()
            }
            return
        }
        allModeMirrors.remove(sbn.key)?.let { cancelMirror(it) }
    }

    /**
     * Re-posts the shared LATEST-mode slot with whatever is now the most recent entry left in
     * the fallback queue, or clears the slot if the queue is empty or the option is turned off.
     * Called whenever the currently-mirrored original (or its mirror) is dismissed.
     */
    private fun promoteNextLatestMode() {
        val next = latestModeActive.entries.lastOrNull()
        if (next != null && LatestModePrefs.isFallbackEnabled(applicationContext)) {
            latestOriginalKey = next.key
            mirror(next.value, MIRROR_ID)
        } else {
            // Option disabled, or nothing left to fall back to: clear the slot, same as the
            // original behavior before this option existed.
            cancelMirror(MIRROR_ID)
        }
    }

    /**
     * Builds the [AllNotifEntryPush] that feeds [sbn] into the shared "Toutes notifs" widget
     * history — which also drives "Dernière notif"/la montre, see mirror()'s own push (MERGED
     * 20/09/2026) — without touching the actual system-notification mirror (mirror() below
     * already covers that for the ALL-mode bootstrap and for the single LATEST-mode notification
     * promoted into the shared slot). Used to catch up every OTHER
     * currently-active LATEST-mode notification too, so "Toutes notifs" isn't stuck showing just
     * one entry per LATEST-mode app after a listener reconnect (see
     * rebuildStateFromActiveNotifications' own comment, 17/09/2026). Same title/image extraction
     * as mirror(), duplicated rather than shared since mirror() also builds the actual system
     * notification, which this deliberately skips.
     *
     * Skips Sofascore's package unconditionally (18/09/2026): SofascoreNotificationListenerService
     * already pushes its OWN entry for the same notification into this same shared history, in its
     * proper match-tile shape (see WidgetAllNotificationsStore.Kind.SOFASCORE_MATCH) — pushing a
     * second, generic (image+title) entry for it here raced that dedicated push (whichever landed
     * last decided the tile's presentation, and sometimes both ended up coexisting as two tiles for
     * one match — Yann: "j'ai encore des applis Sofascore qui s'affichent bien en vue sport mais
     * s'affichent comme les autres notifs en vue toutes notifs [...] parfois j'ai même deux icônes
     * pour un même match"). This does NOT stop Sofascore from being mirrored elsewhere when
     * configured ALL/LATEST in the app-selection screen — mirror() still builds the real Now Bar
     * notification for it as normal; only ITS OWN push into "Toutes notifs" (and so, via it, into
     * "Dernière notif"/la montre) is skipped here (and in mirror() itself, see its own such guard) —
     * SofascoreNotificationListenerService's own match-tile push covers that instead.
     *
     * Split out 20/09/2026 from what used to be [pushAllNotifsHistoryOnly] in one step, so
     * [refillAllNotifsHistory] can build a whole batch of these up front and hand it to
     * [NowBarWidgetProvider.pushToAllNotificationsBatch] in one call instead of pushing (and
     * redrawing the widget) once per notification — see that function's doc for why looping
     * call-by-call used to make "Toutes notifs" visibly flicker through every notification,
     * oldest first, after a dismissal. Returns null (silently) if [sbn] is Sofascore's own
     * package, or if anything about building the entry throws — same "never let this widget
     * nice-to-have crash the listener" reasoning the old inline try/catch had.
     */
    private fun buildAllNotifEntryPush(sbn: StatusBarNotification): AllNotifEntryPush? {
        if (sbn.packageName == SofascoreNotificationListenerService.SOFASCORE_PACKAGE) return null
        return try {
            val extras = sbn.notification.extras
            val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
                ?: getAppName(sbn.packageName)
            // Same raw-text extraction as mirror() below — see AllNotifEntryPush.text's doc
            // ("peek" feature, NEW 18/09/2026): needed here too so an entry bootstrapped straight
            // from an already-active notification (listener reconnect) can also be peeked, not
            // just one that arrived through onNotificationPosted.
            val rawText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: ""
            val image = NotificationImageExtractor.extract(applicationContext, sbn)
            val actions = widgetActionsFor(sbn.notification)
            val isConversation = isConversationNotification(sbn)

            // Deliberately NOT applying the per-app "Titre ↔ texte" invert flag here — that
            // setting is a Now Bar-only concern (see mirror()'s own note on this), and this
            // function never touches the Now Bar's own system notification, only "Toutes
            // notifs" history. Always the ORIGINAL title (18/09/2026, Yann: "ne pas tenir
            // compte des applis où j'ai indiqué qu'il faut inverser titre et texte [...]
            // l'inversion ne doit servir que pour la now bar").
            AllNotifEntryPush(
                key = sbn.key,
                postTimeMillis = sbn.postTime,
                kind = WidgetAllNotificationsStore.Kind.GENERIC,
                title = rawTitle,
                text = rawText,
                packageName = sbn.packageName,
                isConversation = isConversation,
                image = image,
                contentIntent = sbn.notification.contentIntent,
                actions = actions,
                detailLines = if (isConversation) messageLinesFor(sbn, rawTitle) else emptyList()
            )
        } catch (_: Throwable) {
            null
        }
    }

    /** Pushes [sbn] into "Toutes notifs" on its own — see [buildAllNotifEntryPush]. Used where a single, standalone push is enough (the LATEST-mode bootstrap loop below); [refillAllNotifsHistory] builds and pushes a whole batch instead. */
    private fun pushAllNotifsHistoryOnly(sbn: StatusBarNotification) {
        val entry = buildAllNotifEntryPush(sbn) ?: return
        try {
            NowBarWidgetProvider.pushToAllNotifications(applicationContext, entry)
        } catch (_: Throwable) {
            // Same reasoning as mirror()'s own push block: never let this widget nice-to-have
            // crash the listener during catch-up.
        }
    }

    /**
     * Up to three of [notification]'s own action buttons, as [WidgetAction]s — shared by mirror()
     * (the true LATEST push) and pushAllNotifsHistoryOnly() (the "Toutes notifs" bootstrap catch-up)
     * so a "peek" (see WidgetPeekPrefs' class doc) can show the same action row regardless of which
     * path fed that entry. Gated behind WidgetActionsPrefs so the widget stays exactly as compact
     * as before for anyone who hasn't opted in; an action with no usable title is skipped rather
     * than shown as an empty button.
     */
    private fun widgetActionsFor(notification: Notification): List<WidgetAction> {
        if (!WidgetActionsPrefs.isEnabled(applicationContext)) return emptyList()
        return notification.actions
            ?.take(3)
            ?.mapNotNull { WidgetAction.from(it) }
            ?: emptyList()
    }

    /**
     * Détecte si [sbn] est une notification de "conversation" (messagerie — WhatsApp, Signal…)
     * plutôt qu'un contenu qui REMPLACE simplement le précédent en réutilisant le même id (ex. Le
     * Monde — voir WidgetAllNotificationsStore's IDENTITY section pour pourquoi la distinction
     * compte). Ajouté le 17/09/2026 : Yann s'est envoyé 5 messages de test dans la même
     * conversation WhatsApp et a vu 5 cases distinctes dans "Toutes notifs" au lieu d'une seule
     * mise à jour — alors que la conversation elle-même reste UNE SEULE notification Android
     * (MessagingStyle) mise à jour en place à chaque message, exactement comme Sofascore avec un
     * match (voir SofascoreNotificationParser, même mécanisme confirmé sur appareil).
     *
     * Trois signaux, au cas où l'app source n'en fournit qu'un ou deux :
     * - `shortcutId` renseigné : l'app a déclaré cette conversation comme "Conversation Shortcut"
     *   (WhatsApp, Signal, Messages… depuis Android 11).
     * - `category == CATEGORY_MESSAGE` : catégorie standard des notifs de messagerie.
     * - présence de `EXTRA_MESSAGING_PERSON` dans les extras : marqueur du template
     *   `NotificationCompat.MessagingStyle`, que ces apps utilisent pour accumuler les messages.
     */
    private fun isConversationNotification(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (n.shortcutId != null) return true
        if (n.category == Notification.CATEGORY_MESSAGE) return true
        return n.extras.containsKey(Notification.EXTRA_MESSAGING_PERSON)
    }

    /**
     * NEW 21/09/2026, watch "Notification" complication detail screen (Yann: "pour les
     * notifications comme les messages [...] afficher toutes les notifs de l'expéditeur";
     * précision : "il suffit de lire le centre de notifs [...] ce que l'application fait déjà
     * normalement") — feeds [AllNotifEntryPush.detailLines] for a conversation notification.
     *
     * A conversation is, confirmed on device (see [isConversationNotification]'s doc), ONE
     * Android notification reused per conversation via NotificationCompat.MessagingStyle, which
     * already accumulates the recent messages itself (EXTRA_MESSAGES/EXTRA_HISTORIC_MESSAGES) —
     * exactly the same source NotificationImageExtractor already reads for the contact photo, so
     * no new store is needed, just read what's already there. `.messages` covers the currently
     * shown messages; historicMessages are the ones MessagingStyle has already rotated out of the
     * visible set but the app still attached — both are included so a long-ish exchange isn't
     * truncated to whatever fits the "visible" set alone.
     *
     * Ordered MOST RECENT FIRST (MessagingStyle's own list is chronological, oldest first, so
     * reversed here) — same "glance and read the latest without scrolling" convention as
     * SofascoreNotificationListenerService.collectLines' own EXTRA_TEXT_LINES ordering. Each line
     * is prefixed with the sender's own name when it's available AND differs from the
     * conversation's own title (i.e. only useful for a group chat — a 1:1 conversation's sender is
     * already the notification's title, no need to repeat it on every line).
     *
     * Capped at [MAX_DETAIL_LINES] messages, same reasoning as Sofascore's own 6-line Android cap
     * — this is a quick glance on a watch screen, not a full chat history browser.
     */
    private fun messageLinesFor(sbn: StatusBarNotification, conversationTitle: String): List<String> {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification)
            ?: return emptyList()
        val all = (style.historicMessages + style.messages)
        return all.takeLast(MAX_DETAIL_LINES).reversed().mapNotNull { message ->
            val text = message.text?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val sender = message.person?.name?.toString()?.takeIf { it.isNotBlank() && it != conversationTitle }
            if (sender != null) "$sender : $text" else text
        }
    }

    private fun mirror(sbn: StatusBarNotification, mirrorId: Int) {
        val n = sbn.notification
        val extras = n.extras
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
            ?: getAppName(sbn.packageName)
        val rawText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: ""

        // Some apps put the more useful string in the title rather than the text (e.g. a
        // price alert app: title = "AAPL +5%", text = generic body copy). shortChipText()
        // below prefers `text` for the collapsed pill, so swapping here is what actually
        // puts the title in the pill for those apps.
        //
        // Now-Bar-ONLY (18/09/2026, Yann: "pour la vue notification texte et toutes notifs, ne
        // pas tenir compte des applis où j'ai indiqué qu'il faut inverser titre et texte [...]
        // l'inversion ne doit servir que pour la now bar") — nowBarTitle/nowBarText below feed
        // ONLY the actual system notification built further down (the real Samsung Now Bar
        // surface). pushToAllNotifications ("Toutes notifs", and via it "Dernière notif"/la
        // montre — voir plus bas) deliberately keeps using the ORIGINAL rawTitle/rawText below
        // instead, never these.
        val invert = AppMirrorPrefs.getInvertTitleText(applicationContext, sbn.packageName)
        val nowBarTitle = if (invert) rawText.ifBlank { rawTitle } else rawTitle
        val nowBarText = if (invert) rawTitle else rawText

        // Extraction fusionnée avec le traitement Sofascore lors du rapprochement avec
        // Sport Watch Complication — voir NotificationImageExtractor.
        val image = NotificationImageExtractor.extract(applicationContext, sbn)   // computed once, reused for the large icon and the chip attempt below

        // Up to three of the notification's own action buttons (e.g. "Reply", "Mark as read"),
        // handed over as live PendingIntents right now while they're still valid Binder
        // references (same reasoning as contentIntent below) -- same cap as the
        // system-notification mirror. Rendered as text (the action's own label) in the widget
        // rather than an icon, and an action with no usable title is skipped rather than shown
        // as an empty button. Gated behind the setting so the widget stays exactly as compact
        // as before for anyone who hasn't opted in.
        val widgetActions = widgetActionsFor(n)
        val isConversation = isConversationNotification(sbn)

        // Feeds the widget's shared "Toutes notifs" history (added 17/09/2026 at Yann's request) —
        // a rolling history of the last 5 RECEIVED notifications. MERGED 20/09/2026 (Yann: "Fusionne
        // toutes les listes que tu peux [...] Sofascore peut apparaître en dernière notif. Toutes
        // les notifs des applis choisies peuvent y apparaître") — this is now also the SOLE source
        // for the widget's "Dernière notif" view and the watch's "Notification" complication, which
        // simply read whichever entry here is currently most recent (see
        // NowBarWidgetProvider.applyLatestContent/syncWatchToLatest) instead of being written
        // separately by their own dedicated push (the old WidgetNotificationStore/pushLive()).
        // That separate store had to be independently kept in sync with this history on every
        // removal path by hand, which is exactly the class of bug behind the "widget et
        // complication montre marquent aucune notification alors qu'il y en a plein" reports —
        // this history already self-heals correctly on every removal (see
        // WidgetAllNotificationsStore.pruneAgainstActive / refillAllNotifsHistory below), so
        // deriving "Dernière notif" from it instead removes that whole class of drift rather than
        // patching each removal path one at a time.
        //
        // Pushed for every mirrored notification, ALL or LATEST mode alike — one push per received
        // event, whether that's a brand new notification or an existing one updated in place (same
        // sbn.key) — EXCEPT Sofascore (18/09/2026, see pushAllNotifsHistoryOnly's doc for the full
        // reasoning): Sofascore still gets a proper Now Bar notification from this function when
        // configured ALL/LATEST, same as any other app, but its OWN entry in this shared history —
        // and so its eligibility for "Dernière notif"/the watch too, per Yann's remark above — comes
        // from SofascoreNotificationListenerService's dedicated match-tile push instead, to avoid a
        // second, racing, generic-looking entry for the same match (see that push's own doc,
        // "j'ai encore des applis Sofascore qui s'affichent [...] parfois j'ai même deux icônes pour
        // un même match").
        if (sbn.packageName != SofascoreNotificationListenerService.SOFASCORE_PACKAGE) {
            try {
                NowBarWidgetProvider.pushToAllNotifications(
                    applicationContext,
                    AllNotifEntryPush(
                        key = sbn.key,
                        postTimeMillis = sbn.postTime,
                        kind = WidgetAllNotificationsStore.Kind.GENERIC,
                        title = rawTitle,
                        text = rawText,
                        packageName = sbn.packageName,
                        isConversation = isConversation,
                        image = image,
                        contentIntent = n.contentIntent,
                        actions = widgetActions,
                        detailLines = if (isConversation) messageLinesFor(sbn, rawTitle) else emptyList()
                    )
                )
            } catch (t: Throwable) {
                // TEMPORARY diagnostic: surfaces the exact failure on screen since this device
                // can't be hooked up to Android Studio for logcat. Safe to remove once the widget
                // update path is confirmed stable.
                Toast.makeText(
                    applicationContext,
                    "Widget: ${t.javaClass.simpleName}: ${t.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentTitle(nowBarTitle)
            .setContentText(nowBarText)
            // Collapsed pill content. Chip is max 96dp wide: text only renders if it fits,
            // otherwise the system falls back to icon-only — keep this short.
            .setShortCriticalText(shortChipText(nowBarText, nowBarTitle))
            .setStyle(NotificationCompat.BigTextStyle().bigText(nowBarText))
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setCategory(n.category ?: Notification.CATEGORY_MESSAGE)
            .setWhen(n.`when`)
            .setShowWhen(true)
            .setContentIntent(n.contentIntent)
            .setLargeIcon(appIconBitmap(sbn.packageName))   // top-right thumbnail in the expanded popup — app icon, not the contact photo
            .addExtras(Bundle().apply {
                putBoolean(EXTRA_MIRROR, true)
                putString(EXTRA_ORIGINAL_KEY, sbn.key)
            })

        n.actions?.take(3)?.forEach { action ->
            val pi = action.actionIntent ?: return@forEach
            builder.addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_send,
                    action.title,
                    pi
                ).build()
            )
        }

        if (Build.VERSION.SDK_INT >= 36) {
            try {
                builder.setRequestPromotedOngoing(true)
            } catch (_: Throwable) { }
        }

        var notification = builder.build()

        // Small icon slot: the extracted photo when available, so the pill and the reduced
        // lock-screen Now Bar show the contact/notification image. Independent from the
        // large icon above, which now always shows the app icon (top-right thumbnail in
        // the expanded popup). Falls back to the app icon here too if there's no image.
        val chipIcon = image?.let { bmp ->
            // Same reasoning as appIcon() below: without this, a plain createWithBitmap() photo
            // is treated as a "legacy" square icon, shrunk into the adaptive safe zone and given
            // a synthesized background plate (usually white) to fill the rest of the circle.
            // The photo is already full-bleed, so telling the OS it's adaptive lets it fill the
            // whole circle with no background showing through.
            if (Build.VERSION.SDK_INT >= 26) Icon.createWithAdaptiveBitmap(bmp) else Icon.createWithBitmap(bmp)
        } ?: appIcon(sbn.packageName)
        chipIcon?.let { icon ->
            notification = Notification.Builder.recoverBuilder(this, notification)
                .setSmallIcon(icon)
                .build()
        }

        // Posting to an id that's already showing is an in-place update as far as the system
        // is concerned — no removal event is generated. That's what lets a LATEST-mode swap or
        // an ALL-mode content refresh happen without ever triggering onNotificationRemoved for
        // our own package, which is the other half of the race fix above.
        getSystemService(NotificationManager::class.java).notify(mirrorId, notification)
    }

    /**
     * True for a media-playback control notification (radio/music "now playing": play, pause,
     * skip). This is how the system itself tells a media notification apart from an ordinary
     * one, so it works regardless of how a given app flags (or doesn't flag) it as ongoing:
     * - CATEGORY_TRANSPORT is the category media apps put on their playback notification, or
     * - EXTRA_MEDIA_SESSION carries the app's MediaSession token, present on any notification
     *   built with MediaStyle even if the app didn't also set the category above.
     */
    private fun isMediaPlaybackNotification(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (n.category == Notification.CATEGORY_TRANSPORT) return true
        return n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
    }

    private fun cancelMirror(mirrorId: Int) {
        getSystemService(NotificationManager::class.java).cancel(mirrorId)
    }

    private fun cancelOriginal(key: String) {
        try {
            cancelNotification(key)
        } catch (_: Throwable) {
            activeNotifications?.firstOrNull { it.key == key }?.let {
                cancelNotification(it.key)
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_description)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun appIconBitmap(pkg: String): Bitmap? {
        return try {
            drawableToBitmap(packageManager.getApplicationIcon(pkg))
        } catch (_: Throwable) { null }
    }

    /**
     * The source app's real icon, used for the small-icon slot (left side, next to the title).
     * Modern launcher icons are AdaptiveIconDrawable (background + foreground layers). Baking
     * both layers into a flat bitmap and handing it to the system as a plain Icon is what
     * produces the ugly solid-color/white square once the OS tries to mask it into a circle.
     * Icon.createWithAdaptiveBitmap() tells the OS "this bitmap already follows the adaptive
     * safe-zone convention", so it applies the same clean round mask native notifications get.
     */
    private fun appIcon(pkg: String): Icon? {
        return try {
            val drawable = packageManager.getApplicationIcon(pkg)
            val bitmap = drawableToBitmap(drawable)
            if (Build.VERSION.SDK_INT >= 26 && drawable is AdaptiveIconDrawable) {
                Icon.createWithAdaptiveBitmap(bitmap)
            } else {
                Icon.createWithBitmap(bitmap)
            }
        } catch (_: Throwable) { null }
    }

    /**
     * Text shown in the collapsed pill. Prefer the message text over the title.
     * No truncation here: Samsung's Now Bar appears to handle long text itself
     * (marquee/scroll) rather than following the strict AOSP 96dp chip-fit rule,
     * so cutting it in code would only hide that behavior.
     */
    private fun shortChipText(text: String, title: String): String {
        return text.ifBlank { title }
    }

    private fun getAppName(pkg: String): String {
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
    }

    /**
     * Tries, in order:
     * 1) the contact photo attached to the sender of a MessagingStyle notification
     *    (WhatsApp, Messages, etc. put it here, NOT in the large icon),
     * 2) a BigPictureStyle image (EXTRA_PICTURE),
     * 3) the notification's actual large icon via the official getLargeIcon() accessor
     *    (reading raw extras instead of this, like the previous version did, misses
     *    most real-world notifications).
     */
    // extractImageBitmap()/drawableFromIcon() ont été retirées lors de la fusion avec Sport
    // Watch Complication : cette logique vit maintenant dans NotificationImageExtractor,
    // partagée avec com.yann.nowbarmirror.sport.SofascoreNotificationListenerService (voir
    // README.md, section "Fusion avec Sport Watch Complication"). drawableToBitmap() reste ici
    // : encore utilisée par appIconBitmap()/appIcon() ci-dessous, sans rapport avec
    // l'extraction d'image de notification.
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
}
