package com.yann.nowbarmirror.sport

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.wearable.Asset
import com.yann.nowbarmirror.BitmapUtils
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.io.ByteArrayOutputStream

/**
 * Centralise l'envoi de données vers la montre via la Wear Data Layer
 * API (chemin "/match"). Seul appelant depuis le 16/09/2026 :
 * SofascoreNotificationListenerService (ApiOverrideFollowService ne fait
 * que sonder l'API et alimenter ApiOverrideCache — c'est
 * SofascoreNotificationListenerService.refresh() qui envoie le résultat
 * fusionné à la montre, voir README).
 *
 * [downloadLogo] (téléchargement d'un logo d'équipe TheSportsDB) a été
 * retiré à cette date, tout comme les paramètres homeLogo/awayLogo de
 * [sendMatch] : Sofascore fournit maintenant TOUJOURS l'image envoyée à
 * la montre (voir [bitmapToAsset]/[notifImage]) — plus aucun appelant
 * n'avait besoin de logos séparés téléchargés depuis une API.
 */
object WatchSync {

    private const val MATCH_PATH = "/match"

    /**
     * Convertit un Bitmap quelconque en Asset — utilisé pour
     * [notifImage], l'image combinée extraite d'une notif Sofascore (voir
     * SofascoreNotificationListenerService.kt/extractNotificationImage).
     */
    fun bitmapToAsset(bitmap: Bitmap): Asset {
        val stream = ByteArrayOutputStream()
        // Downscaled first (AUDIT 23/09/2026 — see BitmapUtils.MAX_STORED_IMAGE_PX): shared by
        // "/match" and "/notification", so neither ever sends a full-size image over Bluetooth
        // for a complication composed at 320 px.
        BitmapUtils.downscale(bitmap).compress(Bitmap.CompressFormat.PNG, 100, stream)
        return Asset.createFromBytes(stream.toByteArray())
    }

    /**
     * [notifImage] : image combinée des deux logos telle que fournie par
     * la notif Sofascore elle-même — voir MatchScore.kt (côté montre) et
     * ComplicationImageComposer.composeRoundImage pour le repli (une
     * pastille générique) quand elle est `null`.
     */
    fun sendMatch(
        context: Context,
        match: MatchResult,
        notifImage: Asset? = null
    ) {
        val request = PutDataMapRequest.create(MATCH_PATH).apply {
            dataMap.putString("apiSource", match.source.name)
            dataMap.putString("homeTeam", match.homeTeam)
            dataMap.putString("awayTeam", match.awayTeam)
            dataMap.putString("homeScore", match.homeScore ?: "")
            dataMap.putString("awayScore", match.awayScore ?: "")
            match.currentSetHomeGames?.let { dataMap.putInt("currentSetHomeGames", it) }
            match.currentSetAwayGames?.let { dataMap.putInt("currentSetAwayGames", it) }
            match.lastScorer?.let { dataMap.putString("lastScorer", it) }
            dataMap.putString("status", match.status)
            match.kickoffEpochMillis?.let { dataMap.putLong("kickoffEpochMillis", it) }
            notifImage?.let { dataMap.putAsset("notifImage", it) }
            // Force un DataChanged même si le contenu texte n'a pas bougé
            // depuis le dernier envoi (la Data Layer API ignore sinon un
            // putDataItem identique au précédent).
            dataMap.putLong("timestamp", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()

        Wearable.getDataClient(context).putDataItem(request)
    }

    /**
     * Signale à la montre qu'aucun match n'est plus suivi (plus aucune
     * notification Sofascore active) — remet la complication à "Aucun
     * match" au lieu de la laisser figée sur le dernier score connu. Voir
     * MatchListenerService.kt côté montre pour la réception.
     */
    fun sendCleared(context: Context) {
        val request = PutDataMapRequest.create(MATCH_PATH).apply {
            dataMap.putBoolean("cleared", true)
            dataMap.putLong("timestamp", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()

        Wearable.getDataClient(context).putDataItem(request)
    }
}
