package com.yann.nowbarmirror.wear

import android.app.PendingIntent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest

/**
 * Fournit les données de score pour les emplacements de complication de
 * la montre. Seuls LONG_TEXT (emplacement central, ex. Zenith) et
 * SMALL_IMAGE (rond du Dashboard Samsung) sont supportés — voir
 * AndroidManifest.xml, SUPPORTED_TYPES.
 *
 * SHORT_TEXT a été RETIRÉ ENTIÈREMENT le 16/09/2026 (demandé par Yann) :
 * voir le README, section juste avant "## État actuel", pour
 * l'historique complet des essais (icône jamais fiable sur le rond
 * Dashboard, blob de logos qui se chevauchaient sur le rectangle
 * central) qui ont mené à ce choix. SMALL_IMAGE couvre déjà tous les cas
 * où SHORT_TEXT était utilisé — voir buildSmallImage plus bas.
 *
 * Les données viennent de MatchScoreStore, alimenté par
 * MatchListenerService (Wear Data Layer API). UPDATE_PERIOD_SECONDS=60
 * dans le manifest fait que le système rappelle onComplicationRequest
 * environ chaque minute même sans nouvelle donnée du téléphone — utile
 * si jamais un rafraîchissement système est nécessaire, même si
 * MatchClock n'a plus de minute à recalculer par lui-même (voir
 * MatchClock.kt : le statut affiché vient tel quel de TheSportsDB en
 * football, de Live Tennis API en tennis).
 *
 * Un tap sur la complication ouvre l'app Sofascore SUR LA MONTRE (demandé
 * par Yann le 15/09/2026) — voir [sofascoreTapAction]. Nécessite que
 * Sofascore (ou son équivalent Wear OS) soit installé sur la montre elle
 * -même (distinct du téléphone) ; sans ça, le tap ne fait simplement rien
 * plutôt que planter (voir [sofascoreTapAction]). Nécessite aussi un
 * élément `<queries>` déclarant ce package dans AndroidManifest.xml —
 * requis à partir d'Android 11 pour que `getLaunchIntentForPackage` puisse
 * voir un package qui n'est pas le sien.
 */
class ScoreComplicationService : ComplicationDataSourceService() {

    // Suppose que l'app Sofascore sur la montre a le même nom de package
    // que sur le téléphone (vérifié côté téléphone via le Play Store, voir
    // mobile/SofascoreNotificationListenerService.kt) — à corriger si
    // jamais son équivalent Wear OS utilise un package différent.
    private val sofascoreTapAction: PendingIntent?
        get() {
            val launchIntent = packageManager.getLaunchIntentForPackage(SOFASCORE_PACKAGE) ?: return null
            return PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

    companion object {
        private const val SOFASCORE_PACKAGE = "com.sofascore.results"
    }

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener
    ) {
        val match = MatchScoreStore.current

        val data: ComplicationData = when (request.complicationType) {
            ComplicationType.LONG_TEXT -> buildLongText(match)
            ComplicationType.SMALL_IMAGE -> buildSmallImage(match)
            else -> NoDataComplicationData()
        }

        listener.onComplicationData(data)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        // Données d'exemple affichées dans le sélecteur de complications de
        // la montre, pour que Yann voie un aperçu avant d'avoir un vrai
        // match en cours.
        val preview = MatchScore(
            homeTeam = "PSG",
            awayTeam = "OM",
            homeScore = 2,
            awayScore = 1,
            status = "1H",
            kickoffEpochMillis = System.currentTimeMillis() - 20 * 60_000L
        )
        return when (type) {
            ComplicationType.LONG_TEXT -> buildLongText(preview)
            ComplicationType.SMALL_IMAGE -> buildSmallImage(preview)
            else -> null
        }
    }

    private fun buildLongText(match: MatchScore?): ComplicationData {
        if (match == null) {
            val placeholder = PlainComplicationText.Builder("Aucun match").build()
            return LongTextComplicationData.Builder(
                text = placeholder,
                contentDescription = PlainComplicationText.Builder(
                    "Aucun match sélectionné"
                ).build()
            ).setTapAction(sofascoreTapAction).build()
        }

        // Indication de temps (P1/MT/P2/Fin...) en premier, puis
        // équipes/score — demandé par Yann le 15/09/2026 (auparavant
        // l'ordre inverse : "PSG 2-1 OM · 1ère MT"). Si MatchClock.label
        // renvoie une chaîne vide (statut inconnu, voir MatchClock.kt),
        // on omet le "· " plutôt que d'afficher un séparateur seul devant
        // rien.
        val statusLabel = MatchClock.label(match)
        val scoreText = match.scoreText()
        val text = if (statusLabel.isBlank()) {
            "${match.homeTeam} $scoreText ${match.awayTeam}"
        } else {
            "$statusLabel · ${match.homeTeam} $scoreText ${match.awayTeam}"
        }

        return LongTextComplicationData.Builder(
            text = PlainComplicationText.Builder(text).build(),
            contentDescription = PlainComplicationText.Builder(text).build()
        ).setTapAction(sofascoreTapAction).build()
    }

    /**
     * RÉINTRODUIT le 15/09/2026 spécifiquement pour le rond du Dashboard
     * Samsung — voir le README, section "SMALL_IMAGE réintroduit pour le
     * rond Dashboard", pour l'historique complet des essais qui ont mené
     * ici, "SMALL_IMAGE : fond plein + tout sur une ligne" pour le
     * correctif qui a suivi, et "SMALL_IMAGE : disposition verticale
     * (image en haut, score au milieu, période en bas)" pour la revue du
     * 16/09/2026. Une seule image (logos/notifImage, score ET période,
     * "cuits" dans le bitmap par
     * `ComplicationImageComposer.composeRoundImage`, empilés
     * verticalement) — pas de champ texte séparé, ce type n'en fournit
     * pas.
     *
     * Icône source : [MatchScore.notifImage] (image de la notif
     * Sofascore, ratio d'origine conservé) si disponible, sinon les deux
     * logos API — voir composeRoundImage pour le détail de la
     * disposition.
     *
     * Depuis le retrait de SHORT_TEXT (16/09/2026), c'est le SEUL format
     * avec icône/image de ce projet.
     */
    private fun buildSmallImage(match: MatchScore?): ComplicationData {
        val scoreText = match?.scoreText() ?: "vs"

        val description = match?.let {
            "${it.homeTeam} $scoreText ${it.awayTeam}"
        } ?: "Aucun match sélectionné"

        val icon = Icon.createWithBitmap(ComplicationImageComposer.composeRoundImage(match))

        return SmallImageComplicationData.Builder(
            smallImage = SmallImage.Builder(icon, SmallImageType.PHOTO).build(),
            contentDescription = PlainComplicationText.Builder(description).build()
        ).setTapAction(sofascoreTapAction).build()
    }
}
