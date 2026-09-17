package com.yann.nowbarmirror.sport

/**
 * Quelle API interroger pour un override de score/période (voir
 * SofascoreApiOverridePrefs, ApiOverrideFollowService, LiveTennisApi.kt).
 * Ne dit PAS quel sport précis a été cherché — TheSportsDB couvre
 * plusieurs sports (foot, basket, baseball...) sous ce même SPORTS_DB,
 * sans filtre par sport côté recherche (voir SportsDbApi.kt).
 */
enum class ApiSource { SPORTS_DB, LIVE_TENNIS }

data class TeamResult(
    val id: String,
    val name: String
)

/** Résultat d'une recherche par joueur — [teamId]/[teamName] sont son équipe actuelle (peut être null). */
data class PlayerResult(
    val id: String,
    val name: String,
    val teamId: String?,
    val teamName: String?
)

/** Résultat d'une recherche de joueur de tennis (Live Tennis API) — id numérique côté API, contrairement aux ids texte de TheSportsDB. */
data class TennisPlayerResult(
    val id: Int,
    val name: String,
    val tour: String?,
    val ranking: Int?,
    val country: String?
)

/** Résultat d'une recherche par ligue (ex. "French Ligue 1", id "4334"). */
data class LeagueResult(
    val id: String,
    val name: String,
    val sport: String
)

/**
 * Un match trouvé via TheSportsDB/Live Tennis API — sert UNIQUEMENT à
 * sonder un score/période plus précis pour un override lié à une
 * notification Sofascore (voir SofascoreApiOverridePrefs,
 * ApiOverrideFollowService), depuis la refonte du 16/09/2026 : Sofascore
 * est maintenant TOUJOURS la source du nom des équipes et de l'image
 * envoyés à la montre (voir README). Ce fichier avait auparavant des
 * champs idHomeTeam/idAwayTeam (ids d'équipe TheSportsDB) pour
 * télécharger les logos et un nom à afficher directement sur la montre —
 * retirés à cette date en même temps que WatchSync.downloadLogo, devenus
 * inutiles.
 */
data class MatchResult(
    val id: String,
    /** SPORTS_DB par défaut pour rester compatible avec le code existant — voir [ApiSource]. */
    val source: ApiSource = ApiSource.SPORTS_DB,
    val homeTeam: String,
    val awayTeam: String,
    val homeScore: String?,
    val awayScore: String?,
    /**
     * Score de JEUX du set en cours (tennis uniquement) — null en football,
     * et null en tennis tant qu'aucun set n'a commencé. À ne pas confondre
     * avec homeScore/awayScore ci-dessus, qui portent le nombre de SETS
     * gagnés. Alimenté par LiveTennisApi.parseMatch (dernier élément du
     * tableau `games` de l'API).
     */
    val currentSetHomeGames: Int? = null,
    val currentSetAwayGames: Int? = null,
    /**
     * "home"/"away" si on sait quel côté vient de marquer/gagner le
     * dernier set, null sinon — repris des crochets de la notif Sofascore
     * elle-même (voir SofascoreNotificationParser.bracketedSide), donc
     * toujours null pour un match trouvé via TheSportsDB/Live Tennis API.
     */
    val lastScorer: String? = null,
    val date: String,
    val time: String?,
    val status: String,
    val league: String,
    /** Horodatage du coup d'envoi (UTC, epoch ms), ou null si non calculable. */
    val kickoffEpochMillis: Long?
) {
    /** Ex. "PSG 2-1 OM" si le score est connu, sinon "PSG vs OM". */
    val title: String
        get() = if (homeScore != null && awayScore != null) {
            "$homeTeam $homeScore-$awayScore $awayTeam"
        } else {
            "$homeTeam vs $awayTeam"
        }

    val details: String
        get() = "$league · $date${time?.let { " $it" } ?: ""} · $status"

    /**
     * Un match dans cet état n'a plus besoin d'être sondé (arrêt du polling
     * d'ApiOverrideFollowService). "completed" couvre le statut brut de Live
     * Tennis API (tennis) ; "Cancelled" couvre déjà celui de TheSportsDB ET
     * de Live Tennis API, qui utilisent tous deux ce mot (voir ApiSource,
     * LiveTennisApi.kt).
     */
    val isFinished: Boolean
        get() = status.contains("Finished", ignoreCase = true) ||
            status.equals("FT", ignoreCase = true) ||
            status.contains("Postponed", ignoreCase = true) ||
            status.contains("Cancelled", ignoreCase = true) ||
            status.contains("Abandoned", ignoreCase = true) ||
            status.equals("completed", ignoreCase = true)
}
