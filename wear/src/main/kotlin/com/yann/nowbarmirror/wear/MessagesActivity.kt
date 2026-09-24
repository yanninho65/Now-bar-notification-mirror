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
 */
class MessagesActivity : Activity() {

    private lateinit var container: LinearLayout

    // Deleted from here but not yet confirmed by a phone push: hidden meanwhile.
    private val pendingDismissed = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_messages)
        container = findViewById(R.id.messages_container)
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

    /** The source app's own watch version, if installed (same package name as on the phone). */
    private fun watchLaunchIntent(packageName: String): Intent? {
        if (packageName.isBlank()) return null
        return try {
            packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } catch (_: Exception) {
            null
        }
    }
}
