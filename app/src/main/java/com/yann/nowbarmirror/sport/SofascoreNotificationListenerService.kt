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
 * (MainActivity). [notifImage] est le résultat de
 * [SofascoreNotificationListenerService.extractNotificationImage] pour
 * CETTE notif précise — la même fonction, donc la même image, que celle
 * envoyée à la montre par [SofascoreNotificationListenerService.refresh] —
 * affiché en vignette dans la liste pour vérifier visuellement ce qui est
 * réellement extrait avant de s'y fier côté montre. `null` si aucune des
 * pistes de [extractNotificationImage] n'aboutit pour cette notif.
 * [override] : l'API de score/période éventuellement configurée pour CETTE
 * notif précise (voir SofascoreApiOverridePrefs) — `null` si aucune, auquel
 * cas le score/la période affichés restent ceux déduits du texte de la
 * notif elle-même.
 */
data class SofascoreMatchOption(
    val key: String,
    val homeTeam: String,
    val awayTeam: String,
    val latestLine: String,
    val notifImage: Bitmap?,
    val override: SofascoreApiOverride?
)

/**
 * RÔLE REDÉFINI le 16/09/2026 (demandé par Yann — voir README, section de
 * cette date) : Sofascore est maintenant TOUJOURS la base de l'application.
 * Ce service relit les notifications de l'app Sofascore
 * (`com.sofascore.results`, vérifié via sa fiche Play Store — pas à
 * confondre avec les apps "Livesport"/Soccerway, éditeur différent) et
 * pousse à la montre, pour la notification ACTIVE (voir ci-dessous), les
 * noms d'équipe + l'image telles que Sofascore les fournit, et un score/
 * statut qui est soit déduit du texte de la notif, soit — si Yann a
 * configuré un override pour CETTE notif précise (voir
 * [SofascoreApiOverridePrefs]) — repris du dernier sondage de l'API choisie
 * (TheSportsDB ou Live Tennis API, voir [ApiOverrideFollowService]), champ
 * par champ (score et/ou période). AVANT cette date, TheSportsDB/Live
 * Tennis API pouvaient aussi être suivis de façon totalement autonome
 * (MainActivity + MatchFollowService, tous deux retirés) avec leurs propres
 * noms d'équipe et logos envoyés à la montre — ce n'est plus possible :
 * l'API ne sert plus qu'à affiner score/période d'un match dont le nom/
 * l'image viennent toujours de Sofascore.
 *
 * Notification ACTIVE (celle qui pilote la complication) : choisie par
 * Yann dans l'app et persistée dans [SofascorePrefs] :
 * - LATEST (par défaut) : la notif la plus récemment mise à jour parmi
 *   toutes les notifs Sofascore actives.
 * - CHOSEN : un match précis, choisi à la main parmi une liste de ceux
 *   actuellement dans le centre de notifications (voir [listAvailableMatches],
 *   appelé depuis MainActivity). Si ce match n'a plus de notif active (fini,
 *   notif supprimée), on retombe automatiquement sur LATEST plutôt que de ne
 *   rien afficher.
 *
 * DEPUIS l'ajout de la vue "Sport" au widget lock-screen (voir
 * [pushWidgetMatches]) : ce service alimente maintenant DEUX surfaces à
 * chaque [refresh] — la complication montre (un seul match "actif", comme
 * ci-dessus, override compris) ET le widget (jusqu'à 4 matchs, TOUS ceux
 * actuellement actifs, SANS override — voir la doc de [pushWidgetMatches]
 * pour pourquoi). Les deux partagent la même extraction de base
 * ([toMatchResult]), qui ne fait que du parsing, sans se soucier de la
 * notion de notif "active".
 *
 * Nécessite que Yann accorde l'accès aux notifications à cette app
 * (permission spéciale, non demandable au runtime contrairement à
 * POST_NOTIFICATIONS — voir le bouton dédié dans MainActivity qui ouvre
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
        refresh()
        bootstrapAllNotificationsHistory()
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
        return super.onStartCommand(intent, flags, startId)
    }

    /** Cancels the Sofascore notification identified by [key], then closes/refreshes the widget exactly like a normal removal would (see onNotificationRemoved) — a peek's dismiss button never waits for the async onNotificationRemoved round-trip for its own instant feedback. */
    private fun dismiss(key: String, postTimeMillis: Long) {
        try {
            cancelNotification(key)
        } catch (_: Throwable) {
            activeNotifications?.firstOrNull { it.key == key }?.let { cancelNotification(it.key) }
        }
        SofascoreApiOverridePrefs.remove(applicationContext, key)
        refresh()
        removeFromAllNotificationsHistory(key, postTimeMillis)
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
            refresh()
            pushToAllNotificationsHistory(sbn)
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
                apiSource = match.source.name,
                image = extractNotificationImage(sbn),
                contentIntent = sbn.notification.contentIntent,
                actions = widgetActionsFor(sbn.notification)
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
            ?.mapNotNull { action ->
                val pi = action.actionIntent ?: return@mapNotNull null
                val label = action.title?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                WidgetAction(label = label, pendingIntent = pi)
            }
            ?: emptyList()
    }

    /**
     * CORRIGÉ/ÉTENDU le 16/09/2026 (demandé par Yann) : une notif
     * supprimée doit aussi faire disparaître l'override éventuellement
     * configuré pour elle (voir [SofascoreApiOverridePrefs]) — sans quoi un
     * override resterait indéfiniment stocké pour une clé de notification
     * qui n'existera jamais plus (Android ne réutilise pas les clés d'une
     * notif à l'autre). [refresh] recalcule ensuite la notif active parmi
     * celles qui restent, ce qui arrête au passage le sondage
     * ApiOverrideFollowService si c'est l'override qui vient d'être
     * supprimé qui était actif.
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == SOFASCORE_PACKAGE) {
            SofascoreApiOverridePrefs.remove(applicationContext, sbn.key)
            refresh()
            removeFromAllNotificationsHistory(sbn.key, sbn.postTime)
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
     * celle qui est active (voir doc de classe), lui superpose l'override
     * éventuellement configuré (voir [applyOverride]), aligne le sondage
     * en tâche de fond sur cet override (voir [ApiOverrideFollowService.sync])
     * et pousse le résultat à la montre — PUIS pousse aussi le widget (voir
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
            WatchSync.sendCleared(this)
            ApiOverrideFollowService.sync(this, null, null)
            pushWidgetMatches(emptyList())
            return
        }

        // Si un match précis a été choisi ET qu'il a encore une notif
        // active, on le suit ; sinon (mode "dernière", ou match choisi
        // terminé/supprimé) on retombe sur la notif la plus récente.
        val chosenKey = SofascorePrefs.loadChosenKey(this)
            ?.takeIf { SofascorePrefs.loadMode(this) == SofascorePrefs.Mode.CHOSEN }
        val target = (chosenKey?.let { key -> notifications.find { it.key == key } })
            ?: notifications.maxByOrNull { it.postTime }
            ?: return

        val baseMatch = toMatchResult(target) ?: return

        val override = SofascoreApiOverridePrefs.get(this, target.key)
        ApiOverrideFollowService.sync(this, target.key, override)
        val match = applyOverride(baseMatch, override)

        // Image combinée des deux logos telle que postée par Sofascore
        // lui-même — voir [extractNotificationImage]. Les noms d'équipe
        // (homeTeam/awayTeam ci-dessus) et cette image sont TOUJOURS ceux
        // de Sofascore, même quand un override API est actif : seuls
        // score/statut peuvent venir de l'API (voir [applyOverride]).
        val targetImage = extractNotificationImage(target)
        WatchSync.sendMatch(this, match, notifImage = targetImage?.let { WatchSync.bitmapToAsset(it) })

        pushWidgetMatches(notifications)
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
     * Pousse jusqu'à 4 matchs au widget — un par notif Sofascore actuellement active, SANS
     * l'override API éventuellement configuré (contrairement à la montre, voir [applyOverride]) :
     * le système d'override ne suit qu'UN SEUL match (celui actif pour la montre) et n'est pas
     * construit pour en suivre plusieurs à la fois, donc le widget affiche toujours le score/la
     * période tels que la notif Sofascore elle-même les donne — exactement comme le fait déjà
     * aujourd'hui tout match qui N'EST PAS le match actif de la montre. Le tri/plafonnement à 4
     * (priorité aux matchs en cours, un match fini depuis plus de 5 minutes passe après) se fait
     * côté NowBarWidgetProvider (à la fois ici, à l'envoi, et à nouveau au rendu — voir son
     * sortedForWidget pour pourquoi aux deux endroits) : cette fonction se contente de tout
     * transmettre. Enveloppée dans un try/catch — le widget est un bonus au-dessus de la
     * complication montre, une erreur ici ne doit jamais faire planter ce service (même logique
     * que le try/catch autour de NowBarWidgetProvider.pushLive dans MirrorNotificationListener.mirror()).
     */
    private fun pushWidgetMatches(notifications: List<StatusBarNotification>) {
        try {
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
                    apiSource = match.source.name,
                    postTimeMillis = sbn.postTime,
                    title = rawTitle,
                    text = rawText,
                    image = extractNotificationImage(sbn),
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
     * Superpose au match issu du parsing Sofascore ([base]) le score et/ou
     * la période venant de l'API choisie pour CETTE notification
     * ([override]), si elle est configurée ET qu'ApiOverrideFollowService a
     * déjà obtenu un premier résultat (voir [ApiOverrideCache] — sinon on
     * retombe sur [base] le temps du premier sondage, plutôt que d'afficher
     * "vs"/un statut vide). Les noms d'équipe et l'image restent TOUJOURS
     * ceux de Sofascore : ni [base] ni le résultat API n'y touchent, voir
     * [refresh].
     *
     * [MatchResult.status] est déjà, pour [base] comme pour un résultat
     * TheSportsDB/Live Tennis API, exprimé dans le vocabulaire brut que
     * wear/MatchClock.kt sait traduire SELON [MatchResult.source] (voir
     * SofascoreNotificationParser, doc de [SofascoreNotificationParser.parse])
     * — remplacer status ET source ensemble quand [SofascoreApiOverride.showPeriod]
     * est vrai suffit donc à faire traduire ce nouveau statut avec le bon
     * vocabulaire côté montre, sans rien changer là-bas.
     */
    private fun applyOverride(base: MatchResult, override: SofascoreApiOverride?): MatchResult {
        if (override == null) return base
        val apiMatch = ApiOverrideCache.get(override) ?: return base
        return base.copy(
            homeScore = if (override.showScore) apiMatch.homeScore else base.homeScore,
            awayScore = if (override.showScore) apiMatch.awayScore else base.awayScore,
            currentSetHomeGames = if (override.showScore) apiMatch.currentSetHomeGames else base.currentSetHomeGames,
            currentSetAwayGames = if (override.showScore) apiMatch.currentSetAwayGames else base.currentSetAwayGames,
            // L'API ne fournit jamais l'info "qui vient de marquer"
            // (crochets, voir MatchResult.lastScorer) — on l'efface dès que
            // le score affiché n'est plus celui de Sofascore, pour ne pas
            // laisser un crochet Sofascore obsolète sur un score API.
            lastScorer = if (override.showScore) null else base.lastScorer,
            status = if (override.showPeriod) apiMatch.status else base.status,
            source = if (override.showPeriod) apiMatch.source else base.source
        )
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
     * notifications, pour l'écran d'accueil de MainActivity — un par notif
     * active, avec l'override éventuellement configuré pour chacune (voir
     * [SofascoreApiOverridePrefs]). Vide si l'accès aux notifications n'est
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
                notifImage = extractNotificationImage(sbn),
                override = SofascoreApiOverridePrefs.get(applicationContext, sbn.key)
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
        id = "sofascore_fallback_raw",
        source = ApiSource.SPORTS_DB,
        homeTeam = homeTeam,
        awayTeam = awayTeam,
        homeScore = null,
        awayScore = null,
        date = SportsDbApi.todayUtcDateString(),
        time = null,
        status = rawLine,
        league = "Sofascore",
        kickoffEpochMillis = null
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

        private var instance: SofascoreNotificationListenerService? = null

        /**
         * Appelé quand Yann change son choix de repli, sauvegarde/retire un
         * override (MainActivity), ou quand ApiOverrideFollowService obtient
         * un nouveau résultat de sondage, pour réafficher immédiatement le
         * résultat sans attendre le prochain événement Sofascore. Sans effet
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
