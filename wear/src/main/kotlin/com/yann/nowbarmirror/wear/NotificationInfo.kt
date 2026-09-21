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
}
