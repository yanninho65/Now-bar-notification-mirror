package com.yann.nowbarmirror.wear

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
 * UPDATED 24/09/2026 — round screen: paddings are fractions of the screen ([applyRoundInsets]),
 * and app icons wrap in rows of at most [ICONS_PER_ROW], sized so a full row fits the circle's
 * chord at the top of the list — no horizontal scrolling. App order = the phone's order.
 */
class MessagesActivity : Activity() {

    private lateinit var container: LinearLayout

    // Deleted from here but not yet confirmed by a phone push: hidden meanwhile.
    private val pendingDismissed = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_messages)
        container = findViewById(R.id.messages_container)
        applyRoundInsets()
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
        val messages = all.filterNot { it.key in pendingDismissed }

        container.removeAllViews()
        val hasAppRows = addAppRows(list?.apps.orEmpty())
        if (hasAppRows) container.addView(separator())
        if (messages.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.messages_empty)
                setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Large)
                setTextColor(getColor(R.color.detail_text_primary))
                gravity = android.view.Gravity.CENTER
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            return
        }
        messages.forEachIndexed { index, message ->
            val item = layoutInflater.inflate(R.layout.item_message, container, false)
            bind(item, message)
            item.findViewById<View>(R.id.message_separator).visibility =
                if (index == messages.lastIndex) View.GONE else View.VISIBLE
            container.addView(item)
        }
    }

    private fun bind(item: View, message: MessageInfo) {
        item.findViewById<ImageView>(R.id.message_image).apply {
            if (message.image != null) {
                setImageBitmap(DetailViews.circularBitmap(message.image))
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }
        item.findViewById<ImageView>(R.id.message_app_icon).apply {
            if (message.appIcon != null) {
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
            message.detailLines.forEach { lines.addView(DetailViews.lineCard(this, it)) }
        } else if (message.text.isNotBlank()) {
            textView.visibility = View.VISIBLE
            textView.text = message.text
        }

        val actions = item.findViewById<LinearLayout>(R.id.message_actions_container)
        message.actionLabels.forEachIndexed { index, label ->
            actions.addView(DetailViews.actionChip(this, label) {
                PhoneRelay.sendMessageAction(applicationContext, message.key, index)
            })
        }
        watchLaunchIntent(message.packageName)?.let { launch ->
            actions.addView(DetailViews.actionChip(this, getString(R.string.action_view_on_watch)) {
                try {
                    startActivity(launch)
                } catch (_: Exception) {
                }
            })
        }
        actions.addView(DetailViews.actionChip(this, getString(R.string.action_view_on_phone)) {
            PhoneRelay.sendMessageOpen(applicationContext, message.key)
        })

        item.findViewById<ImageButton>(R.id.message_delete_button).apply {
            DetailViews.addPressFeedback(this)
            setOnClickListener {
                PhoneRelay.sendMessageDismiss(applicationContext, message.key)
                pendingDismissed.add(message.key)
                render(MessagesStore.current)
            }
        }
    }

    /** Adds the "on watch" / "on phone" app rows; returns false when there is nothing to show. */
    private fun addAppRows(apps: List<MessageApp>): Boolean {
        if (apps.isEmpty()) return false
        val (onWatch, phoneOnly) = apps.partition { watchLaunchIntent(it.packageName) != null }
        if (onWatch.isNotEmpty()) addAppRow(getString(R.string.messages_apps_on_watch), onWatch, onWatchRow = true)
        if (phoneOnly.isNotEmpty()) addAppRow(getString(R.string.messages_apps_on_phone), phoneOnly, onWatchRow = false)
        return true
    }

    private fun addAppRow(title: String, apps: List<MessageApp>, onWatchRow: Boolean) {
        container.addView(TextView(this).apply {
            text = title
            setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small)
            setTextColor(getColor(R.color.detail_text_secondary))
            gravity = android.view.Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Up to ICONS_PER_ROW per line, centered, wrapping to a new line — never scrolls sideways.
        apps.chunked(ICONS_PER_ROW).forEachIndexed { index, chunk ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER
            }
            chunk.forEach { app -> row.addView(appIconView(app, onWatchRow)) }
            container.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = DetailViews.dp(this@MessagesActivity, if (index == (apps.size - 1) / ICONS_PER_ROW) 10 else 2)
            })
        }
    }

    /**
     * Width of one icon cell: a full row of [ICONS_PER_ROW] must fit the circle's chord near the top
     * of the list (~78 % of the screen width), capped at the former 50dp.
     */
    private fun cellSizePx(): Int {
        val width = resources.displayMetrics.widthPixels
        return minOf((width * 0.78f / ICONS_PER_ROW).toInt(), DetailViews.dp(this, 50))
    }

    /** Round app icon (80 % of the cell), count badge at its top-right. */
    private fun appIconView(app: MessageApp, onWatchRow: Boolean): View {
        val dp = { v: Int -> DetailViews.dp(this, v) }
        val cellSize = cellSizePx()
        val iconSize = (cellSize * 0.8f).toInt()
        val badgeHeight = (cellSize * 0.4f).toInt()
        val cell = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(cellSize, cellSize)
            contentDescription = app.label
        }
        val icon = app.icon ?: watchIcon(app.packageName)
        cell.addView(ImageView(this).apply {
            icon?.let { setImageBitmap(DetailViews.circularBitmap(it)) }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, FrameLayout.LayoutParams(iconSize, iconSize, android.view.Gravity.BOTTOM or android.view.Gravity.START).apply {
            bottomMargin = dp(1)
        })
        if (app.count > 0) {
            cell.addView(TextView(this).apply {
                text = if (app.count > 99) "99+" else app.count.toString()
                setTextColor(getColor(R.color.detail_text_primary))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, badgeHeight * 0.55f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                minWidth = badgeHeight
                setPadding(dp(3), 0, dp(3), 0)
                setBackgroundResource(R.drawable.bg_count_badge)
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, badgeHeight, android.view.Gravity.TOP or android.view.Gravity.END))
        }
        DetailViews.addPressFeedback(cell)
        cell.isClickable = true
        cell.setOnClickListener {
            if (onWatchRow) {
                watchLaunchIntent(app.packageName)?.let { launch ->
                    try {
                        startActivity(launch)
                    } catch (_: Exception) {
                    }
                }
            } else {
                PhoneRelay.sendOpenAppOnPhone(applicationContext, app.packageName)
            }
        }
        return cell
    }

    private fun separator(): View = View(this).apply {
        setBackgroundColor(getColor(R.color.detail_chip_bg))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, DetailViews.dp(this@MessagesActivity, 1)).apply {
            topMargin = DetailViews.dp(this@MessagesActivity, 6)
            bottomMargin = DetailViews.dp(this@MessagesActivity, 20)
        }
    }

    /**
     * Round-screen insets as fractions of the display (Wear OS guidance): sides ~10 % so centered
     * text stays inside the circle, top ~13 % for the narrow top of the circle, bottom ~25 % so the
     * last delete button can be scrolled up to the middle.
     */
    private fun applyRoundInsets() {
        val metrics = resources.displayMetrics
        val side = (metrics.widthPixels * 0.10f).toInt()
        container.setPadding(side, (metrics.heightPixels * 0.13f).toInt(), side, (metrics.heightPixels * 0.25f).toInt())
    }

    /** Fallback when the phone sent no icon: the watch app's own icon. */
    private fun watchIcon(packageName: String): android.graphics.Bitmap? = try {
        val drawable = packageManager.getApplicationIcon(packageName)
        val size = DetailViews.dp(this, 40).coerceAtLeast(1)
        android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888).also {
            val canvas = android.graphics.Canvas(it)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
        }
    } catch (_: Exception) {
        null
    }

    /** The source app's own watch version, if installed (shared helper, also used by NotificationDetailActivity). */
    private fun watchLaunchIntent(packageName: String): Intent? = DetailViews.watchLaunchIntent(this, packageName)

    private companion object {
        const val ICONS_PER_ROW = 4
    }
}
