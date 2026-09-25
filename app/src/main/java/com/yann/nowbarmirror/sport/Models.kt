package com.yann.nowbarmirror.sport

/**
 * One Sofascore match as parsed from its notification (SofascoreNotificationParser /
 * SofascoreNotificationListenerService) — sent to the watch ("/match", WatchSync) and to the
 * widget. Since 24/09/2026 Sofascore's own notification is the ONLY source (TheSportsDB / Live
 * Tennis API overrides removed). [status] is a short code translated by wear/MatchClock.kt and
 * widget/SofascoreMatchPresentation.kt; [lastScorer] = "home"/"away" when the notification
 * brackets who just scored / won the last set, else null. Null scores = unparsed ("vs").
 * Watch Sport screen only (25/09/2026): [periodLabel] = long French label of the event the status
 * came from ("Match commencé", "Mi-temps", "2ème mi-temps", "Min 23 · Carton rouge"…, null → the watch spells [status]);
 * [eventKind] = kind of that event (SofascoreNotificationParser.PERIOD/GOAL/OTHER); [goals] = every
 * goal / in-match missed penalty still in the notification, most recent first, encoded
 * "side\tminute\tname\tkind" (see SofascoreNotificationParser.goalsOf).
 */
data class MatchResult(
    val homeTeam: String,
    val awayTeam: String,
    val homeScore: String?,
    val awayScore: String?,
    val lastScorer: String? = null,
    val status: String,
    val periodLabel: String? = null,
    val eventKind: String? = null,
    val goals: List<String> = emptyList()
)
