package com.yann.nowbarmirror.sport

/**
 * One Sofascore match as parsed from its notification (SofascoreNotificationParser /
 * SofascoreNotificationListenerService) — sent to the watch ("/match", WatchSync) and to the
 * widget. Since 24/09/2026 Sofascore's own notification is the ONLY source (TheSportsDB / Live
 * Tennis API overrides removed). [status] is a short code translated by wear/MatchClock.kt and
 * widget/SofascoreMatchPresentation.kt; [lastScorer] = "home"/"away" when the notification
 * brackets who just scored / won the last set, else null. Null scores = unparsed ("vs").
 */
data class MatchResult(
    val homeTeam: String,
    val awayTeam: String,
    val homeScore: String?,
    val awayScore: String?,
    val lastScorer: String? = null,
    val status: String
)
