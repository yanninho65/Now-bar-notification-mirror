package com.yann.nowbarmirror.wear

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest

/**
 * Fournit la dernière notification reçue (toutes apps mirorées confondues, voir NotificationInfo)
 * pour un emplacement SMALL_IMAGE rond — pensé pour le rond central du tableau de bord Samsung,
 * et volontairement une complication À PART de "Score en direct" (ScoreComplicationService) pour
 * que les deux puissent être assignées séparément (Yann, 20/09/2026 : "L'appeler Notification
 * pour la distinguer de score en direct"). Seul SMALL_IMAGE est supporté — voir
 * AndroidManifest.xml, SUPPORTED_TYPES (demande explicite : "complication small image format
 * cercle").
 *
 * Les données viennent de NotificationInfoStore, alimenté par NotificationDataListenerService
 * (Wear Data Layer API, chemin "/notification"). UPDATE_PERIOD_SECONDS=60 dans le manifest, même
 * raisonnement que ScoreComplicationService.
 *
 * NEW 21/09/2026 (Yann : "quand je clique sur la complication notification ça ouvre une fenêtre
 * [...]") : un tap ouvre désormais [NotificationDetailActivity], plutôt que d'essayer d'ouvrir un
 * hypothétique équivalent Wear OS de l'app source (contrairement à Score en direct, qui ouvre
 * Sofascore lui-même) — rien ne garantit qu'une app source quelconque en ait un installé sur la
 * montre, alors que la fenêtre de détail, elle, est toujours disponible.
 *
 * FIXED 21/09/2026 (Yann : "quand je clique sur la complication Notification, ça ne montre pas
 * toujours la notification qui est affichée sur la complication [...] ça reste bloqué sur une
 * ancienne et sans avoir l'exhaustivité (le match est terminé mais ça ne le montre pas dans la
 * fenêtre)") : [onComplicationRequest] faisait confiance à [NotificationInfoStore.current] dès
 * qu'il n'était pas `null`, sans jamais le revérifier contre l'item persistant de la Data Layer —
 * un `onDataChanged` raté par CE process (mise à jour arrivée pendant que le process était tué, ou
 * simplement pas encore redémarré) laissait ce cache en mémoire bloqué sur une valeur périmée
 * indéfiniment, jusqu'au prochain redémarrage complet du process. Cette même incohérence existait
 * séparément dans [NotificationDetailActivity], donc les deux pouvaient diverger l'un de l'autre
 * selon lequel avait eu la chance de recevoir la dernière mise à jour en direct. Chaque requête
 * relit désormais systématiquement l'item persistant (repli identique côté [NotificationDetailActivity]
 * — voir sa doc) : un appel purement local (Play Services, pas de réseau), donc sans coût
 * perceptible, qui garantit que les deux composants se resynchronisent sur la même vérité de
 * référence à chaque fois plutôt que de dériver chacun de leur côté. Ne retombe sur le cache mémoire
 * que si cette relecture locale échoue elle-même (cas rare).
 *
 * UPDATED 22/09/2026 : cette relecture elle-même pouvait, par une course avec une synchronisation
 * Data Layer pas encore terminée, renvoyer une version PLUS ANCIENNE que ce que [NotificationInfoStore]
 * avait déjà reçu en direct — voir PhoneDataLayer.readNotification et NotificationInfoStore.
 * updateIfNotOlder's doc pour le correctif (jamais de retour en arrière).
 */
class NotificationComplicationService : ComplicationDataSourceService() {

    private val notificationDetailTapAction: PendingIntent
        get() {
            val intent = Intent(this, NotificationDetailActivity::class.java)
            return PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener
    ) {
        // Re-read on every request (see the class doc, FIXED 21/09 / UPDATED 22/09/2026), now
        // through the shared PhoneDataLayer.readNotification (AUDIT 23/09/2026): path-specific
        // query instead of listing every DataItem, and no image decoding when the item hasn't
        // changed (NotificationDataCodec's `reuse`).
        val notification = when (val read = PhoneDataLayer.readNotification(this)) {
            is PhoneDataLayer.Read.Success -> read.value
            PhoneDataLayer.Read.Failed -> NotificationInfoStore.current
        }

        val data: ComplicationData = when (request.complicationType) {
            ComplicationType.SMALL_IMAGE -> buildSmallImage(notification)
            else -> NoDataComplicationData()
        }

        listener.onComplicationData(data)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        // Données d'exemple affichées dans le sélecteur de complications de la montre — un texte
        // volontairement long pour donner un aperçu réaliste de l'ajustement automatique du corps
        // du texte sur jusqu'à 3 lignes.
        val preview = NotificationInfo(
            title = "Nouveau message",
            text = "Exemple de texte de notification assez long pour vérifier l'ajustement automatique de la taille.",
            packageName = "",
            image = null,
            appIcon = null
        )
        return when (type) {
            ComplicationType.SMALL_IMAGE -> buildSmallImage(preview)
            else -> null
        }
    }

    private fun buildSmallImage(notification: NotificationInfo?): ComplicationData {
        val description = notification?.let {
            if (it.text.isNotBlank()) "${it.title} : ${it.text}" else it.title
        } ?: "Aucune notification"

        // Cached for real data (AUDIT 23/09/2026, see ComposedImageCache); a preview
        // (syncTimestamp 0, never from the phone) is always composed fresh.
        val bitmap = if (notification != null && notification.syncTimestamp == 0L) {
            ComplicationImageComposer.composeNotificationImage(notification)
        } else {
            smallImageCache.get(notification) { ComplicationImageComposer.composeNotificationImage(notification) }
        }
        val icon = Icon.createWithBitmap(bitmap)

        return SmallImageComplicationData.Builder(
            smallImage = SmallImage.Builder(icon, SmallImageType.PHOTO).build(),
            contentDescription = PlainComplicationText.Builder(description).build()
        ).setTapAction(notificationDetailTapAction).build()
    }

    companion object {
        private val smallImageCache = ComposedImageCache()
    }
}
