package com.yann.nowbarmirror.wear

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Galaxy Watch-style building blocks shared by NotificationDetailActivity and MessagesActivity
 * (extracted 24/09/2026 so both screens stay identical — see NotificationDetailActivity's doc for
 * the history of each style choice: full-width single-line pills, system text appearances, press
 * feedback = scale 0.94 + lighter state_pressed background).
 */
object DetailViews {

    /** Launch intent of the source app's own watch version, if installed (same package name as on the phone). */
    fun watchLaunchIntent(context: Context, packageName: String): Intent? {
        if (packageName.isBlank()) return null
        return try {
            context.packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } catch (_: Exception) {
            null
        }
    }

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

    /**
     * App row at the top of the Messages and Sport screens (UPDATED 25/09/2026, shared): ONE
     * horizontally scrollable line, no "watch"/"phone" split any more. Order = the phone's order.
     * Tap opens the app on the watch when it's installed there, otherwise on the phone
     * ([PhoneRelay.sendOpenAppOnPhone]). Each icon carries its count as a red badge.
     */
    fun appIconRow(context: Context, apps: List<MessageApp>, extraSideFraction: Float = 0f): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        apps.forEach { row.addView(appIconView(context, it)) }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true            // few icons: centered; many: scrolls sideways
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(context, 10)
                // Screens with narrower side insets (Sport) keep the row inside the top of the circle.
                val extra = (context.resources.displayMetrics.widthPixels * extraSideFraction).toInt()
                leftMargin = extra
                rightMargin = extra
            }
        }
    }

    /** One icon cell: ~78 % of the screen width / 4 (a row of 4 fits the top of the circle), capped at 50dp. */
    private fun appIconView(context: Context, app: MessageApp): View {
        val cellSize = minOf((context.resources.displayMetrics.widthPixels * 0.78f / 4).toInt(), dp(context, 50))
        val iconSize = (cellSize * 0.8f).toInt()
        val badgeHeight = (cellSize * 0.4f).toInt()
        val cell = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(cellSize, cellSize)
            contentDescription = app.label
        }
        val icon = app.icon ?: watchIcon(context, app.packageName)
        cell.addView(ImageView(context).apply {
            icon?.let { setImageBitmap(circularBitmap(it)) }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.BOTTOM or Gravity.START).apply {
            bottomMargin = dp(context, 1)
        })
        if (app.count > 0) {
            cell.addView(TextView(context).apply {
                text = if (app.count > 99) "99+" else app.count.toString()
                setTextColor(context.resources.getColor(R.color.detail_text_primary, context.theme))
                setTextSize(TypedValue.COMPLEX_UNIT_PX, badgeHeight * 0.55f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                minWidth = badgeHeight
                setPadding(dp(context, 3), 0, dp(context, 3), 0)
                setBackgroundResource(R.drawable.bg_count_badge)
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, badgeHeight, Gravity.TOP or Gravity.END))
        }
        addPressFeedback(cell)
        cell.isClickable = true
        cell.setOnClickListener {
            val launch = watchLaunchIntent(context, app.packageName)
            if (launch != null) {
                try {
                    context.startActivity(launch)
                } catch (_: Exception) {
                }
            } else {
                PhoneRelay.sendOpenAppOnPhone(context.applicationContext, app.packageName)
            }
        }
        return cell
    }

    /** Fallback when the phone sent no icon: the watch app's own icon. */
    private fun watchIcon(context: Context, packageName: String): Bitmap? = try {
        val drawable = context.packageManager.getApplicationIcon(packageName)
        val size = dp(context, 40).coerceAtLeast(1)
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            val canvas = Canvas(it)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
        }
    } catch (_: Exception) {
        null
    }

    /** Small round icon button (Sport rows, 25/09/2026): same grey disc + pressed state as the delete button. */
    fun roundIconButton(context: Context, iconRes: Int, description: String, sizeDp: Int, onClick: () -> Unit): ImageButton =
        ImageButton(context).apply {
            setImageResource(iconRes)
            setBackgroundResource(R.drawable.bg_delete_button)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = dp(context, sizeDp) / 4
            setPadding(pad, pad, pad, pad)
            contentDescription = description
            layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
            setOnClickListener { onClick() }
            addPressFeedback(this)
        }

    /** Horizontal line between the app row and the list. */
    fun separator(context: Context): View = View(context).apply {
        setBackgroundColor(context.resources.getColor(R.color.detail_chip_bg, context.theme))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 1)).apply {
            topMargin = dp(context, 6)
            bottomMargin = dp(context, 20)
        }
    }

    /** "Nothing to show" text of the list screens. */
    fun emptyText(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Large)
        setTextColor(context.resources.getColor(R.color.detail_text_primary, context.theme))
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    /**
     * Round-screen insets as fractions of the display (Wear OS guidance), shared by the list screens:
     * sides ~10 %, top ~13 %, bottom ~25 % so the last row can be scrolled up to the middle.
     */
    fun applyRoundInsets(container: View, sideFraction: Float = 0.10f) {
        val metrics = container.resources.displayMetrics
        val side = (metrics.widthPixels * sideFraction).toInt()
        container.setPadding(side, (metrics.heightPixels * 0.13f).toInt(), side, (metrics.heightPixels * 0.25f).toInt())
    }
}
