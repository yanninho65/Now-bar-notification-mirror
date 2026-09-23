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
    val kind: String = "",
    // AUDIT 23/09/2026 — see MatchScore.syncTimestamp.
    val syncTimestamp: Long = 0L
)

/**
 * Cache en mémoire de la dernière notification reçue — alimenté en direct par
 * PhoneDataListenerService ([FreshStore.setLive]) et par la relecture de l'item persistant
 * (PhoneDataLayer.readNotification, [FreshStore.updateIfNotOlder]), utilisée à chaque requête de
 * NotificationComplicationService et à l'ouverture de NotificationDetailActivity.
 *
 * Garde de fraîcheur (UPDATED 22/09/2026, puis AUDIT 23/09/2026) : une relecture de l'item
 * persistant peut courir contre une synchronisation Data Layer pas encore propagée et renvoyer une
 * version PLUS ANCIENNE que ce qui a déjà été reçu en direct (Yann : "quand je clique sur la
 * complication, ça m'ouvre la dernière notification que j'ai ouverte [...]") — elle n'est donc
 * appliquée que si elle n'est pas plus ancienne. La comparaison se fait maintenant sur l'horodatage
 * d'envoi du téléphone (commun aux deux complications, voir [FreshStore]) plutôt que sur
 * entryPostTimeMillis : ça ordonne aussi correctement un état "cleared", qui était auparavant
 * toujours accepté, même périmé.
 */
object NotificationInfoStore : FreshStore<NotificationInfo>()
