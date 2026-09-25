package com.yann.nowbarmirror.wear

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.wear.widget.SwipeDismissFrameLayout

/**
 * NEW 24/09/2026 — opened by a tap on the "Messages" complication: every message currently in the
 * phone's notification center (MessagesStore — not filtered like the complication), one after the
 * other, each in exactly the "Dernière notif" window style (item_message.xml mirrors
 * activity_notification_detail.xml; pills/press feedback from [DetailViews]).
 *
 * Per message: the notification's own action pills (inline-reply actions are not sent by the
 * phone), "Aff. sur montre" ONLY if the same app is installed on the watch, "Aff. sur tél.", and
 * the round delete button. Re-renders live when a new list arrives ([MessagesStore.onChanged]).
 *
 * UPDATED 24/09/2026 — above the messages (shown with or without messages): one row with the
 * selected message apps installed on the watch (tap = open on the watch), one row with the others
 * (tap = open on the phone, PhoneRelay.sendOpenAppOnPhone). An app on both is only in the watch row.
 * Each icon carries its unread count as a badge at its top-right ([MessageApp.count]).
 *
 * UPDATED 24/09/2026 — round screen: paddings are fractions of the screen (DetailViews.applyRoundInsets).
 *
 * REWORKED 25/09/2026 — each message in a pill like the Sport screen: image top-left with the app
 * icon as a badge at its bottom-right, title on its right, text below (truncated text gets a
 * clickable blue "•••" that shows it all); actions as round icon buttons on one line (mark read =
 * open envelope, silence = bell off, delete = bin, watch, phone; "Répondre" dropped), the "delete on
 * the phone" button under them.
 *
 * UPDATED 25/09/2026 — app row: ONE horizontally scrollable line (DetailViews.appIconRow, shared with
 * SportActivity), no more "Sur la montre" / "Sur le téléphone" split. Tap still opens on the watch
 * when the app is installed there, otherwise on the phone.
 */
class MessagesActivity : Activity() {

    private lateinit var container: LinearLayout

    // Deleted from here but not yet confirmed by a phone push: hidden meanwhile.
    private val pendingDismissed = mutableSetOf<String>()

    // Texts expanded with their "•••" ("<message key>|<line index>", -1 = the plain text), kept across re-renders.
    private val expanded = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_messages)
        container = findViewById(R.id.messages_container)
        // Pills almost full width, same as the Sport screen (25/09/2026).
        DetailViews.applyRoundInsets(container, sideFraction = 0.03f)
        findViewById<SwipeDismissFrameLayout>(R.id.swipe_layout).addCallback(object : SwipeDismissFrameLayout.Callback() {
            override fun onDismissed(layout: SwipeDismissFrameLayout) {
                layout.visibility = View.GONE
                finish()
            }
        })
        render(MessagesStore.current)
    }

    override fun onResume() {
        super.onResume()
        MessagesStore.onChanged = { runOnUiThread { if (!isFinishing && !isDestroyed) render(MessagesStore.current) } }
        // Same "paint the cache, then re-read the persisted item" as NotificationDetailActivity.
        Thread {
            val read = PhoneDataLayer.readMessages(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (read is PhoneDataLayer.Read.Success) render(read.value)
            }
        }.start()
    }

    override fun onPause() {
        super.onPause()
        MessagesStore.onChanged = null
    }

    private fun render(list: MessageList?) {
        val all = list?.messages.orEmpty()
        pendingDismissed.retainAll(all.map { it.key }.toSet())
        val keys = all.map { it.key }.toSet()
        expanded.retainAll { it.substringBeforeLast('|') in keys }
        val messages = all.filterNot { it.key in pendingDismissed }

        container.removeAllViews()
        val apps = list?.apps.orEmpty()
        if (apps.isNotEmpty()) {
            container.addView(DetailViews.appIconRow(this, apps, extraSideFraction = 0.07f))
            container.addView(DetailViews.separator(this))
        }
        if (messages.isEmpty()) {
            container.addView(DetailViews.emptyText(this, getString(R.string.messages_empty)))
            return
        }
        messages.forEach { message ->
            val item = layoutInflater.inflate(R.layout.item_message, container, false)
            bind(item, message)
            container.addView(item)
        }
    }

    private fun bind(item: View, message: MessageInfo) {
        // Top-left: the notification image, else the app icon; with an image, the app icon is a badge at its bottom-right.
        val image = message.image ?: message.appIcon
        item.findViewById<ImageView>(R.id.message_image).apply {
            if (image != null) {
                setImageBitmap(DetailViews.circularBitmap(image))
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }
        item.findViewById<ImageView>(R.id.message_app_icon).apply {
            if (message.image != null && message.appIcon != null) {
                setImageBitmap(DetailViews.circularBitmap(message.appIcon))
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }
        item.findViewById<TextView>(R.id.message_title).text = message.title.ifBlank { "Message" }

        val textView = item.findViewById<TextView>(R.id.message_text)
        val lines = item.findViewById<LinearLayout>(R.id.message_lines_container)
        if (message.detailLines.isNotEmpty()) {
            lines.visibility = View.VISIBLE
            message.detailLines.forEachIndexed { i, line ->
                val view = detailLine(line)
                lines.addView(view)
                makeExpandable(view, "${message.key}|$i")
            }
        } else if (message.text.isNotBlank()) {
            textView.visibility = View.VISIBLE
            textView.text = message.text
            makeExpandable(textView, "${message.key}|-1")
        }

        // Round icon buttons on one line (25/09/2026); "Répondre" ignored; labels with no known
        // icon stay text pills above the row. The index sent is the phone's action index.
        val buttons = mutableListOf<Triple<Int, String, () -> Unit>>()
        val textActions = item.findViewById<LinearLayout>(R.id.message_text_actions_container)
        message.actionLabels.forEachIndexed { index, label ->
            if (isReply(label)) return@forEachIndexed
            val send = { PhoneRelay.sendMessageAction(applicationContext, message.key, index) }
            val icon = iconFor(label)
            if (icon != null) {
                buttons.add(Triple(icon, label, send))
            } else {
                textActions.visibility = View.VISIBLE
                textActions.addView(DetailViews.actionChip(this, label) { send() })
            }
        }
        watchLaunchIntent(message.packageName)?.let { launch ->
            buttons.add(Triple(R.drawable.ic_watch, getString(R.string.action_view_on_watch)) {
                try {
                    startActivity(launch)
                } catch (_: Exception) {
                }
            })
        }
        buttons.add(Triple(R.drawable.ic_phone, getString(R.string.action_view_on_phone)) {
            PhoneRelay.sendMessageOpen(applicationContext, message.key)
        })

        // Sized so up to 5 buttons fit the pill width on one line.
        val size = when {
            buttons.size <= 3 -> 36
            buttons.size == 4 -> 32
            else -> 28
        }
        val gap = DetailViews.dp(this, if (buttons.size <= 3) 14 else 6)
        val row = item.findViewById<LinearLayout>(R.id.message_actions_container)
        buttons.forEachIndexed { i, (icon, description, onClick) ->
            val button = DetailViews.roundIconButton(this, icon, description, size, onClick)
            if (i > 0) (button.layoutParams as LinearLayout.LayoutParams).marginStart = gap
            row.addView(button)
        }

        item.findViewById<ImageButton>(R.id.message_delete_button).apply {
            contentDescription = getString(R.string.action_delete_on_phone)
            DetailViews.addPressFeedback(this)
            setOnClickListener {
                PhoneRelay.sendMessageDismiss(applicationContext, message.key)
                pendingDismissed.add(message.key)
                render(MessagesStore.current)
            }
        }
    }

    /**
     * Truncated text (25/09/2026): a blue "•••" pill is added right under it once laid out; a tap
     * on it (or on the text) shows the whole text. Remembered per message/line across re-renders.
     */
    private fun makeExpandable(textView: TextView, id: String) {
        if (id in expanded) {
            textView.maxLines = Int.MAX_VALUE
            return
        }
        textView.post {
            val layout = textView.layout ?: return@post
            val last = layout.lineCount - 1
            if (last < 0 || layout.getEllipsisCount(last) == 0) return@post
            val parent = textView.parent as? LinearLayout ?: return@post
            val more = TextView(this).apply {
                text = "•••"
                setTextColor(getColor(R.color.detail_accent_pressed))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                includeFontPadding = false
                setBackgroundResource(R.drawable.bg_more_chip)
                val h = DetailViews.dp(this@MessagesActivity, 14)
                val v = DetailViews.dp(this@MessagesActivity, 3)
                setPadding(h, v, h, v)
                contentDescription = getString(R.string.action_show_more)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = DetailViews.dp(this@MessagesActivity, 4)
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                }
            }
            val expand = {
                expanded.add(id)
                textView.maxLines = Int.MAX_VALUE
                parent.removeView(more)
                textView.setOnClickListener(null)
                textView.isClickable = false
            }
            more.setOnClickListener { expand() }
            DetailViews.addPressFeedback(more)
            textView.setOnClickListener { expand() }
            parent.addView(more, parent.indexOfChild(textView) + 1)
        }
    }

    /** One conversation line inside the pill: plain left-aligned text. */
    private fun detailLine(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.detail_text_primary))
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        maxLines = 4
        ellipsize = android.text.TextUtils.TruncateAt.END
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = DetailViews.dp(this@MessagesActivity, 4)
        }
    }

    private fun normalized(label: String): String =
        java.text.Normalizer.normalize(label.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private fun isReply(label: String): Boolean {
        val l = normalized(label)
        return "repond" in l || "reply" in l
    }

    /** Icon of a known action label (French/English wordings), null = keep it as a text pill. */
    private fun iconFor(label: String): Int? {
        val l = normalized(label)
        return when {
            Regex("\\blu\\b").containsMatchIn(l) || "read" in l -> R.drawable.ic_mark_read
            "silenc" in l || "mute" in l || "sourdine" in l || "muet" in l -> R.drawable.ic_mute
            "suppr" in l || "delete" in l || "corbeille" in l || "effacer" in l -> R.drawable.ic_delete
            else -> null
        }
    }

    /** The source app's own watch version, if installed (shared helper, also used by NotificationDetailActivity). */
    private fun watchLaunchIntent(packageName: String): Intent? = DetailViews.watchLaunchIntent(this, packageName)
}
