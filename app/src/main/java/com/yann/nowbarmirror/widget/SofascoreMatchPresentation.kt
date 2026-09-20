package com.yann.nowbarmirror.widget

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phone-side equivalents of wear/MatchScore.kt (scoreText) and wear/MatchClock.kt (label), used
 * to render each Sofascore match tile in the widget's Sport view (see
 * NowBarWidgetProvider.applySofascoreMatches). The :wear module can't be depended on from :app
 * (it pulls in Wear-OS-specific artifacts that don't belong on the phone side, and the reverse
 * dependency direction would be backwards anyway), so this is a deliberate small duplication
 * rather than a shared module — kept identical in spirit to wear/MatchClock.kt's vocabulary
 * mapping. See that file for the full reasoning/sourcing behind each status code; mirror any
 * change there here too.
 *
 * ONE simplification versus the watch: [periodLabel] doesn't reproduce wear/MatchClock.kt's
 * `liveSetLabel` ("3e set 4-3", tennis only) — a live tennis match here just shows "En direct",
 * since a widget tile this small (see widget_now_bar.xml/widget_match_1..4) has no comfortable
 * room for a 3rd line of that length next to 3 other tiles.
 */
object SofascoreMatchPresentation {

    private val kickoffTimeFormat = SimpleDateFormat("HH:mm", Locale.FRANCE)

    /** "2-1" (brackets around whichever side just scored/won the last set), or "vs" if unknown — see wear/MatchScore.kt/scoreText. */
    fun scoreText(homeScore: String?, awayScore: String?, lastScorer: String?): String {
        if (homeScore == null || awayScore == null) return "vs"
        val home = if (lastScorer == "home") "[$homeScore]" else homeScore
        val away = if (lastScorer == "away") "[$awayScore]" else awayScore
        return "$home-$away"
    }

    /** Short period/status label ("P1"/"MT"/"Fin"...) — mirrors wear/MatchClock.kt/label's vocabulary mapping, see that file for the sourcing behind each code. [kickoffEpochMillis] is currently always null from the widget (see SofascoreWidgetStore — not carried over, since an ACTIVE Sofascore notification is never for a match that hasn't started yet), so the "kickoff time" branches below fall back to a bare "À venir". */
    fun periodLabel(status: String, apiSource: String, kickoffEpochMillis: Long?): String {
        if (apiSource.equals("LIVE_TENNIS", ignoreCase = true)) return tennisLabel(status, kickoffEpochMillis)

        val trimmed = status.trim()
        return when {
            trimmed.isBlank() -> ""

            trimmed.equals("NS", true) || trimmed.equals("TBD", true) || trimmed.contains("Not Started", true) ->
                kickoffEpochMillis?.let { "À venir · ${kickoffTimeFormat.format(Date(it))}" } ?: "À venir"

            trimmed.equals("HT", true) -> "MT"
            trimmed.equals("1H", true) || trimmed.contains("1H", true) -> "P1"
            trimmed.equals("2H", true) || trimmed.contains("2H", true) -> "P2"
            trimmed.equals("Q1", true) -> "P1"
            trimmed.equals("Q2", true) -> "P2"
            trimmed.equals("Q3", true) -> "P3"
            trimmed.equals("Q4", true) -> "P4"
            trimmed.equals("ET", true) -> "Prolongation"
            trimmed.equals("BT", true) -> "Pause"
            trimmed.equals("P", true) -> "Tirs au but"

            // Séance de tirs au but EN COURS (repli Sofascore, voir SofascoreNotificationParser.kt)
            // — statut interne "TAB", pas "PEN" (déjà pris par TheSportsDB pour "fini aux tirs au
            // but", voir plus bas) — affiché "PEN" comme demandé par Yann le 20/09/2026. Garder ce
            // fichier aligné avec wear/MatchClock.kt#label (voir tête de fichier).
            trimmed.equals("TAB", true) -> "PEN"

            trimmed.equals("FT", true) || trimmed.equals("AOT", true) || trimmed.contains("Finished", true) -> "Fin"
            trimmed.equals("AET", true) -> "Fin (a.p.)"
            // AP est aussi, depuis le 20/09/2026, le statut renvoyé par le repli Sofascore pour un
            // score final foot avec tirs au but (voir SofascoreNotificationParser.kt/
            // matchFinishedWithShootout) — même coïncidence de vocabulaire que côté montre.
            trimmed.equals("PEN", true) || trimmed.equals("AP", true) -> "Fin (tab)"

            trimmed.equals("SUSP", true) || trimmed.contains("Suspended", true) -> "Suspendu"
            trimmed.equals("INT", true) || trimmed.contains("Interrupted", true) -> "Interrompu"
            trimmed.equals("PST", true) || trimmed.contains("Postponed", true) -> "Reporté"
            trimmed.equals("CANC", true) || trimmed.contains("Cancelled", true) -> "Annulé"
            trimmed.equals("ABD", true) || trimmed.contains("Abandoned", true) -> "Abandonné"
            trimmed.equals("AWD", true) -> "Forfait technique"
            trimmed.equals("WO", true) -> "Walkover"

            trimmed.toIntOrNull() != null -> "$trimmed'"

            // Repli brut : couvre notamment "S1".."S5" (tennis de table/volley, déjà le bon
            // format, voir SofascoreNotificationParser.parseSetTally) et "MM+" (but horodaté,
            // voir SofascoreNotificationParser.parseFootball/timedEvent).
            else -> trimmed
        }
    }

    private fun tennisLabel(status: String, kickoffEpochMillis: Long?): String {
        val trimmed = status.trim()
        return when {
            trimmed.equals("upcoming", true) ->
                kickoffEpochMillis?.let { "À venir · ${kickoffTimeFormat.format(Date(it))}" } ?: "À venir"
            trimmed.equals("live", true) -> "En direct"
            trimmed.equals("completed", true) -> "Fin"
            trimmed.equals("cancelled", true) -> "Annulé"
            trimmed.isBlank() -> ""
            else -> trimmed
        }
    }

    /**
     * The exact set of terminal statuses SofascoreNotificationParser.parse can produce — see its
     * class doc — used to decide the widget's live-first sort order (NowBarWidgetProvider.
     * sortedForWidget). NOT the same test as MatchResult.isFinished in Models.kt: that one is
     * tuned for TheSportsDB/Live Tennis API's own vocabulary ("Match Finished", "Cancelled"...)
     * and doesn't recognise "Fin" (the set-tally sports' own finished status, see
     * SofascoreNotificationParser.parseSetTally) — using it here would silently miss those.
     */
    fun isMatchFinished(status: String): Boolean =
        status.equals("FT", ignoreCase = true) ||
            status.equals("Fin", ignoreCase = true) ||
            status.equals("completed", ignoreCase = true) ||
            // "AP" : score final foot avec tirs au but (voir SofascoreNotificationParser.kt/
            // matchFinishedWithShootout, ajouté le 20/09/2026) — sans cette entrée, un tel match
            // resterait classé "en cours" dans le tri du widget (sortedForWidget) indéfiniment.
            status.equals("AP", ignoreCase = true)
}
