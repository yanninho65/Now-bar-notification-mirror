package com.yann.nowbarmirror.wear

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
 * Pas d'action au tap pour l'instant (contrairement à Score en direct, qui ouvre Sofascore sur la
 * montre) : rien ne garantit qu'une app source quelconque ait un équivalent Wear OS installé sur
 * la montre — à revoir si besoin.
 */
class NotificationComplicationService : ComplicationDataSourceService() {

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener
    ) {
        val data: ComplicationData = when (request.complicationType) {
            ComplicationType.SMALL_IMAGE -> buildSmallImage(NotificationInfoStore.current)
            else -> NoDataComplicationData()
        }

        listener.onComplicationData(data)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        // Données d'exemple affichées dans le sélecteur de complications de la montre — un texte
        // volontairement long pour donner un aperçu réaliste de l'ajustement automatique du corps
        // du texte sur 2 lignes.
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
        ).build()
    }
}
