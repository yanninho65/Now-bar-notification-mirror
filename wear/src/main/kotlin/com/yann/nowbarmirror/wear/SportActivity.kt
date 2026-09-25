package com.yann.nowbarmirror.wear

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
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
 *   "Notification" complication included — one pill per match (item_sport_match.xml): the
 *   complication's image (composeRoundImage), the notification's title, "Aff. sur tél." (opens,
 *   doesn't dismiss), delete, and the pin that makes "Score en direct" follow only this match.
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
        DetailViews.applyRoundInsets(container)
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
        pruneImages(all)

        container.removeAllViews()
        val apps = list?.apps.orEmpty()
        if (apps.isNotEmpty()) {
            container.addView(DetailViews.appIconRow(this, apps))
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
        row.setBackgroundResource(if (isFollowed) R.drawable.bg_notif_pill_followed else R.drawable.bg_notif_pill)
        row.findViewById<ImageView>(R.id.sport_match_image).setImageBitmap(imageFor(item))
        row.findViewById<TextView>(R.id.sport_match_title).text = item.title

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

    /** Same bitmap as the "Score en direct" SMALL_IMAGE, composed once per match value. */
    private fun imageFor(item: SportMatch): Bitmap =
        images.getOrPut(imageKey(item)) { ComplicationImageComposer.composeRoundImage(item.match) }

    // Content key (not the push timestamp): a push that only changes the app row recomposes nothing.
    private fun imageKey(item: SportMatch) = with(item.match) {
        "${item.key}|${item.postTimeMillis}|$homeScore|$awayScore|$lastScorer|$status|${notifImage != null}"
    }

    private fun pruneImages(current: List<SportMatch>) {
        val keep = current.map(::imageKey).toSet()
        images.keys.retainAll(keep)
    }

    private companion object {
        // Kept across openings (process lifetime): reopening the screen doesn't recompose unchanged matches.
        val images = HashMap<String, Bitmap>()
    }
}
