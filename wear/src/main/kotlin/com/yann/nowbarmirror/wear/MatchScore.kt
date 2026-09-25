package com.yann.nowbarmirror.wear

import android.graphics.Bitmap

/**
 * Représente l'état d'un match tel qu'affiché par la complication.
 *
 * [apiSource] est la valeur brute envoyée par le téléphone ("SPORTS_DB"
 * ou "LIVE_TENNIS", voir l'enum ApiSource dans mobile/Models.kt) —
 * MatchClock.kt s'en sert pour savoir quel vocabulaire de statut
 * interpréter et comment construire le libellé (un set en tennis n'a
 * pas d'équivalent en football).
 *
 * [homeScore] et [awayScore] sont nullables car un match pas encore
 * commencé n'a pas de score. En tennis, ils portent le nombre de SETS
 * gagnés par chaque joueur (pas de jeux ni de points).
 *
 * [currentSetHomeGames] / [currentSetAwayGames] portent le score de
 * JEUX du set en cours (tennis uniquement, null en football et null en
 * tennis tant qu'aucun set n'a commencé) — voir MatchClock.tennisLabel.
 *
 * [status] est la valeur brute renvoyée par la source concernée
 * ("Not Started", "1H", "2H", "Match Finished"... en football ;
 * "upcoming", "live", "completed", "cancelled"... en tennis), traduite
 * telle quelle par MatchClock.kt (aucune minute n'est plus calculée par
 * déduction). [kickoffEpochMillis] est l'horodatage du coup d'envoi
 * (UTC), affiché tel quel pour les matchs pas encore commencés
 * ("À venir · 20:00").
 *
 * [notifImage] est l'image combinée des deux logos telle que postée
 * directement par Sofascore dans sa propre notification (large icon) —
 * voir mobile/SofascoreNotificationListenerService.kt/extractNotificationImage.
 * Depuis la refonte du 16/09/2026, Sofascore est TOUJOURS la source des
 * noms d'équipe ET de cette image — il n'existe plus de repli "logos API"
 * séparés (homeLogo/awayLogo, retirés à cette date avec
 * WatchSync.downloadLogo, voir README) : `null` signifie simplement que
 * l'extraction de l'image a échoué côté téléphone pour cette notif — voir
 * ComplicationImageComposer.composeRoundImage pour le repli visuel dans
 * ce cas (une pastille générique, plus deux logos séparés).
 *
 * [lastScorer] ("home"/"away", ou null) indique quel côté vient de
 * marquer/gagner le dernier set — repris des crochets de la notif
 * Sofascore elle-même (voir mobile/SofascoreNotificationParser.kt), donc
 * toujours null pour un score venant de TheSportsDB/Live Tennis API (un
 * override actif sur le score efface ce champ côté téléphone — voir
 * mobile/SofascoreNotificationListenerService.applyOverride). Voir
 * [scoreText] pour l'affichage.
 */
data class MatchScore(
    val homeTeam: String,
    val awayTeam: String,
    val homeScore: Int?,
    val awayScore: Int?,
    val currentSetHomeGames: Int? = null,
    val currentSetAwayGames: Int? = null,
    val lastScorer: String? = null,
    val status: String,
    val kickoffEpochMillis: Long?,
    val notifImage: Bitmap? = null,
    val apiSource: String = "SPORTS_DB",
    // AUDIT 23/09/2026 — the phone's send timestamp for this value (DataMap "timestamp"): lets the
    // codec reuse this instance (and skip re-decoding its image) when the persisted item hasn't
    // changed. 0 for preview data.
    val syncTimestamp: Long = 0L,
    // 25/09/2026, Sport screen only (see mobile Models.MatchResult): long period label, kind of the
    // last event ("period"/"goal"/"other"), every goal / in-match missed penalty most recent first.
    val periodLabel: String? = null,
    val eventKind: String? = null,
    val goals: List<ScorerLine> = emptyList()
)

/**
 * One scorer line of the watch Sport screen (25/09/2026): [side] "home"/"away"/null (unknown →
 * centered), [minute] without "'" (blank when Sofascore gave none), [name] (blank when absent),
 * [missedPenalty] = "Penalty manqué" line rather than a goal.
 */
data class ScorerLine(val side: String?, val minute: String, val name: String, val missedPenalty: Boolean) {
    companion object {
        /** Phone "scorers" line "side\tminute\tname\tkind" (mobile SofascoreNotificationParser.goalsOf). */
        fun decode(line: String): ScorerLine {
            val parts = line.split('\t')
            return ScorerLine(
                side = parts.getOrNull(0)?.takeIf { it == "home" || it == "away" },
                minute = parts.getOrNull(1).orEmpty(),
                name = parts.getOrNull(2).orEmpty(),
                missedPenalty = parts.getOrNull(3) == "penmiss"
            )
        }

        /** Legacy "goals" line from an older phone build: "52' Name", no side. */
        fun legacy(line: String): ScorerLine {
            val minute = line.substringBefore("'", "").takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() || c == '+' } }
            return if (minute != null) ScorerLine(null, minute, line.substringAfter("'").trim(), false)
            else ScorerLine(null, "", line.trim(), false)
        }
    }
}

/**
 * "2-1" (ou "vs" si le score n'est pas encore connu) — entoure de
 * crochets le nombre de l'équipe désignée par [MatchScore.lastScorer]
 * ("home"/"away"), pour reprendre la même convention que les notifs
 * Sofascore elles-mêmes. `null` (score pas encore connu, ou origine du
 * dernier point non fournie par la source — ex. TheSportsDB/Live Tennis
 * API) -> pas de crochets. Utilisée par ScoreComplicationService.kt et
 * ComplicationImageComposer.kt, pour ne calculer ce texte qu'à un seul
 * endroit.
 */
fun MatchScore.scoreText(): String {
    if (homeScore == null || awayScore == null) return "vs"
    val home = if (lastScorer == "home") "[$homeScore]" else "$homeScore"
    val away = if (lastScorer == "away") "[$awayScore]" else "$awayScore"
    return "$home-$away"
}

/**
 * Cache en mémoire du dernier score reçu — alimenté en direct par PhoneDataListenerService
 * ([FreshStore.setLive]) et par la relecture de l'item persistant (PhoneDataLayer.readMatch,
 * [FreshStore.updateIfNotOlder]).
 *
 * AUDIT 23/09/2026 : même garde de fraîcheur que NotificationInfoStore (voir [FreshStore]) — avant,
 * ScoreComplicationService ne relisait l'item persistant que si ce cache était vide et l'écrasait
 * sans garde (voir le "Known issue" du README), d'où un affichage qui pouvait rester figé ou
 * régresser. Toujours volatile : perdu si le processus montre est tué, reconstruit par la relecture
 * suivante.
 */
object MatchScoreStore : FreshStore<MatchScore>()
