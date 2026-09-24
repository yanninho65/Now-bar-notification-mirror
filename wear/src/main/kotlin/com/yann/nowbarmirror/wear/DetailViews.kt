package com.yann.nowbarmirror.wear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Galaxy Watch-style building blocks shared by NotificationDetailActivity and MessagesActivity
 * (extracted 24/09/2026 so both screens stay identical — see NotificationDetailActivity's doc for
 * the history of each style choice: full-width single-line pills, system text appearances, press
 * feedback = scale 0.94 + lighter state_pressed background).
 */
object DetailViews {

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    /** Shrink on press (ACTION_DOWN), restore on UP/CANCEL; returns false so the click still fires. */
    fun addPressFeedback(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
    }

    /** One history line (message/event): plain centered system Medium text, no background. */
    @Suppress("DEPRECATION")
    fun lineCard(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            setTextAppearance(context, android.R.style.TextAppearance_DeviceDefault_Medium)
            setTextColor(context.resources.getColor(R.color.detail_text_primary, context.theme))
            gravity = android.view.Gravity.CENTER
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = dp(context, 10)
            layoutParams = params
        }
    }

    /** Full-width, single-line action pill (native Galaxy Watch notification menu style). */
    fun actionChip(context: Context, label: String, onClick: () -> Unit): Button {
        return Button(context).apply {
            text = label
            isAllCaps = false
            setTextColor(context.resources.getColor(R.color.detail_text_primary, context.theme))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = android.view.Gravity.CENTER
            setBackgroundResource(R.drawable.bg_action_chip)
            setPadding(dp(context, 20), dp(context, 14), dp(context, 20), dp(context, 14))
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = dp(context, 8)
            layoutParams = params
            setOnClickListener { onClick() }
            addPressFeedback(this)
        }
    }

    /** Crops [source] into a circle — same algorithm as mobile BitmapUtils.circular (separate Gradle modules). */
    fun circularBitmap(source: Bitmap): Bitmap {
        val size = minOf(source.width, source.height).coerceAtLeast(1)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawOval(RectF(0f, 0f, size.toFloat(), size.toFloat()), paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val left = (source.width - size) / 2f
        val top = (source.height - size) / 2f
        canvas.drawBitmap(source, -left, -top, paint)
        return output
    }
}
