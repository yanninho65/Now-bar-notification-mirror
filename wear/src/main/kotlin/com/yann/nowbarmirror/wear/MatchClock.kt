package com.yann.nowbarmirror.wear

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Traduit le statut brut renvoyé par l'API du sport concerné en un
 * libellé court pour la complication — SANS calculer de minute de jeu
 * par déduction. Deux vocabulaires possibles selon `match.apiSource` :
 * celui de TheSportsDB (branche par défaut, `label`, tous sports hors
 * tennis) et celui de Live Tennis API pour le tennis (`tennisLabel`), qui
 * inclut en plus le score du set en cours quand il est connu
 * (`liveSetLabel`).
 *
 * Pourquoi pas de minute chiffrée en football : le plan gratuit de
 * TheSportsDB ne fournit une minute de jeu en direct (champ
 * `strProgress`, format "mm:ss - 1st/2nd...") que via l'API Livescores
 * V2, réservée aux abonnés Premium (9$/mois — voir
 * https://www.thesportsdb.com/pricing). `lookupevent.php` (utilisé ici,
 * plan gratuit) ne renvoie qu'un statut texte, sans minute. Ce fichier
 * se contente donc de traduire ce texte en français, sans y ajouter le
 * moindre calcul basé sur l'heure du coup d'envoi. EXCEPTION : le repli
 * Sofascore (mobile/SofascoreNotificationParser.kt) pousse directement
 * "MM+" (ex. "37+") comme statut pour un but horodaté — ce n'est pas
 * calculé ici, c'est déjà la minute donnée par la notif Sofascore
 * elle-même, affichée telle quelle via la branche `else` ci-dessous
 * (aucune règle dédiée nécessaire, "37+" ne correspond à aucun des
 * statuts connus de TheSportsDB).
 *
 * Vocabulaire unifié "P<N>"/"MT"/"Fin", plutôt que de coller aux codes
 * bruts par sport — voir le détail par sport ci-dessous
 * (https://www.thesportsdb.com/docs_api_data, confirmé le 15/09/2026) :
 * - Foot, rugby, handball (2 mi-temps : codes `1H`/`HT`/`2H`/`FT`
 *   identiques dans les trois sports) -> P1 / MT / P2 / Fin
 * - Basket, football américain (4 quarts-temps : `Q1`/`Q2`/`HT`/`Q3`/`Q4`/
 *   `FT`, MT après le 2e quart-temps comme au foot) -> P1 / P2 / MT / P3 /
 *   P4 / Fin
 * - Hockey sur glace (`P1`/`P2`/`P3` déjà tels quels dans l'API — aucune
 *   règle dédiée nécessaire, passent déjà tels quels par la branche
 *   `else` ci-dessous, non demandé par Yann mais couvert par cohérence)
 * - Tennis (sets à décompte NON cumulatif, score = jeux du set qui vient
 *   de finir) : PAS d'indicateur de période affiché en cours de match —
 *   le score en sets suffit. Seul `completed` (`tennisLabel`) est
 *   traduit, en "Fin".
 * - Tennis de table, volley (sets à décompte CUMULATIF — repli
 *   Sofascore uniquement, voir mobile/SofascoreNotificationParser.kt) :
 *   affiche `S<N>` (N = numéro du set EN COURS, déjà calculé côté
 *   téléphone) — déjà le format brut utilisé par TheSportsDB pour le
 *   volley (`S1`..`S5` ci-dessous), donc affiché tel quel via la
 *   branche `else`, sans règle dédiée. `Fin` à la fin, comme les autres
 *   sports.
 *
 * Statut vide (`""`, jamais un statut manquant dans TheSportsDB/Live
 * Tennis API elles-mêmes, mais possible côté repli Sofascore — ex. un
 * but sans minute donnée par la notif, voir mobile/
 * SofascoreNotificationParser.kt/goalNoMinute) -> chaîne vide plutôt que
 * d'inventer une période : wear/ScoreComplicationService.kt omet alors
 * le "· " devant le score plutôt que d'afficher un statut vide ou "?".
 *
 * Foot, prolongation/tirs au but (repli Sofascore uniquement, AJOUTÉ le
 * 20/09/2026 — voir mobile/SofascoreNotificationParser.kt/parse pour les
 * exemples réels confirmés) : "ET1"/"MTP"/"ET2" (1re période, mi-temps,
 * 2e période de la prolongation) passent tels quels par le repli `else`
 * ci-dessous, sans règle dédiée ; "ET" (attente de la prolongation)
 * réutilise le statut TheSportsDB existant ("Prolongation") ; "TAB"
 * (séance de tirs au but en cours, ou attente d'icelle) a sa propre règle
 * dédiée plus bas, affichée "PEN" — nom interne différent du "PEN"
 * TheSportsDB existant (qui désigne, lui, un match FINI aux tirs au but,
 * voir plus bas) pour ne pas entrer en collision avec lui.
 *
 * Les statuts testés ci-dessous couvrent à la fois les codes courts
 * officiels documentés par TheSportsDB (NS, 1H, HT, 2H, FT, Q1... voir
 * le lien ci-dessus) et les libellés plus longs ("Not Started", "Match
 * Finished"...) réellement observés en pratique sur lookupevent.php /
 * eventsnext.php / eventslast.php avec la clé gratuite. Les statuts
 * tennis viennent de https://docs.livetennisapi.com (voir tennisLabel
 * ci-dessous).
 */
object MatchClock {

    private val kickoffTimeFormat = SimpleDateFormat("HH:mm", Locale.FRANCE)

    fun label(match: MatchScore): String {
        if (match.apiSource.equals("LIVE_TENNIS", ignoreCase = true)) return tennisLabel(match)

        val status = match.status.trim()

        return when {
            status.isBlank() -> ""

            status.equals("NS", ignoreCase = true) ||
                status.equals("TBD", ignoreCase = true) ||
                status.contains("Not Started", ignoreCase = true) -> {
                val kickoff = match.kickoffEpochMillis
                if (kickoff != null) "À venir · ${kickoffTimeFormat.format(Date(kickoff))}" else "À venir"
            }

            status.equals("HT", ignoreCase = true) -> "MT"

            status.equals("1H", ignoreCase = true) || status.contains("1H", ignoreCase = true) -> "P1"

            status.equals("2H", ignoreCase = true) || status.contains("2H", ignoreCase = true) -> "P2"

            // Quarts-temps (basket, football américain) — même vocabulaire
            // "P<N>" générique que les mi-temps ci-dessus, pas de
            // distinction par sport nécessaire côté montre.
            status.equals("Q1", ignoreCase = true) -> "P1"

            status.equals("Q2", ignoreCase = true) -> "P2"

            status.equals("Q3", ignoreCase = true) -> "P3"

            status.equals("Q4", ignoreCase = true) -> "P4"

            status.equals("ET", ignoreCase = true) -> "Prolongation"

            status.equals("BT", ignoreCase = true) -> "Pause"

            status.equals("P", ignoreCase = true) -> "Tirs au but"

            // Séance de tirs au but EN COURS (repli Sofascore uniquement, voir
            // mobile/SofascoreNotificationParser.kt/parse — statut interne "TAB", PAS "PEN" : ce
            // dernier est déjà pris par TheSportsDB pour "match fini aux tirs au but", voir plus
            // bas — affiché "PEN" comme demandé par Yann le 20/09/2026, sous un nom interne
            // différent pour ne pas entrer en collision avec ce statut TheSportsDB existant).
            status.equals("TAB", ignoreCase = true) -> "PEN"

            status.equals("FT", ignoreCase = true) ||
                status.equals("AOT", ignoreCase = true) ||
                status.contains("Finished", ignoreCase = true) -> "Fin"

            status.equals("AET", ignoreCase = true) -> "Fin (a.p.)"

            // PEN (foot, TheSportsDB) et AP (handball, "After Penalties") désignent le même cas :
            // match fini aux tirs au but. AP est AUSSI, depuis le 20/09/2026, le statut renvoyé par
            // le repli Sofascore lui-même pour un score final foot avec tirs au but (voir mobile/
            // SofascoreNotificationParser.kt/matchFinishedWithShootout, ex. "Match terminé : 6 - 5
            // (5 - 4) (AP)") — coïncidence de vocabulaire bienvenue, aucune règle dédiée nécessaire.
            status.equals("PEN", ignoreCase = true) || status.equals("AP", ignoreCase = true) -> "Fin (tab)"

            status.equals("SUSP", ignoreCase = true) || status.contains("Suspended", ignoreCase = true) -> "Suspendu"

            status.equals("INT", ignoreCase = true) || status.contains("Interrupted", ignoreCase = true) -> "Interrompu"

            status.equals("PST", ignoreCase = true) || status.contains("Postponed", ignoreCase = true) -> "Reporté"

            status.equals("CANC", ignoreCase = true) || status.contains("Cancelled", ignoreCase = true) -> "Annulé"

            status.equals("ABD", ignoreCase = true) || status.contains("Abandoned", ignoreCase = true) -> "Abandonné"

            status.equals("AWD", ignoreCase = true) -> "Forfait technique"

            status.equals("WO", ignoreCase = true) -> "Walkover"

            // TheSportsDB retourne parfois directement un nombre dans
            // strStatus pour certaines ligues — c'est une vraie donnée de
            // l'API, pas un calcul, donc on l'affiche telle quelle.
            status.toIntOrNull() != null -> "$status'"

            // Repli brut : couvre notamment "P1"/"P2"/"P3" du hockey sur
            // glace (déjà dans le format voulu, rien à traduire) et
            // "S1".."S5" du volley (volontairement pas traduits, voir la
            // note de tête de fichier).
            else -> status
        }
    }

    /**
     * Statuts bruts de Live Tennis API : "upcoming", "live", "completed",
     * "cancelled" (voir https://docs.livetennisapi.com) — vocabulaire
     * distinct de celui de TheSportsDB, d'où cette branche séparée plutôt
     * que d'essayer de le faire rentrer dans le `when` ci-dessus. Comme le
     * volley (voir tête de fichier), pas d'indicateur de période en cours
     * de set — seul `completed` est traduit, en "Fin".
     */
    private fun tennisLabel(match: MatchScore): String {
        val status = match.status.trim()

        return when {
            status.equals("upcoming", ignoreCase = true) -> {
                val kickoff = match.kickoffEpochMillis
                if (kickoff != null) "À venir · ${kickoffTimeFormat.format(Date(kickoff))}" else "À venir"
            }

            status.equals("live", ignoreCase = true) -> liveSetLabel(match)

            status.equals("completed", ignoreCase = true) -> "Fin"

            status.equals("cancelled", ignoreCase = true) -> "Annulé"

            status.isBlank() -> ""

            else -> status
        }
    }

    /**
     * "3e set 4-3" si le score de jeux du set en cours est connu, sinon
     * repli sur "En direct". Le numéro de set est déduit du nombre de
     * SETS déjà gagnés au total (homeScore + awayScore, voir MatchScore) —
     * ce n'est pas un calcul de minute par déduction comme celui qu'on
     * s'interdit pour le foot : c'est une donnée directement dérivable du
     * score de sets renvoyé par l'API, pas une estimation temporelle.
     */
    private fun liveSetLabel(match: MatchScore): String {
        val homeGames = match.currentSetHomeGames
        val awayGames = match.currentSetAwayGames
        if (homeGames == null || awayGames == null) return "En direct"

        val setsPlayed = (match.homeScore ?: 0) + (match.awayScore ?: 0)
        val setLabel = if (setsPlayed <= 0) "1er set" else "${setsPlayed + 1}e set"
        return "$setLabel $homeGames-$awayGames"
    }
}
