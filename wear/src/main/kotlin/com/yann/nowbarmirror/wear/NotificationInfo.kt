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
 */
data class NotificationInfo(
    val title: String,
    val text: String,
    val packageName: String,
    val image: Bitmap?,
    val appIcon: Bitmap?
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
