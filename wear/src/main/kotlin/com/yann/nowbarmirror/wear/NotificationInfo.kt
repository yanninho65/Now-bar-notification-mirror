package com.yann.nowbarmirror.wear

import android.graphics.Bitmap

/**
 * Dernière notification reçue côté téléphone, toutes apps mirorées confondues (ALL/LATEST et
 * Sofascore compris, aucune distinction) — miroir exact de ce qui alimente la vue "Dernière
 * notif" du widget écran de verrouillage côté téléphone (l'entrée la plus récente de
 * mobile/widget/WidgetAllNotificationsStore.kt, voir NowBarWidgetProvider.applyLatestContent),
 * envoyé à la montre via mobile/WatchNotificationSync.kt sur le chemin "/notification".
 *
 * [image] est l'image de la notif elle-même (grande icône/photo, voir mobile/
 * NotificationImageExtractor.kt) — `null` si l'app source n'en fournit pas. [appIcon] est l'icône
 * de l'app source elle-même, résolue côté téléphone (voir mobile/WatchNotificationSync.
 * appIconAsset) puisque l'app en question n'est pas forcément installée sur la montre. Voir
 * ComplicationImageComposer.composeNotificationImage pour comment les deux se combinent : badge
 * en bas à droite de [image] si les deux sont là, [appIcon] seul en repli si [image] est absente
 * (même règle que le widget côté téléphone, NowBarWidgetProvider.applyAllNotifSlotAsGeneric).
 *
 * NEW 21/09/2026, écran de détail (voir NotificationDetailActivity.kt) : [detailLines] est
 * l'historique déjà résolu côté téléphone (messages de la conversation / lignes d'événements du
 * match Sofascore — voir mobile/WatchNotificationSync.send's doc), vide pour une notification
 * générique sans historique particulier. [actionLabels] sont juste les LIBELLÉS des boutons
 * d'action (aucun PendingIntent ne peut traverser vers la montre) ; un tap sur le bouton N envoie
 * à [PhoneRelay] une demande "actionIndex=N" adressée par [entryKey]/[entryPostTimeMillis]/[kind]
 * — l'identité de CETTE entrée précise côté téléphone (voir mobile/NowBarWidgetProvider.fireAction/
 * dismissEntry), pour que le téléphone retrouve le vrai PendingIntent (ou déclenche la vraie
 * suppression) dans son propre cache en mémoire plutôt que d'avoir à faire deviner la montre.
 */
data class NotificationInfo(
    val title: String,
    val text: String,
    val packageName: String,
    val image: Bitmap?,
    val appIcon: Bitmap?,
    val detailLines: List<String> = emptyList(),
    val actionLabels: List<String> = emptyList(),
    val entryKey: String = "",
    val entryPostTimeMillis: Long = -1L,
    val kind: String = ""
)

/**
 * Cache en mémoire de la dernière notification reçue.
 *
 * ATTENTION : comme MatchScoreStore, ce cache est volatile — perdu si le système tue le processus
 * de l'app entre deux requêtes de complication. Si le processus watch redémarre, on revient à
 * `null` ("aucune notification") jusqu'à la prochaine mise à jour envoyée par le téléphone.
 *
 * Alimenté par NotificationDataListenerService, qui reçoit les mises à jour du téléphone via la
 * Wear Data Layer API.
 */
object NotificationInfoStore {

    @Volatile
    var current: NotificationInfo? = null

    /**
     * UPDATED 22/09/2026 (Yann : "quand je clique sur la complication, ça m'ouvre la dernière
     * notification que j'ai ouverte [...] je dois faire retour puis recliquer pour voir
     * l'actuelle [...] ça devrait se mettre à jour quand j'ouvre") — point d'écriture UNIQUE pour
     * [current], utilisé par NotificationComplicationService/NotificationDetailActivity chaque
     * fois qu'ils relisent l'item persistant "/notification" en repli (voir leur
     * `fetchPersistedNotification`'s doc). Ces deux relectures partaient du principe qu'une
     * lecture directe de la Data Layer est TOUJOURS au moins aussi fraîche que ce qui est déjà en
     * mémoire — vrai seulement juste après un redémarrage du process (le cas qu'elles visaient à
     * couvrir). Si une notification venait tout juste d'arriver et que [current] avait déjà la
     * bonne valeur EN DIRECT (NotificationDataListenerService.onDataChanged, qui décode
     * l'événement lui-même, sans round-trip), une relecture lancée en parallèle pouvait courir
     * contre une synchronisation Data Layer pas encore totalement propagée côté montre et
     * renvoyer une version PLUS ANCIENNE — écrasant silencieusement un affichage déjà correct par
     * du contenu périmé (exactement le symptôme de Yann : "revenir + recliquer" laissait le temps
     * à cette synchronisation de rattraper son retard avant la relecture suivante).
     *
     * Comparaison par [NotificationInfo.entryPostTimeMillis] : n'applique [candidate] que s'il
     * n'est pas plus ancien que [current] (jamais de retour en arrière). `null` (état "cleared"
     * explicite, voir mobile/WatchNotificationSync.sendCleared) est toujours appliqué — ce n'est
     * pas une notification concurrente plus ancienne, c'est un signal explicite qu'il n'y a plus
     * rien à montrer. Renvoie ce qui est effectivement retenu (le candidat s'il a été appliqué,
     * sinon ce qui était déjà là) : l'appelant peut afficher directement cette valeur sans avoir à
     * connaître cette garde.
     */
    @Synchronized
    fun updateIfNotOlder(candidate: NotificationInfo?): NotificationInfo? {
        val previous = current
        val shouldApply = candidate == null || previous == null ||
            candidate.entryPostTimeMillis >= previous.entryPostTimeMillis
        if (shouldApply) current = candidate
        return if (shouldApply) candidate else previous
    }
}
