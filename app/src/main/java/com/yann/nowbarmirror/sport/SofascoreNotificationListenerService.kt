package com.yann.nowbarmirror.sport

import android.app.Notification
import android.content.Intent
import android.graphics.Bitmap
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.yann.nowbarmirror.NotificationImageExtractor
import com.yann.nowbarmirror.settings.WidgetActionsPrefs
import com.yann.nowbarmirror.widget.AllNotifEntryPush
import com.yann.nowbarmirror.widget.NowBarWidgetProvider
import com.yann.nowbarmirror.widget.SofascoreWidgetMatch
import com.yann.nowbarmirror.widget.WidgetAction
import com.yann.nowbarmirror.widget.WidgetAllNotificationsStore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Une notification Sofascore active = un match. [key] est
 * [StatusBarNotification.getKey], stable tant que la notif reste active (une
 * mise à jour en place — même id/tag — garde la même clé). [latestLine] est
 * la ligne la plus récente, pour donner un aperçu dans l'écran d'accueil
 * (SportActivity). [notifImage] est le résultat de
 * [SofascoreNotificationListenerService.extractNotificationImage] pour
 * CETTE notif précise — la même fonction, donc la même image, que celle
 * envoyée à la montre par [SofascoreNotificationListenerService.refresh] —
 * affiché en vignette dans la liste pour vérifier visuellement ce qui est
 * réellement extrait avant de s'y fier côté montre. `null` si aucune des
 * pistes de [extractNotificationImage] n'aboutit pour cette notif.
 */
data class SofascoreMatchOption(
    val key: String,
    val homeTeam: String,
    val awayTeam: String,
    val latestLine: String,
    val notifImage: Bitmap?
)

/**
 * RÔLE REDÉFINI le 16/09/2026 (demandé par Yann — voir README, section de
 * cette date) : Sofascore est maintenant TOUJOURS la base de l'application.
 * Ce service relit les notifications de l'app Sofascore
 * (`com.sofascore.results`, vérifié via sa fiche Play Store — pas à
 * confondre avec les apps "Livesport"/Soccerway, éditeur différent) et
 * pousse à la montre, pour la notification ACTIVE (voir ci-dessous), les
 * noms d'équipe + l'image telles que Sofascore les fournit, et un score/
 * statut déduits du texte de la notif (SofascoreNotificationParser). Les
 * overrides TheSportsDB / Live Tennis API ont été retirés le 24/09/2026.
 *
 * Notification ACTIVE (celle qui pilote la complication) : choisie par
 * Yann dans l'app et persistée dans [SofascorePrefs] :
 * - LATEST (par défaut) : la notif la plus récemment mise à jour parmi
 *   toutes les notifs Sofascore actives.
 * - CHOSEN : un match précis, choisi à la main parmi une liste de ceux
 *   actuellement dans le centre de notifications (voir [listAvailableMatches],
 *   appelé depuis SportActivity). Si ce match n'a plus de notif active (fini,
 *   notif supprimée), on retombe automatiquement sur LATEST plutôt que de ne
 *   rien afficher.
 *
 * DEPUIS l'ajout de la vue "Sport" au widget lock-screen (voir
 * [pushWidgetMatches]) : ce service alimente maintenant DEUX surfaces à
 * chaque [refresh] — la complication montre (un seul match "actif", comme
 * ci-dessus) ET le widget (tous ceux actuellement actifs, voir
 * [pushWidgetMatches]). Les deux partagent la même extraction de base
 * ([toMatchResult]), qui ne fait que du parsing, sans se soucier de la
 * notion de notif "active".
 *
 * Nécessite que Yann accorde l'accès aux notifications à cette app
 * (permission spéciale, non demandable au runtime contrairement à
 * POST_NOTIFICATIONS — voir le bouton dédié dans PermissionsActivity qui ouvre
 * directement l'écran système).
 *
 * CONFIRMÉ SUR APPAREIL (test du 12/09, Real Madrid - Rayo Vallecano) :
 * Sofascore poste UNE notification par match, mise à jour en place, en
 * style Inbox (`EXTRA_TEXT_LINES`, plafonné à 6 lignes par Android — les 6
 * lignes de la capture le confirment).
 *
 * ATTENTION ORDRE : `InboxStyle.addLine()` affiche les lignes dans leur
 * ordre d'ajout, la première ajoutée en haut (doc officielle Android). La
 * capture montre l'événement le plus récent EN HAUT ("Match terminé"),
 * donc Sofascore ajoute chaque nouvel événement EN PREMIER : le tableau brut
 * `EXTRA_TEXT_LINES` est donc déjà trié du plus récent au plus ancien —
 * [collectLines] ne le renverse pas.
 *
 * CORRIGÉ (test du 14/09, notif tennis affichant le score du foot terminé) :
 * une version antérieure regroupait les notifs par
 * [StatusBarNotification.getGroupKey] en supposant qu'une clé de groupe =
 * un match. En réalité Sofascore semble regrouper TOUTES ses notifications
 * (tous matchs confondus) sous la même clé de groupe système — un match en
 * cours de foot et un match de tennis qui démarre se retrouvaient donc dans
 * le MÊME groupe, et [extractTeams]/[collectLines] picoraient des lignes
 * des deux matchs mélangées (d'où le score du foot terminé qui ressortait
 * sur la notif tennis). Le code ne groupe plus du tout : chaque
 * [StatusBarNotification] Sofascore active est traitée individuellement
 * (une notif = un match, mise à jour en place — voir plus haut), identifiée
 * par sa propre [StatusBarNotification.getKey] plutôt que par un groupKey
 * partagé.
 */
class SofascoreNotificationListenerService : NotificationListenerService() {

    // NEW 18/09/2026, "peek" feature (see WidgetPeekPrefs' class doc) — same robustness pattern as
    // MirrorNotificationListener's own ready/pendingDismissKey: a peek's dismiss button targets
    // this service directly (PendingIntent.getService), which can spin the process back up before
    // onListenerConnected() has actually fired, so a dismiss request arriving that early is queued
    // and replayed once the listener is really connected instead of silently failing.
    private val ready = AtomicBoolean(false)
    private var pendingDismissKey: String? = null
    private var pendingDismissPostTime: Long = -1L

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        ready.set(true)
        // ORDRE INVERSÉ 23/09/2026 (voir pickLatestAvoidingDuplicate's doc, "éviter le doublon
        // Score en direct / Dernière notif") : bootstrapAllNotificationsHistory() DOIT tourner
        // AVANT refresh() maintenant, pas après — refresh() lit WidgetAllNotificationsStore pour
        // savoir ce que "Dernière notif" affiche actuellement, et bootstrapAllNotificationsHistory()
        // est justement ce qui remplit/rafraîchit ce store à la (re)connexion. Dans l'ancien ordre,
        // refresh() lisait un store pas encore à jour (souvent vide, juste après l'installation ou
        // l'octroi de la permission) et pouvait donc rater la détection du doublon lors du tout
        // premier rendu.
        bootstrapAllNotificationsHistory()
        refresh()
        pendingDismissKey?.let { key ->
            pendingDismissKey = null
            val postTime = pendingDismissPostTime
            pendingDismissPostTime = -1L
            dismiss(key, postTime)
        }
    }

    /**
     * Same reasoning as MirrorNotificationListener.onStartCommand's ACTION_DISMISS_WIDGET
     * handling — a peek's own dismiss button (see NowBarWidgetProvider.sofascoreDismissPendingIntent)
     * targets this service directly with ACTION_DISMISS_WIDGET, so the tap keeps working even if
     * this process had been killed and needs the system to spin it back up first.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISMISS_WIDGET) {
            val key = intent.getStringExtra(EXTRA_DISMISS_KEY)
            val postTimeMillis = intent.getLongExtra(EXTRA_DISMISS_POST_TIME, -1L)
            if (key != null) {
                if (ready.get()) {
                    dismiss(key, postTimeMillis)
                } else {
                    pendingDismissKey = key
                    pendingDismissPostTime = postTimeMillis
                }
            }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // NEW 21/09/2026, fix for "silent" widget action buttons (mark as read/delete/archive/
        // mute — see widget.WidgetAction.dismissesOnFire's doc): fires the real action first, then
        // — only if NowBarWidgetProvider.fireAction says to — dismiss() this entry exactly like
        // ACTION_DISMISS_WIDGET above does. Actions on a Sofascore match are almost never present
        // in practice (see widgetActionsFor's doc) but handled here for parity/consistency.
        if (intent?.action == ACTION_FIRE_WIDGET_ACTION) {
            val key = intent.getStringExtra(EXTRA_ACTION_KEY)
            val postTimeMillis = intent.getLongExtra(EXTRA_ACTION_POST_TIME, -1L)
            val actionIndex = intent.getIntExtra(EXTRA_ACTION_INDEX, -1)
            if (key != null && actionIndex >= 0 &&
                NowBarWidgetProvider.fireAction(key, postTimeMillis, actionIndex) ==
                    NowBarWidgetProvider.FireActionResult.FIRED_DISMISS
            ) {
                if (ready.get()) {
                    dismiss(key, postTimeMillis)
                } else {
                    pendingDismissKey = key
                    pendingDismissPostTime = postTimeMillis
                }
            }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /** Cancels the Sofascore notification identified by [key], then closes/refreshes the widget exactly like a normal removal would (see onNotificationRemoved) — a peek's dismiss button never waits for the async onNotificationRemoved round-trip for its own instant feedback. */
    private fun dismiss(key: String, postTimeMillis: Long) {
        try {
            cancelNotification(key)
        } catch (_: Throwable) {
            activeNotifications?.firstOrNull { it.key == key }?.let { cancelNotification(it.key) }
        }
        // ORDRE INVERSÉ 23/09/2026 — même raisonnement que onListenerConnected/onNotificationRemoved
        // ci-dessous : removeFromAllNotificationsHistory (qui retire ce match de
        // WidgetAllNotificationsStore ET repousse tout ce qui reste actif) doit tourner AVANT
        // refresh(), sans quoi refresh() déciderait du "match précédent" (pickLatestAvoidingDuplicate)
        // sur un store qui contient encore le match qu'on est justement en train de supprimer.
        removeFromAllNotificationsHistory(key, postTimeMillis)
        refresh()
        try {
            NowBarWidgetProvider.closePeekIfShowing(applicationContext, key, postTimeMillis)
        } catch (_: Throwable) {
        }
    }

    /**
     * Catch-up (17/09/2026, Yann: "le comportement doit bien être de lire toutes les notifs dans
     * le centre de notif et non plus uniquement celles reçues après installation de l'appli ou
     * mise à jour"): pushes every Sofascore notification ALREADY active at connect time (first
     * install, notification access just granted, or a process restart) into the shared "Toutes
     * notifs" history — before this, [pushToAllNotificationsHistory] only ran from
     * [onNotificationPosted], i.e. for notifications posted AFTER this listener (re)connected, so
     * a match notification already sitting in the shade at that moment was silently skipped until
     * its next score update. Mirrors the same fix already applied on the Accueil-tab side (see
     * MirrorNotificationListener.rebuildStateFromActiveNotifications). Safe to call on every
     * reconnect: [pushToAllNotificationsHistory] keys each entry by (sbn.key, sbn.postTime) — see
     * WidgetAllNotificationsStore's IDENTITY section — so re-pushing an already-known notification
     * just updates that tile in place rather than duplicating it.
     *
     * ALSO prunes the shared history against the FULL notification shade first (18/09/2026, same
     * fix as MirrorNotificationListener.rebuildStateFromActiveNotifications — see
     * WidgetAllNotificationsStore.pruneAgainstActive's doc): a Sofascore match-tile entry whose
     * onNotificationRemoved was missed while this process was dead (killed in the background, or
     * the phone rebooted) would otherwise sit there forever, exactly like the generic-entry bug
     * Yann reported for Gmail/Calendar. `activeNotifications` here (not
     * [activeSofascoreNotifications]) since pruning has to validate GENERIC entries from other
     * apps too, not just Sofascore's own.
     */
    private fun bootstrapAllNotificationsHistory() {
        try {
            activeNotifications?.let { all -> WidgetAllNotificationsStore.pruneAgainstActive(applicationContext, all.toList()) }
        } catch (_: Throwable) {
            // Voir la doc de la fonction : le widget ne doit jamais faire tomber ce service.
        }
        val notifications = activeSofascoreNotifications() ?: return
        pushAllToAllNotificationsHistory(notifications)
    }

    /**
     * Construit puis pousse en UN seul appel batch l'[AllNotifEntryPush] de chaque match de
     * [notifications] — voir [buildAllNotifEntryPush]/[NowBarWidgetProvider.pushToAllNotificationsBatch]
     * pour pourquoi (20/09/2026, même correction que MirrorNotificationListener.refillAllNotifsHistory) :
     * pousser un par un redessinait le widget autant de fois qu'il y avait de matchs actifs.
     */
    private fun pushAllToAllNotificationsHistory(notifications: List<StatusBarNotification>) {
        val entries = notifications.mapNotNull { sbn -> buildAllNotifEntryPush(sbn) }
        try {
            NowBarWidgetProvider.pushToAllNotificationsBatch(applicationContext, entries)
        } catch (_: Throwable) {
            // Voir la doc de la fonction : le widget ne doit jamais faire tomber ce service.
        }
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == SOFASCORE_PACKAGE) {
            // ORDRE INVERSÉ 23/09/2026 (voir pickLatestAvoidingDuplicate's doc) —
            // pushToAllNotificationsHistory doit tourner AVANT refresh() : c'est cet appel qui met à
            // jour WidgetAllNotificationsStore avec CETTE notification (nouvelle position la plus
            // récente si c'est bien la dernière reçue tous types confondus), et refresh() a besoin de
            // lire ce store déjà à jour pour savoir si le match qu'il s'apprête à choisir pour "Score
            // en direct" est aussi celui que "Dernière notif" affiche.
            pushToAllNotificationsHistory(sbn)
            refresh()
        }
    }

    /**
     * Builds the [AllNotifEntryPush] that feeds the SAME shared "Toutes notifs" history the
     * generic mirror listener feeds (see WidgetAllNotificationsStore's class doc) — added
     * 17/09/2026 at Yann's request ("Garder la même présentation qu'aujourd'hui pour les notifs de
     * Sofascore" inside that view). Reuses [toMatchResult]/[extractNotificationImage], the EXACT
     * same parsing already used for the dedicated Sport view (see [pushWidgetMatches]), so a
     * Sofascore entry in "Toutes notifs" renders with today's match-tile presentation instead of
     * the generic image+title one — see NowBarWidgetProvider.applyAllNotifSlotAsMatch.
     *
     * Split out 20/09/2026 from what used to be [pushToAllNotificationsHistory] in one step, so
     * [removeFromAllNotificationsHistory]/[bootstrapAllNotificationsHistory] can build a whole
     * batch of these up front and hand it to
     * [NowBarWidgetProvider.pushToAllNotificationsBatch]/[pushAllToAllNotificationsHistory] in ONE
     * call instead of pushing (and redrawing the widget) match by match — see that function's doc
     * for why looping call-by-call used to make "Toutes notifs" visibly flicker through every
     * match, oldest first, on every dismissal (same bug/fix as
     * MirrorNotificationListener.refillAllNotifsHistory). Returns `null` (silently) if
     * [toMatchResult] can't parse [sbn], or if anything about building the entry throws — same
     * "never let this widget nice-to-have take this service down" reasoning the old inline
     * try/catch had.
     */
    private fun buildAllNotifEntryPush(sbn: StatusBarNotification): AllNotifEntryPush? {
        return try {
            val match = toMatchResult(sbn) ?: return null
            val (rawTitle, rawText) = rawTitleAndText(sbn, match)
            AllNotifEntryPush(
                key = sbn.key,
                postTimeMillis = sbn.postTime,
                kind = WidgetAllNotificationsStore.Kind.SOFASCORE_MATCH,
                title = rawTitle,
                text = rawText,
                homeTeam = match.homeTeam,
                awayTeam = match.awayTeam,
                homeScore = match.homeScore,
                awayScore = match.awayScore,
                lastScorer = match.lastScorer,
                status = match.status,
                // Lazy (AUDIT 23/09/2026, see WidgetImageFiles): only extracted when this exact
                // posting has no image on disk yet — a refill/bootstrap re-pushing every active
                // match no longer re-extracts (and re-encodes) all their images.
                image = null,
                imageLoader = { extractNotificationImage(sbn) },
                contentIntent = sbn.notification.contentIntent,
                actions = widgetActionsFor(sbn.notification),
                // NEW 21/09/2026, watch "Notification" complication detail screen (Yann: "pour
                // [...] Sofascore, afficher toutes [...] celles du match" ; précision : "il suffit
                // de lire le centre de notifs [...] ce que l'application fait déjà normalement") —
                // same raw lines [collectLines] already extracts for parsing (EXTRA_TEXT_LINES,
                // Inbox style, capped at 6 by Android itself, already most-recent-first — see the
                // class doc's "CONFIRMÉ SUR APPAREIL" section), just surfaced as-is instead of only
                // feeding the parser. No new store needed: this match's whole notification already
                // carries every event line Sofascore has posted for it so far.
                detailLines = collectLines(sbn)
            )
        } catch (_: Throwable) {
            null
        }
    }

    /** Pousse [sbn] seul dans "Toutes notifs" — voir [buildAllNotifEntryPush]. Utilisé là où un seul push suffit (onNotificationPosted) ; [bootstrapAllNotificationsHistory]/[removeFromAllNotificationsHistory] construisent et poussent un lot entier à la place. */
    private fun pushToAllNotificationsHistory(sbn: StatusBarNotification) {
        val entry = buildAllNotifEntryPush(sbn) ?: return
        try {
            NowBarWidgetProvider.pushToAllNotifications(applicationContext, entry)
        } catch (_: Throwable) {
            // Voir la doc de la fonction : le widget ne doit jamais faire tomber ce service.
        }
    }

    /**
     * Raw Android notification title/text for [sbn] — NEW 18/09/2026, "peek" feature (see
     * WidgetPeekPrefs' class doc): [title] is EXTRA_TITLE itself (already exactly
     * "$homeTeam - $awayTeam" — see extractTeams — with [match]'s own teams as a fallback if
     * EXTRA_TITLE is ever missing), [text] is the single most recent score/event line (same
     * "most recent first" ordering as [collectLines] — see that function's doc). This is what lets
     * a SPORT/ALL_NOTIFS Sofascore tile be shown full-format when tapped, in the exact same shape
     * a generic notification's peek uses.
     */
    private fun rawTitleAndText(sbn: StatusBarNotification, match: MatchResult): Pair<String, String> {
        val rawTitle = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "${match.homeTeam} - ${match.awayTeam}"
        val rawText = collectLines(sbn).firstOrNull().orEmpty()
        return rawTitle to rawText
    }

    /**
     * Up to three of [notification]'s own action buttons, as [WidgetAction]s — same shape/gating
     * as MirrorNotificationListener.widgetActionsFor, duplicated rather than shared (separate
     * package/service, same reasoning as the rest of this app's Accueil/Sport split). Almost
     * always empty in practice — Sofascore's own notifications don't appear to carry action
     * buttons — kept for parity with "avec bouton d'action si active" in Yann's peek request.
     */
    private fun widgetActionsFor(notification: Notification): List<WidgetAction> {
        if (!WidgetActionsPrefs.isEnabled(applicationContext)) return emptyList()
        return notification.actions
            ?.take(3)
            ?.mapNotNull { WidgetAction.from(it) }
            ?: emptyList()
    }

    /** A removed Sofascore notification: drop it from the history, then [refresh] recomputes the active match. */
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == SOFASCORE_PACKAGE) {
            // ORDRE INVERSÉ 23/09/2026 — même raisonnement que dismiss()/onListenerConnected : voir
            // pickLatestAvoidingDuplicate's doc.
            removeFromAllNotificationsHistory(sbn.key, sbn.postTime)
            refresh()
            // "Peek" (NEW 18/09/2026, see WidgetPeekPrefs' class doc) — this match's notification
            // might be the one currently peeked (from either SPORT or ALL_NOTIFS), removed by
            // something other than the widget's own dismiss button (the match simply ending and
            // Sofascore clearing its own notification, "clear all", a direct swipe from the shade)
            // — Yann: "Si je supprime la notification ou la fais disparaitre [...] revenir
            // automatiquement aux icônes." A no-op duplicate of the same call inside dismiss()
            // when THAT was what triggered this removal — closePeekIfShowing is idempotent.
            try {
                NowBarWidgetProvider.closePeekIfShowing(applicationContext, sbn.key, sbn.postTime)
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * "Toutes notifs" (17/09/2026, Yann: "Si une notification a été supprimée du centre de
     * notifs, elle ne doit plus apparaître dans le widget") — [refresh] above already drops this
     * match from the dedicated Sport view (it recomputes from activeSofascoreNotifications()),
     * but the "Toutes notifs" history is a SEPARATE store (see WidgetAllNotificationsStore) that
     * needs its own explicit removal, matched on (key, postTimeMillis) together rather than key
     * alone (see that store's IDENTITY section) so only the exact posting that was dismissed goes.
     * No-op if that exact pair was never pushed there. Wrapped in try/catch for the same reason as
     * [pushWidgetMatches]/[pushToAllNotificationsHistory]: never let a widget nice-to-have take
     * this service down.
     */
    private fun removeFromAllNotificationsHistory(key: String, postTimeMillis: Long) {
        try {
            WidgetAllNotificationsStore.remove(applicationContext, key, postTimeMillis)
            // Same top-up as MirrorNotificationListener.refillAllNotifsHistory: dropping this
            // entry just shrinks "Toutes notifs" unless something re-pushes whatever else is
            // still actually active — re-push every currently active Sofascore match so a freed
            // slot gets refilled immediately instead of only at the next reconnect. FIXED
            // 20/09/2026, same fix/reasoning as MirrorNotificationListener.refillAllNotifsHistory:
            // this used to push each active match one at a time, redrawing the widget on every
            // single one — now built and pushed as one batch (pushAllToAllNotificationsHistory),
            // so a dismissal with several live matches settles on the final state directly
            // instead of flickering through each intermediate one.
            activeSofascoreNotifications()?.let { pushAllToAllNotificationsHistory(it) }
            NowBarWidgetProvider.requestUpdate(applicationContext)
        } catch (_: Throwable) {
        }
    }

    /**
     * Relit les notifications actives, calcule le match à afficher pour
     * celle qui est active (voir doc de classe) et pousse le résultat à la montre — PUIS pousse aussi le widget (voir
     * [pushWidgetMatches]), à partir de TOUTES les notifs actives (pas
     * seulement la cible ci-dessus).
     *
     * CORRIGÉ (demandé par Yann le 15/09/2026) : si plus aucune notif
     * Sofascore n'est active, la complication doit repasser à "Aucun
     * match" — auparavant cette fonction se contentait de ne rien faire
     * dans ce cas (`return`), donc la montre restait bloquée sur le
     * dernier score connu même après que Yann ait viré la notif du centre
     * de notifications. Le `null` de [activeSofascoreNotifications] (accès
     * non accordé) reste traité différemment de la liste vide (accès
     * accordé, juste plus rien à afficher) : dans le premier cas on ne peut
     * rien dire, donc on ne touche à rien (ni montre, ni widget).
     */
    fun refresh() {
        val notifications = activeSofascoreNotifications() ?: return
        if (notifications.isEmpty()) {
            if (lastWatchSignature != WATCH_CLEARED) {
                WatchSync.sendCleared(this)
                lastWatchSignature = WATCH_CLEARED
            }
            pushWidgetMatches(emptyList())
            return
        }

        // Si un match précis a été choisi ET qu'il a encore une notif
        // active, on le suit ; sinon (mode "dernière", ou match choisi
        // terminé/supprimé) on retombe sur la notif la plus récente — voir
        // [pickLatestAvoidingDuplicate] pour la nuance ajoutée le 23/09/2026 dans CE cas précis.
        val chosenKey = SofascorePrefs.loadChosenKey(this)
            ?.takeIf { SofascorePrefs.loadMode(this) == SofascorePrefs.Mode.CHOSEN }
        val target = (chosenKey?.let { key -> notifications.find { it.key == key } })
            ?: pickLatestAvoidingDuplicate(notifications.sortedByDescending { it.postTime })
            ?: return

        val match = toMatchResult(target) ?: return

        // Image combinée des deux logos telle que postée par Sofascore lui-même — voir
        // [extractNotificationImage].
        // AUDIT 23/09/2026 — only extract/encode the image and wake the watch over Bluetooth when
        // something it displays actually changed: refresh() also runs on SportActivity
        // interactions and after any other Sofascore match's update, which usually leave the
        // watched match untouched.
        // Same posting (key + postTime) = same image; MatchResult is a data class, so its
        // toString covers every displayed field.
        val watchSignature = "${target.key}|${target.postTime}|$match"
        if (watchSignature != lastWatchSignature) {
            val targetImage = extractNotificationImage(target)
            WatchSync.sendMatch(this, match, notifImage = targetImage?.let { WatchSync.bitmapToAsset(it) })
            lastWatchSignature = watchSignature
        }

        pushWidgetMatches(notifications)
    }

    /**
     * NEW 23/09/2026 (Yann : "sur montre si j'ai complication Sofascore et derniere notif et que
     * la dernière notif est un match Sofascore, ne pas afficher le même match sur la complication
     * Sofascore, afficher le match précédent. Afficher le même si c'est le seul.") — repli "auto"
     * (mode LATEST, ou mode CHOSEN retombant sur LATEST faute de notif encore active pour le match
     * choisi, voir [refresh]) pour la notif Sofascore active à afficher sur la complication "Score
     * en direct". Sans ce correctif, quand la notif Sofascore la plus récente est AUSSI, tous types
     * confondus, la notification la plus récente ([WidgetAllNotificationsStore]'s own most-recent
     * entry — ce que la complication "Notification"/le widget "Dernière notif" affichent déjà),
     * "Score en direct" affichait exactement le même match — un doublon inutile entre les deux
     * complications de la montre quand Yann les a assignées toutes les deux.
     *
     * [sortedByRecency] est déjà trié du plus récent au plus ancien par l'appelant. S'il n'y a
     * qu'UN SEUL match actif, ce doublon n'a de toute façon aucune alternative : "Afficher le même
     * si c'est le seul" — on le retourne tel quel, sans même aller lire
     * [WidgetAllNotificationsStore]. Sinon, si le plus récent est bien le doublon détecté, on
     * retombe sur le SUIVANT dans l'ordre de mise à jour ("le match précédent") plutôt que le plus
     * récent.
     *
     * Ne s'applique qu'à ce repli "auto" — un match choisi explicitement par Yann ([SofascorePrefs.Mode.CHOSEN],
     * tant que sa notif reste active) n'est jamais réécrit ici, voir [refresh] : c'est un choix
     * délibéré, pas un cas à désambiguïser.
     *
     * ORDRE : les appelants (onNotificationPosted/onListenerConnected/dismiss/onNotificationRemoved)
     * ont tous été réordonnés le même jour pour que [WidgetAllNotificationsStore] soit déjà à jour
     * avec l'événement en cours AVANT que [refresh]/cette fonction ne le lisent — sans ça, la
     * détection de doublon se serait basée sur l'état d'AVANT cet événement (voir leurs commentaires
     * "ORDRE INVERSÉ 23/09/2026" respectifs).
     */
    private fun pickLatestAvoidingDuplicate(sortedByRecency: List<StatusBarNotification>): StatusBarNotification? {
        val latest = sortedByRecency.firstOrNull() ?: return null
        if (sortedByRecency.size < 2) return latest

        val mostRecentAllNotif = WidgetAllNotificationsStore.get(applicationContext).firstOrNull()
        val duplicatesLatestNotif = mostRecentAllNotif != null &&
            mostRecentAllNotif.kind == WidgetAllNotificationsStore.Kind.SOFASCORE_MATCH &&
            mostRecentAllNotif.key == latest.key
        return if (duplicatesLatestNotif) sortedByRecency[1] else latest
    }

    /**
     * Combine extractTeams + collectLines + SofascoreNotificationParser.parse + buildRawFallback
     * — le pipeline que [refresh] utilisait déjà, en ligne, pour sa seule notif "active". Extrait
     * ici pour que [pushWidgetMatches] puisse le réutiliser sur TOUTES les notifs actives (jusqu'à
     * 4 affichées côte à côte dans la vue Sport du widget). Pur refactor, aucun changement de
     * comportement pour la notif active.
     */
    private fun toMatchResult(sbn: StatusBarNotification): MatchResult? {
        val (homeTeam, awayTeam) = extractTeams(sbn) ?: return null
        val lines = collectLines(sbn)
        if (lines.isEmpty()) return null
        return SofascoreNotificationParser.parse(homeTeam, awayTeam, lines)
            ?: buildRawFallback(homeTeam, awayTeam, lines.first())
    }

    /**
     * Pousse au widget un match par notif Sofascore actuellement active. Le tri/plafonnement à 4
     * (priorité aux matchs en cours, un match fini depuis plus de 5 minutes passe après) se fait
     * côté NowBarWidgetProvider (à la fois ici, à l'envoi, et à nouveau au rendu — voir son
     * sortedForWidget pour pourquoi aux deux endroits) : cette fonction se contente de tout
     * transmettre. Enveloppée dans un try/catch — le widget est un bonus au-dessus de la
     * complication montre, une erreur ici ne doit jamais faire planter ce service (même logique
     * que le try/catch autour de NowBarWidgetProvider.pushLive dans MirrorNotificationListener.mirror()).
     */
    private fun pushWidgetMatches(notifications: List<StatusBarNotification>) {
        try {
            // Images are lazy (AUDIT 23/09/2026): with many active matches (17 on a busy
            // evening), every Sofascore event used to extract every match's image, even though
            // at most SofascoreWidgetStore.MAX_SLOTS are kept, and those were already on disk.
            val matches = notifications.mapNotNull { sbn ->
                val match = toMatchResult(sbn) ?: return@mapNotNull null
                val (rawTitle, rawText) = rawTitleAndText(sbn, match)
                SofascoreWidgetMatch(
                    key = sbn.key,
                    homeTeam = match.homeTeam,
                    awayTeam = match.awayTeam,
                    homeScore = match.homeScore,
                    awayScore = match.awayScore,
                    lastScorer = match.lastScorer,
                    status = match.status,
                    postTimeMillis = sbn.postTime,
                    title = rawTitle,
                    text = rawText,
                    image = null,
                    imageLoader = { extractNotificationImage(sbn) },
                    contentIntent = sbn.notification.contentIntent,
                    actions = widgetActionsFor(sbn.notification)
                )
            }
            NowBarWidgetProvider.pushSofascoreMatches(applicationContext, matches)
        } catch (_: Throwable) {
            // Voir la doc de la fonction : le widget ne doit jamais faire tomber ce service.
        }
    }

    /**
     * Image combinée des deux logos telle que postée par Sofascore
     * lui-même dans sa notification — PAS l'icône de l'app Sofascore,
     * une image distincte que Sofascore compose déjà pour sa propre
     * notif (vue dans la capture d'écran fournie par Yann le
     * 15/09/2026). `null` si aucune des pistes n'aboutit.
     *
     * FUSIONNÉ lors du rapprochement avec Sport Watch Complication (voir
     * README.md, section "Fusion avec Sport Watch Complication") : cette
     * fonction déléguait auparavant à sa propre implémentation (getLargeIcon()
     * -> EXTRA_LARGE_ICON -> EXTRA_PICTURE, jamais confirmée nécessaire sur
     * appareil). Elle passe maintenant par [NotificationImageExtractor],
     * partagée avec [com.yann.nowbarmirror.MirrorNotificationListener] — même
     * ordre de pistes, plus la photo de contact MessagingStyle (sans effet
     * pour Sofascore, dont les notifs ne sont pas en MessagingStyle).
     */
    private fun extractNotificationImage(sbn: StatusBarNotification): Bitmap? =
        NotificationImageExtractor.extract(applicationContext, sbn)

    /**
     * Liste les matchs Sofascore actuellement dans le centre de
     * notifications, pour SportActivity — un par notif active. Vide si l'accès aux notifications n'est
     * pas accordé, ou si aucune notif Sofascore n'est active.
     */
    fun listAvailableMatches(): List<SofascoreMatchOption> {
        val notifications = activeSofascoreNotifications() ?: return emptyList()
        return notifications.mapNotNull { sbn ->
            val (homeTeam, awayTeam) = extractTeams(sbn) ?: return@mapNotNull null
            val latestLine = collectLines(sbn).firstOrNull().orEmpty()
            SofascoreMatchOption(
                key = sbn.key,
                homeTeam = homeTeam,
                awayTeam = awayTeam,
                latestLine = latestLine,
                notifImage = extractNotificationImage(sbn)
            )
        }
    }

    /** Notifs Sofascore actives, une par match — null si l'accès aux notifications n'est pas accordé. */
    private fun activeSofascoreNotifications(): List<StatusBarNotification>? = try {
        activeNotifications.filter { it.packageName == SOFASCORE_PACKAGE }
    } catch (e: Exception) {
        null
    }

    /**
     * "Real Madrid - Rayo Vallecano" -> domicile/extérieur. Split sur
     * " - " (espaces des deux côtés) et non sur tout tiret, pour ne pas
     * couper un nom d'équipe composé (ex. "Saint-Germain", sans espaces
     * autour de son tiret).
     *
     * AJOUTÉ le 20/09/2026 (demandé par Yann) : sport américain — Sofascore
     * présente le titre "Équipe 2 @ Équipe 1" plutôt que "Équipe 1 - Équipe
     * 2" (ex. "Phillies @ Nationals", capture fournie par Yann). Le "@" est
     * traité comme le tiret pour séparer les deux équipes, SANS inverser
     * l'ordre : le score des lignes d'événement ("Score : H - A", même
     * gabarit que [SofascoreNotificationParser.scoreEvent]) suit l'ordre
     * d'apparition dans le titre exactement comme pour les autres sports
     * (premier nom = premier nombre du score) — confirmé sur la capture
     * "Phillies @ Nationals" / "Match terminé : 3 - 6" / "Score : 3 - [6]
     * Nationals" (Phillies toujours associé au premier nombre, Nationals au
     * second). Testé AVANT le split " - " ci-dessous, un titre au format
     * "@" ne contenant jamais " - ".
     */
    private fun extractTeams(sbn: StatusBarNotification): Pair<String, String>? {
        val title = sbn.notification.extras
            .getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
            ?: return null
        if (title.contains("@")) {
            val teams = title.split("@").map { it.trim() }
            if (teams.size != 2 || teams.any { it.isEmpty() }) return null
            return teams[0] to teams[1]
        }
        val teams = title.split(" - ").map { it.trim() }
        if (teams.size != 2 || teams.any { it.isEmpty() }) return null
        return teams[0] to teams[1]
    }

    /**
     * Récupère les lignes d'UNE notif, DU PLUS RÉCENT AU PLUS ANCIEN.
     * Cas confirmé sur appareil (voir note en tête de fichier) : notif
     * unique mise à jour en place, `EXTRA_TEXT_LINES` déjà trié du plus
     * récent au plus ancien (pas besoin de le renverser). Repli sur
     * `EXTRA_TEXT` (une seule ligne) si `EXTRA_TEXT_LINES` est absent.
     */
    private fun collectLines(sbn: StatusBarNotification): List<String> {
        val extras = sbn.notification.extras
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (textLines != null && textLines.isNotEmpty()) {
            return textLines.map { it.toString() }
        }
        return extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?.let { listOf(it) }
            ?: emptyList()
    }

    /**
     * Repli neutre pour tout ce qu'on ne sait pas encore parser (sport
     * autre que foot/tennis, ou événement foot/tennis pas encore couvert
     * — voir SofascoreNotificationParser) : affiche le texte brut de la
     * notif la plus récente tel quel, sans essayer d'en déduire un score —
     * pour ne jamais afficher une donnée fausse. `homeScore`/`awayScore`
     * restent null, donc MatchResult.title retombe sur "Équipe vs Équipe"
     * et wear/MatchClock.kt affiche [rawLine] tel quel comme statut (aucune
     * des branches connues de MatchClock ne le reconnaît).
     */
    private fun buildRawFallback(homeTeam: String, awayTeam: String, rawLine: String) = MatchResult(
        homeTeam = homeTeam,
        awayTeam = awayTeam,
        homeScore = null,
        awayScore = null,
        status = rawLine
    )

    companion object {
        // Vérifié via la fiche Play Store de Sofascore (play.google.com,
        // id=com.sofascore.results) — à ne pas confondre avec
        // "eu.livesport.*", éditeur différent (Livesport s.r.o., Soccerway).
        // Pas privée : référencée aussi par NowBarWidgetProvider (icône Sofascore dans la colonne
        // de gauche + repli "ouvrir l'appli" côté widget, voir applySofascoreIcon/applySofascoreMatches).
        const val SOFASCORE_PACKAGE = "com.sofascore.results"

        // NEW 18/09/2026, "peek" feature — symmetric to MirrorNotificationListener's own
        // ACTION_DISMISS_WIDGET/EXTRA_DISMISS_KEY/EXTRA_DISMISS_POST_TIME, but routed through THIS
        // service, since it's the one with the notification-access grant covering Sofascore (see
        // README's "Two separate notification-access toggles"). See
        // NowBarWidgetProvider.sofascoreDismissPendingIntent for the sender side.
        const val ACTION_DISMISS_WIDGET = "com.yann.nowbarmirror.widget.ACTION_DISMISS_SOFASCORE"
        const val EXTRA_DISMISS_KEY = "mirror.widget.sofascore_dismiss_key"
        const val EXTRA_DISMISS_POST_TIME = "mirror.widget.sofascore_dismiss_post_time"

        // NEW 21/09/2026, fix for "silent" widget action buttons — symmetric to
        // MirrorNotificationListener's own ACTION_FIRE_WIDGET_ACTION/EXTRA_ACTION_*, routed through
        // THIS service for the same reason ACTION_DISMISS_WIDGET above is. See
        // NowBarWidgetProvider.actionFirePendingIntent for the sender side.
        const val ACTION_FIRE_WIDGET_ACTION = "com.yann.nowbarmirror.widget.ACTION_FIRE_ACTION_SOFASCORE"
        const val EXTRA_ACTION_KEY = "mirror.widget.sofascore_action_key"
        const val EXTRA_ACTION_POST_TIME = "mirror.widget.sofascore_action_post_time"
        const val EXTRA_ACTION_INDEX = "mirror.widget.sofascore_action_index"

        private var instance: SofascoreNotificationListenerService? = null

        // AUDIT 23/09/2026 — what was last sent to the watch on "/match" (see refresh()), so an
        // unchanged match isn't re-encoded and re-sent over Bluetooth. Process-lifetime only: the
        // first refresh after a restart always re-sends once (idempotent on the watch side).
        private const val WATCH_CLEARED = "cleared"
        private var lastWatchSignature: String? = null

        /**
         * Appelé quand Yann change le match suivi (SportActivity), pour
         * réafficher immédiatement le résultat sans attendre le prochain
         * événement Sofascore. Sans effet
         * si le service n'est pas encore connecté (accès aux notifications
         * pas encore accordé).
         */
        fun refreshIfConnected() {
            instance?.refresh()
        }

        /** Voir [SofascoreNotificationListenerService.listAvailableMatches] — liste vide si le service n'est pas connecté. */
        fun listAvailableMatchesIfConnected(): List<SofascoreMatchOption> =
            instance?.listAvailableMatches() ?: emptyList()
    }
}
