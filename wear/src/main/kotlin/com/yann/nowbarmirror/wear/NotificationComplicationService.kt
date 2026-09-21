package com.yann.nowbarmirror.wear

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataItemBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.TimeUnit

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
        val notification = NotificationInfoStore.current ?: fetchPersistedNotification()

        val data: ComplicationData = when (request.complicationType) {
            ComplicationType.SMALL_IMAGE -> buildSmallImage(notification)
            else -> NoDataComplicationData()
        }

        listener.onComplicationData(data)
    }

    /**
     * Repli quand NotificationInfoStore est vide (processus watch relancé depuis le dernier
     * envoi du téléphone — fréquent sur Wear OS, voir NotificationInfoStore.current). Au lieu
     * d'attendre passivement le prochain onDataChanged (qui ne se redéclenche PAS pour un
     * DataItem déjà synchronisé avant le redémarrage — seul un vrai changement le fait), va
     * relire directement le DataItem "/notification" déjà persistant côté Wear Data Layer API :
     * même logique que le rattrapage déjà en place côté téléphone
     * (MirrorNotificationListener.rebuildStateFromActiveNotifications, qui relit
     * activeNotifications() plutôt que d'attendre un nouvel événement) transposée côté montre —
     * Yann, 20/09/2026 : "comme pour le widget, il ne faut pas afficher que les dernières
     * notifications reçues depuis l'installation [...] c'est rare de se retrouver sans rien à
     * afficher".
     *
     * Appel bloquant, mais purement local (Play Services, pas de réseau — la Data Layer API
     * synchronise déjà en tâche de fond) : sans risque ici pour les quelques dizaines/centaines
     * de ms que ça prend, borné à [FETCH_TIMEOUT_SECONDS] par précaution. `null` si le DataItem
     * n'existe pas encore (aucune notif jamais envoyée), s'il indique "cleared", ou si l'appel
     * échoue/expire — repli identique à `NotificationInfoStore.current == null`.
     */
    private fun fetchPersistedNotification(): NotificationInfo? {
        return try {
            val items: DataItemBuffer = Tasks.await(
                Wearable.getDataClient(this).getDataItems(),
                FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS
            )
            try {
                val item = items.firstOrNull { it.uri.path == NOTIFICATION_PATH } ?: return null
                val dataMap = DataMapItem.fromDataItem(item).dataMap
                NotificationDataCodec.decode(this, dataMap)?.also { NotificationInfoStore.current = it }
            } finally {
                items.release()
            }
        } catch (e: Exception) {
            null
        }
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

        val icon = Icon.createWithBitmap(ComplicationImageComposer.composeNotificationImage(notification))

        return SmallImageComplicationData.Builder(
            smallImage = SmallImage.Builder(icon, SmallImageType.PHOTO).build(),
            contentDescription = PlainComplicationText.Builder(description).build()
        ).setTapAction(notificationDetailTapAction).build()
    }

    companion object {
        private const val NOTIFICATION_PATH = "/notification"
        private const val FETCH_TIMEOUT_SECONDS = 2L
    }
}
