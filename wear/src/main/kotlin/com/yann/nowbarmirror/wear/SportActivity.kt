package com.yann.nowbarmirror.wear

import android.app.Activity
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.wear.widget.SwipeDismissFrameLayout

/**
 * NEW 25/09/2026 — opened by a tap on the "Score en direct" complication (was: Sofascore on the
 * watch; Sofascore is now reachable from the app row). Built like MessagesActivity (same frame
 * layout activity_messages.xml, same round insets, same shared app row / separator / empty text
 * from [DetailViews]):
 * - top: the sport apps chosen on the phone (SportAppsPrefs), ONE scrollable line with count badges;
 * - below: EVERY active Sofascore notification (SportStore, "/sport") — the one also shown by the
 *   "Notification" complication included — one near-full-width pill per match (item_sport_match.xml,
 *   reworked 25/09/2026): small notification image + title, big score, the period right under it,
 *   the scorers (football, most recent first, cancelled ones removed on the phone; since 25/09/2026
 *   minute centered, home scorer left / away scorer right, missed penalties shown as "Pénalty X"),
 *   then pin / "Aff. sur tél." (opens, doesn't dismiss) / delete. Compact so 4 scorers fit. The pin makes "Score en direct" follow only this match.
 *   The followed match is listed first (blue outline, blue pin); tapping its pin again goes back
 *   to automatic. Order otherwise: most recent first.
 * Taps are optimistic (hidden / pinned locally) until the next phone push confirms them.
 */
class SportActivity : Activity() {

    private lateinit var container: LinearLayout

    private val pendingDismissed = mutableSetOf<String>()

    // Follow change sent but not yet confirmed: "" = back to automatic. Cleared by any newer push.
    private var pendingFollow: String? = null
    private var pendingFollowSince = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_messages)
        container = findViewById(R.id.messages_container)
        // Pills almost full width, like the watch's own notification cards (25/09/2026).
        DetailViews.applyRoundInsets(container, sideFraction = 0.03f)
        findViewById<SwipeDismissFrameLayout>(R.id.swipe_layout).addCallback(object : SwipeDismissFrameLayout.Callback() {
            override fun onDismissed(layout: SwipeDismissFrameLayout) {
                layout.visibility = View.GONE
                finish()
            }
        })
        render(SportStore.current)
    }

    override fun onResume() {
        super.onResume()
        SportStore.onChanged = { runOnUiThread { if (!isFinishing && !isDestroyed) render(SportStore.current) } }
        Thread {
            val read = PhoneDataLayer.readSport(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (read is PhoneDataLayer.Read.Success) render(read.value)
            }
        }.start()
    }

    override fun onPause() {
        super.onPause()
        SportStore.onChanged = null
    }

    private fun render(list: SportList?) {
        val all = list?.matches.orEmpty()
        pendingDismissed.retainAll(all.map { it.key }.toSet())
        if (list != null && list.syncTimestamp > pendingFollowSince) pendingFollow = null
        val followed = (pendingFollow ?: list?.followedKey)?.takeIf { key -> key.isNotBlank() && all.any { it.key == key } }
        val matches = all.filterNot { it.key in pendingDismissed }
            .sortedWith(compareByDescending<SportMatch> { it.key == followed }.thenByDescending { it.postTimeMillis })

        container.removeAllViews()
        val apps = list?.apps.orEmpty()
        if (apps.isNotEmpty()) {
            container.addView(DetailViews.appIconRow(this, apps, extraSideFraction = 0.07f))
            container.addView(DetailViews.separator(this))
        }
        if (matches.isEmpty()) {
            container.addView(DetailViews.emptyText(this, getString(R.string.sport_empty)))
            return
        }
        matches.forEach { item ->
            val row = layoutInflater.inflate(R.layout.item_sport_match, container, false)
            bind(row, item, isFollowed = item.key == followed, syncTimestamp = list?.syncTimestamp ?: 0L)
            container.addView(row)
        }
    }

    private fun bind(row: View, item: SportMatch, isFollowed: Boolean, syncTimestamp: Long) {
        val match = item.match
        row.setBackgroundResource(if (isFollowed) R.drawable.bg_notif_pill_followed else R.drawable.bg_notif_pill)
        row.findViewById<ImageView>(R.id.sport_match_image).apply {
            val image = match.notifImage
            if (image != null) {
                setImageBitmap(image)
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }
        row.findViewById<TextView>(R.id.sport_match_title).text = item.title
        row.findViewById<TextView>(R.id.sport_score).text = scoreText(item)

        // Period always right under the score, not bold (25/09/2026).
        row.findViewById<TextView>(R.id.sport_period).apply {
            val period = MatchClock.longLabel(match)
            text = period
            visibility = if (period.isBlank()) View.GONE else View.VISIBLE
        }

        // Scorers (25/09/2026): minute in the middle, home scorer on its left, away scorer on its
        // right, name on up to 2 lines; in-match missed penalties too ("Pénalty X" under the name).
        val goals = row.findViewById<LinearLayout>(R.id.sport_goals)
        goals.removeAllViews()
        goals.visibility = if (match.goals.isEmpty()) View.GONE else View.VISIBLE
        match.goals.forEach { goals.addView(scorerRow(it)) }

        row.findViewById<ImageButton>(R.id.sport_follow_button).apply {
            setBackgroundResource(if (isFollowed) R.drawable.bg_follow_active else R.drawable.bg_delete_button)
            contentDescription = getString(if (isFollowed) R.string.sport_unfollow else R.string.sport_follow)
            DetailViews.addPressFeedback(this)
            setOnClickListener {
                val newKey = if (isFollowed) "" else item.key
                PhoneRelay.sendSportFollow(applicationContext, newKey)
                pendingFollow = newKey
                pendingFollowSince = syncTimestamp
                render(SportStore.current)
            }
        }
        row.findViewById<ImageButton>(R.id.sport_open_button).apply {
            DetailViews.addPressFeedback(this)
            setOnClickListener { PhoneRelay.sendSportOpen(applicationContext, item.key) }
        }
        row.findViewById<ImageButton>(R.id.sport_delete_button).apply {
            DetailViews.addPressFeedback(this)
            setOnClickListener {
                PhoneRelay.sendSportDismiss(applicationContext, item.key)
                pendingDismissed.add(item.key)
                render(SportStore.current)
            }
        }
    }

    private fun scorerRow(line: ScorerLine): View {
        val rowView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = DetailViews.dp(this@SportActivity, 4)
            }
        }
        val minute = TextView(this).apply {
            text = if (line.minute.isBlank()) "" else "${line.minute}'"
            setTextColor(android.graphics.Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            val margin = DetailViews.dp(this@SportActivity, if (line.minute.isBlank()) 2 else 6)
            setPadding(margin, 0, margin, 0)
        }
        if (line.side == null) {
            // Side unknown (older phone build, or no bracket to deduce it): centered as before.
            rowView.gravity = Gravity.CENTER
            rowView.addView(minute)
            rowView.addView(scorerColumn(line, Gravity.CENTER), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            return rowView
        }
        val home = line.side == "home"
        val left = if (home) scorerColumn(line, Gravity.END) else View(this)
        val right = if (home) View(this) else scorerColumn(line, Gravity.START)
        rowView.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        rowView.addView(minute, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        rowView.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return rowView
    }

    /** Scorer name (max 2 lines), plus "Pénalty X" under it for a missed penalty. */
    private fun scorerColumn(line: ScorerLine, align: Int): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = align
        }
        val name = line.name.ifBlank { if (line.missedPenalty) "" else "But" }
        if (name.isNotBlank()) {
            column.addView(TextView(this).apply {
                text = name
                setTextColor(android.graphics.Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = align
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
            })
        }
        if (line.missedPenalty) {
            column.addView(TextView(this).apply {
                text = getString(R.string.sport_penalty_missed)
                setTextColor(getColor(R.color.detail_text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = align
                maxLines = 1
                includeFontPadding = false
            })
        }
        return column
    }

    /** "0 - [1]": raw score strings as sent, brackets on the side that just scored; "vs" before any score. */
    private fun scoreText(item: SportMatch): String {
        val home = item.homeScoreText
        val away = item.awayScoreText
        if (home.isEmpty() || away.isEmpty()) return "vs"
        val h = if (item.match.lastScorer == "home" && !home.contains('[')) "[$home]" else home
        val a = if (item.match.lastScorer == "away" && !away.contains('[')) "[$away]" else away
        return "$h - $a"
    }

}
