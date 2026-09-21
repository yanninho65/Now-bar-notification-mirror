package com.yann.nowbarmirror

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.yann.nowbarmirror.sport.WatchSync

/**
 * Envoie la "dernière notification" (toutes apps mirorées confondues, ALL/LATEST et Sofascore
 * compris, aucune distinction — même source que la vue "Dernière notif" du widget écran de
 * verrouillage : l'entrée la plus récente de widget/WidgetAllNotificationsStore.kt) à la montre
 * via la Wear Data Layer API, pour la complication SMALL_IMAGE ronde "Notification"
 * (wear/NotificationComplicationService.kt) demandée par Yann le 20/09/2026, pensée pour le rond
 * central du tableau de bord Samsung et volontairement distincte de "Score en direct" pour
 * pouvoir assigner les deux séparément.
 *
 * Chemin dédié "/notification", séparé de "/match" (sport/WatchSync.kt) — même principe que les
 * deux NotificationListenerServices déjà séparés côté téléphone (voir README, section "Deux
 * accès notifications séparés").
 *
 * MERGED 20/09/2026 (voir WidgetAllNotificationsStore's class doc) — [send]/[sendCleared] sont
 * maintenant appelés depuis NowBarWidgetProvider.syncWatchToLatest(), sur chaque reconstruction du
 * widget plutôt que depuis un point d'entrée dédié (l'ancien pushLive(), qui écrivait aussi dans
 * une WidgetNotificationStore séparée devenue obsolète) : couvre donc automatiquement tous les cas
 * qui font bouger l'entrée la plus récente de "Toutes notifs" — nouvelle notif, mise à jour en
 * place, promotion du survivant suivant après suppression ("revenir à la précédente", voir
 * LatestModePrefs), ou simplement une suppression qui libère la place pour une autre notif déjà
 * là — sans qu'un nouveau site de mutation puisse oublier d'appeler l'un ou l'autre.
 *
 * Réutilise [WatchSync.bitmapToAsset] (déjà utilisé pour notifImage côté Sport) plutôt que de
 * dupliquer la conversion Bitmap -> Asset.
 */
object WatchNotificationSync {

    private const val NOTIFICATION_PATH = "/notification"

    /**
     * [image] est l'image de la notif elle-même (voir NotificationImageExtractor, déjà extraite
     * par l'appelant). L'icône de l'app source est résolue ici ([appIconAsset]) à partir de
     * [packageName] et envoyée séparément ("appIcon") — la montre en a besoin pour le petit badge
     * en bas à droite de l'image (voir ComplicationImageComposer.composeNotificationImage), et ne
     * peut pas la résoudre elle-même puisque l'app source n'est pas forcément installée dessus.
     *
     * NEW 21/09/2026, écran de détail montre (voir NowBarWidgetProvider.AllNotifEntryPush.
     * detailLines' doc) :
     * - [detailLines] : messages de la conversation / lignes d'événements du match, déjà résolus
     *   côté téléphone (NowBarWidgetProvider.resolveAllNotifEntryContent) — vide pour une
     *   notification générique sans historique particulier.
     * - [actionLabels] : juste les libellés des boutons d'action (aucun PendingIntent ne peut
     *   traverser vers la montre) — un tap sur le bouton N envoie à [WearActionRelayService] une
     *   demande "actionIndex=N" pour ([entryKey], [entryPostTimeMillis]), que
     *   NowBarWidgetProvider.fireAction résout dans son propre cache en mémoire des VRAIS
     *   PendingIntents (jamais envoyés eux-mêmes, voir cette fonction).
     * - [entryKey]/[entryPostTimeMillis]/[kind] : identité de l'entrée, pour que la montre puisse
     *   adresser ses demandes d'action/suppression à CETTE notification précise (voir
     *   NowBarWidgetProvider.fireAction/dismissEntry).
     */
    fun send(
        context: Context,
        title: String,
        text: String,
        packageName: String,
        image: Bitmap?,
        detailLines: List<String> = emptyList(),
        actionLabels: List<String> = emptyList(),
        entryKey: String = "",
        entryPostTimeMillis: Long = -1L,
        kind: String = ""
    ) {
        val request = PutDataMapRequest.create(NOTIFICATION_PATH).apply {
            dataMap.putString("title", title)
            dataMap.putString("text", text)
            dataMap.putString("packageName", packageName)
            image?.let { dataMap.putAsset("notifImage", WatchSync.bitmapToAsset(it)) }
            appIconAsset(context, packageName)?.let { dataMap.putAsset("appIcon", it) }
            dataMap.putStringArrayList("detailLines", ArrayList(detailLines))
            dataMap.putStringArrayList("actionLabels", ArrayList(actionLabels))
            dataMap.putString("entryKey", entryKey)
            dataMap.putLong("entryPostTimeMillis", entryPostTimeMillis)
            dataMap.putString("kind", kind)
            // Force un DataChanged même si le contenu texte n'a pas bougé depuis le dernier envoi
            // (la Data Layer API ignore sinon un putDataItem identique au précédent) — même
            // raisonnement que WatchSync.sendMatch.
            dataMap.putLong("timestamp", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()

        Wearable.getDataClient(context).putDataItem(request)
    }

    /**
     * Signale à la montre qu'il n'y a plus de notification éligible à afficher — appelé par
     * NowBarWidgetProvider.syncWatchToLatest() quand WidgetAllNotificationsStore devient vide
     * (dernier miroir supprimé côté téléphone, "revenir à la précédente" désactivé ou sans
     * survivant, et plus aucune notif ALL restante non plus). La complication réaffiche alors son
     * état "aucune notification" au lieu de rester bloquée sur la dernière connue.
     */
    fun sendCleared(context: Context) {
        val request = PutDataMapRequest.create(NOTIFICATION_PATH).apply {
            dataMap.putBoolean("cleared", true)
            dataMap.putLong("timestamp", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()

        Wearable.getDataClient(context).putDataItem(request)
    }

    private fun appIconAsset(context: Context, packageName: String): Asset? {
        val drawable: Drawable = try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        } catch (_: Throwable) {
            return null
        }
        return WatchSync.bitmapToAsset(drawableToBitmap(drawable))
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
}
