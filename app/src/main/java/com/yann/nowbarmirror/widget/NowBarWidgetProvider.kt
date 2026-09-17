package com.yann.nowbarmirror.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import com.yann.nowbarmirror.MirrorNotificationListener
import com.yann.nowbarmirror.R
import com.yann.nowbarmirror.settings.WidgetActionsPrefs
import com.yann.nowbarmirror.sport.SofascoreNotificationListenerService

/** One notification action, rendered as a small text button (the action's own label) in the widget. */
data class WidgetAction(
    val label: String,
    val pendingIntent: PendingIntent
)

/**
 * One Sofascore match as pushed from SofascoreNotificationListenerService.pushWidgetMatches — see
 * its doc. [image] is Sofascore's own combined notification image (may be null, see
 * NotificationImageExtractor) and [contentIntent] is that SPECIFIC notification's own live
 * PendingIntent — tapping a match tile must open Sofascore on that exact match (same as tapping
 * the notification itself) WITHOUT cancelling the source notification, see
 * applySofascoreMatches. Neither [image] nor [contentIntent] is persisted (see
 * SofascoreWidgetStore) — only held in memory for the current process, same limitation as
 * [WidgetAction] above and the mirror's own liveContentIntent.
 */
data class SofascoreWidgetMatch(
    val key: String,
    val homeTeam: String,
    val awayTeam: String,
    val homeScore: String?,
    val awayScore: String?,
    val lastScorer: String?,
    val status: String,
    val apiSource: String,
    val postTimeMillis: Long,
    val image: Bitmap?,
    val contentIntent: PendingIntent?
)

/**
 * Sorts [this] with priority to live/recently-finished matches, most-recently-notified first
 * within each bucket — a match SofascoreMatchPresentation.isMatchFinished() considers finished
 * AND whose last notification was more than 5 minutes ago drops into the second bucket (Yann:
 * "priorité aux matches en cours, un match fini depuis plus de 5 minutes passe après"). Generic
 * over [T] so it applies both to SofascoreWidgetMatch (push time, in
 * NowBarWidgetProvider.pushSofascoreMatches) and to SofascoreWidgetStore.Data (render time, in
 * buildViewsUnsafe) — see pushSofascoreMatches for why it's applied at BOTH points rather than
 * just once at push time: re-sorting the same already-capped set on every render keeps their
 * RELATIVE order (live vs. finished-a-while-ago) accurate as time passes, even between Sofascore
 * events, without needing a periodic background refresh (this app has none, by design — see
 * WidgetNotificationStore's own class doc for the same reasoning elsewhere).
 */
private fun <T> List<T>.sortedForWidget(
    nowMillis: Long,
    statusOf: (T) -> String,
    apiSourceOf: (T) -> String,
    postTimeOf: (T) -> Long
): List<T> {
    fun isDemoted(item: T): Boolean {
        if (!SofascoreMatchPresentation.isMatchFinished(statusOf(item))) return false
        return nowMillis - postTimeOf(item) > 5 * 60_000L
    }
    return sortedWith(compareBy<T> { isDemoted(it) }.thenByDescending { postTimeOf(it) })
}

/**
 * Home-screen App Widget (4x1, transparent background) meant to be placed on the lock screen
 * through a third-party lock-widget host such as Samsung's LockStar. Two views, switched by the
 * rotating-arrows button (see widget_now_bar.xml's class doc and applyViewToggle below):
 * - the ORIGINAL "last notification" view: whatever WidgetNotificationStore currently holds, the
 *   single most recently posted notification from any app configured with a mirror mode, across
 *   ALL/LATEST alike.
 * - the NEW "Sport" view: up to 4 Sofascore matches from SofascoreWidgetStore, pushed by
 *   SofascoreNotificationListenerService whenever a Sofascore notification changes.
 */
class NowBarWidgetProvider : AppWidgetProvider() {

    companion object {

        private const val ACTION_TOGGLE_VIEW = "com.yann.nowbarmirror.widget.ACTION_TOGGLE_VIEW"

        // Actions are deliberately NOT persisted to WidgetNotificationStore/SharedPreferences,
        // for the same reason the content PendingIntent isn't (see the class doc on
        // WidgetNotificationStore): a PendingIntent only survives a real Binder transaction
        // (handing it straight to AppWidgetManager here), not a round-trip through disk. So
        // these live only in memory, for as long as this process stays alive since the
        // notification was posted. liveActionsKey guards against showing them for the wrong
        // notification: buildViewsUnsafe() only uses liveActions when it matches the key
        // WidgetNotificationStore currently holds, so a stale set from a previous notification
        // (or the empty default right after a process restart) never leaks onto an unrelated
        // one — the actions row just stays hidden until the next live push.
        private var liveActions: List<WidgetAction> = emptyList()
        private var liveActionsKey: String? = null

        // Same in-memory-only trick as liveActions above, for the Sofascore view: one live
        // PendingIntent per match key, refreshed on every pushSofascoreMatches call. A match
        // whose key isn't in this map (stale after a process restart, or never had a usable
        // contentIntent) falls back to launchAppPendingIntent(SOFASCORE_PACKAGE) — see
        // applySofascoreMatches.
        private var liveSofascoreIntents: Map<String, PendingIntent> = emptyMap()

        /**
         * Called right when a notification is mirrored, with THAT notification's own live
         * PendingIntent(s). This is the only reliable way to give the widget a working "open
         * the exact conversation/article" tap and working action buttons: handing a
         * PendingIntent to AppWidgetManager here goes through a real Binder transaction (same
         * as NotificationManager.notify() already does for the system-notification mirror),
         * which is what actually preserves it — trying to save and later reconstruct a
         * PendingIntent from SharedPreferences does not.
         */
        fun pushLive(
            context: Context,
            key: String,
            title: String,
            text: String,
            packageName: String,
            contentIntent: PendingIntent?,
            image: Bitmap?,
            actions: List<WidgetAction> = emptyList()
        ) {
            WidgetNotificationStore.save(context, key, title, text, packageName, image)
            liveActions = actions
            liveActionsKey = key
            pushToAllWidgets(context, buildViews(context, liveContentIntent = contentIntent))
        }

        /**
         * Called by SofascoreNotificationListenerService.pushWidgetMatches whenever a Sofascore
         * notification is posted or removed, with EVERY currently active Sofascore notification
         * turned into a match (not just the one the watch complication follows — see that
         * function's doc for why the API-override system doesn't apply here). Sorts + caps to
         * SofascoreWidgetStore.MAX_SLOTS (see sortedForWidget above), keeps the live
         * PendingIntents in memory for the KEPT matches only, persists the rest, then rebuilds
         * whichever view is currently showing (a Sport-view rebuild picks the new matches up
         * immediately; a latest-notif-view rebuild only needs this to recompute whether the
         * toggle button should now be visible — see applyViewToggle).
         */
        fun pushSofascoreMatches(context: Context, matches: List<SofascoreWidgetMatch>) {
            val kept = matches.sortedForWidget(
                nowMillis = System.currentTimeMillis(),
                statusOf = { it.status },
                apiSourceOf = { it.apiSource },
                postTimeOf = { it.postTimeMillis }
            ).take(SofascoreWidgetStore.MAX_SLOTS)

            liveSofascoreIntents = kept.mapNotNull { m -> m.contentIntent?.let { m.key to it } }.toMap()

            SofascoreWidgetStore.save(
                context,
                kept.map { m ->
                    SofascoreWidgetStore.PersistableMatch(
                        key = m.key,
                        homeTeam = m.homeTeam,
                        awayTeam = m.awayTeam,
                        homeScore = m.homeScore,
                        awayScore = m.awayScore,
                        lastScorer = m.lastScorer,
                        status = m.status,
                        apiSource = m.apiSource,
                        postTimeMillis = m.postTimeMillis,
                        image = m.image
                    )
                }
            )

            pushToAllWidgets(context, buildViews(context))
        }

        /**
         * Rebuilds and pushes from whatever the stores currently hold, with no live
         * PendingIntent available (used when just clearing the widget, refreshing after a
         * staleness check, or when the system calls onUpdate() independently of any specific
         * notification event). In the latest-notif view the tap falls back to opening the
         * source app, and the actions row — which has no such fallback — simply stays hidden;
         * in the Sofascore view, a match tile falls back the same way (see
         * applySofascoreMatches).
         */
        fun requestUpdate(context: Context) {
            pushToAllWidgets(context, buildViews(context))
        }

        private fun pushToAllWidgets(context: Context, views: RemoteViews) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NowBarWidgetProvider::class.java))
            if (ids.isEmpty()) return
            ids.forEach { id -> manager.updateAppWidget(id, views) }
        }

        private fun buildViews(context: Context, liveContentIntent: PendingIntent? = null): RemoteViews {
            return try {
                buildViewsUnsafe(context, liveContentIntent)
            } catch (t: Throwable) {
                // TEMPORARY diagnostic: surfaces the exact failure on screen since this device
                // can't be hooked up to Android Studio for logcat. Safe to remove once the
                // widget rendering path is confirmed stable — until then, a fallback empty
                // widget is returned so this can never crash the shared app process.
                Toast.makeText(
                    context,
                    "Widget: ${t.javaClass.simpleName}: ${t.message}",
                    Toast.LENGTH_LONG
                ).show()
                emptyViews(context)
            }
        }

        /** Ultra-safe fallback used only from buildViews()'s catch block — everything below is deliberately re-derived rather than shared with applyLatestContent, so a bug in THAT function can never take this fallback down with it. */
        private fun emptyViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_now_bar)
            views.setViewVisibility(R.id.widget_sofascore_content, View.GONE)
            views.setViewVisibility(R.id.widget_latest_content, View.VISIBLE)
            views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_empty_title))
            views.setTextViewText(R.id.widget_text, "")
            views.setImageViewResource(R.id.widget_app_icon, R.drawable.ic_stat_mirror)
            views.setViewVisibility(R.id.widget_image, View.GONE)
            views.setViewVisibility(R.id.widget_dismiss, View.GONE)
            views.setViewVisibility(R.id.widget_actions_container, View.GONE)
            views.setViewVisibility(R.id.widget_view_toggle, View.GONE)
            return views
        }

        private fun buildViewsUnsafe(context: Context, liveContentIntent: PendingIntent?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_now_bar)

            val sofascoreMatches = SofascoreWidgetStore.get(context).sortedForWidget(
                nowMillis = System.currentTimeMillis(),
                statusOf = { it.status },
                apiSourceOf = { it.apiSource },
                postTimeOf = { it.postTimeMillis }
            )
            val showSofascore = WidgetViewModePrefs.isSofascoreActive(context)

            views.setViewVisibility(R.id.widget_latest_content, if (showSofascore) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.widget_sofascore_content, if (showSofascore) View.VISIBLE else View.GONE)

            if (showSofascore) {
                applySofascoreIcon(context, views)
                applySofascoreMatches(context, views, sofascoreMatches)
            } else {
                applyLatestContent(context, views, liveContentIntent)
            }

            applyViewToggle(context, views, showingSofascore = showSofascore, hasSofascoreMatches = sofascoreMatches.isNotEmpty())

            return views
        }

        private fun applyLatestContent(context: Context, views: RemoteViews, liveContentIntent: PendingIntent?) {
            val data = WidgetNotificationStore.get(context)
            if (data == null) {
                views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_empty_title))
                views.setTextViewText(R.id.widget_text, "")
                views.setImageViewResource(R.id.widget_app_icon, R.drawable.ic_stat_mirror)
                views.setViewVisibility(R.id.widget_image, View.GONE)
                views.setViewVisibility(R.id.widget_dismiss, View.GONE)
                views.setViewVisibility(R.id.widget_actions_container, View.GONE)
                return
            }

            views.setTextViewText(R.id.widget_title, data.title)
            views.setTextViewText(R.id.widget_text, data.text)

            val appIcon = appIconBitmap(context, data.packageName)
            if (appIcon != null) {
                views.setImageViewBitmap(R.id.widget_app_icon, circularBitmap(appIcon))
            } else {
                views.setImageViewResource(R.id.widget_app_icon, R.drawable.ic_stat_mirror)
            }

            val imageBitmap = data.imageFile?.let { BitmapFactory.decodeFile(it.path) }
            if (imageBitmap != null) {
                views.setImageViewBitmap(R.id.widget_image, circularBitmap(imageBitmap))
                views.setViewVisibility(R.id.widget_image, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_image, View.GONE)
            }

            views.setViewVisibility(R.id.widget_dismiss, View.VISIBLE)
            views.setOnClickPendingIntent(R.id.widget_dismiss, dismissPendingIntent(context, data.key))

            applyActions(context, views, data.key)

            // Bound to widget_latest_content specifically (not the whole widget_root any more —
            // widget_root also contains the left column, shared with the Sofascore view, which
            // must NOT open this notification when tapped).
            val openIntent = liveContentIntent ?: launchAppPendingIntent(context, data.packageName)
            if (openIntent != null) {
                views.setOnClickPendingIntent(R.id.widget_latest_content, openIntent)
            }
        }

        private fun applySofascoreIcon(context: Context, views: RemoteViews) {
            val icon = appIconBitmap(context, SofascoreNotificationListenerService.SOFASCORE_PACKAGE)
            if (icon != null) {
                views.setImageViewBitmap(R.id.widget_app_icon, circularBitmap(icon))
            } else {
                views.setImageViewResource(R.id.widget_app_icon, R.drawable.ic_stat_mirror)
            }
        }

        private val SOFASCORE_SLOT_IDS = listOf(R.id.widget_match_1, R.id.widget_match_2, R.id.widget_match_3, R.id.widget_match_4)
        private val SOFASCORE_SLOT_IMAGE_IDS = listOf(R.id.widget_match_1_image, R.id.widget_match_2_image, R.id.widget_match_3_image, R.id.widget_match_4_image)
        private val SOFASCORE_SLOT_SCORE_IDS = listOf(R.id.widget_match_1_score, R.id.widget_match_2_score, R.id.widget_match_3_score, R.id.widget_match_4_score)
        private val SOFASCORE_SLOT_PERIOD_IDS = listOf(R.id.widget_match_1_period, R.id.widget_match_2_period, R.id.widget_match_3_period, R.id.widget_match_4_period)

        /**
         * Fills the up-to-4 fixed match slots (see widget_now_bar.xml's class doc for why fixed
         * slots rather than a RemoteViews collection). [matches] is already sorted by
         * sortedForWidget by the caller (buildViewsUnsafe) and already capped to
         * SofascoreWidgetStore.MAX_SLOTS at push time, so this just walks the slots in order.
         */
        private fun applySofascoreMatches(context: Context, views: RemoteViews, matches: List<SofascoreWidgetStore.Data>) {
            views.setViewVisibility(R.id.widget_sofascore_empty, if (matches.isEmpty()) View.VISIBLE else View.GONE)

            for (i in SOFASCORE_SLOT_IDS.indices) {
                val slotId = SOFASCORE_SLOT_IDS[i]
                val match = matches.getOrNull(i)
                if (match == null) {
                    views.setViewVisibility(slotId, View.GONE)
                    continue
                }
                views.setViewVisibility(slotId, View.VISIBLE)

                val imageBitmap = match.imageFile?.let { BitmapFactory.decodeFile(it.path) }
                if (imageBitmap != null) {
                    views.setImageViewBitmap(SOFASCORE_SLOT_IMAGE_IDS[i], imageBitmap)
                } else {
                    views.setImageViewResource(SOFASCORE_SLOT_IMAGE_IDS[i], R.drawable.bg_sofascore_placeholder)
                }

                views.setTextViewText(
                    SOFASCORE_SLOT_SCORE_IDS[i],
                    SofascoreMatchPresentation.scoreText(match.homeScore, match.awayScore, match.lastScorer)
                )
                views.setTextViewText(
                    SOFASCORE_SLOT_PERIOD_IDS[i],
                    SofascoreMatchPresentation.periodLabel(match.status, match.apiSource, kickoffEpochMillis = null)
                )

                // Same live-PendingIntent-first, launch-the-app-as-fallback pattern as the
                // latest-notif view (applyLatestContent) — see liveSofascoreIntents' doc above.
                // Crucially, this is Sofascore's OWN contentIntent, the exact same one Sofascore
                // itself uses — opening it does NOT cancel the source notification (that only
                // happens via the dedicated dismiss button in the latest-notif view, which this
                // view deliberately has none of, see widget_now_bar.xml).
                val openIntent = liveSofascoreIntents[match.key]
                    ?: launchAppPendingIntent(context, SofascoreNotificationListenerService.SOFASCORE_PACKAGE)
                if (openIntent != null) {
                    views.setOnClickPendingIntent(slotId, openIntent)
                }
            }
        }

        /**
         * In the latest-notif view, only offer the toggle once there's actually a Sport view
         * worth switching to (mirrors how widget_actions_container stays hidden with nothing to
         * show, see applyActions). In the Sofascore view it always stays visible so Yann can
         * always get back — even if the matches shown just dropped to zero while he was looking
         * at it (see widget_sofascore_empty in applySofascoreMatches for that case).
         */
        private fun applyViewToggle(context: Context, views: RemoteViews, showingSofascore: Boolean, hasSofascoreMatches: Boolean) {
            val visible = showingSofascore || hasSofascoreMatches
            views.setViewVisibility(R.id.widget_view_toggle, if (visible) View.VISIBLE else View.GONE)
            if (visible) {
                views.setOnClickPendingIntent(R.id.widget_view_toggle, toggleViewPendingIntent(context))
            }
        }

        private fun toggleViewPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, NowBarWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_VIEW
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * Populates up to three text action buttons when the option is enabled in Settings AND
         * we still hold live PendingIntents for THIS exact notification (see the liveActions
         * doc above) — otherwise hides the whole row rather than showing dead buttons. Text
         * (the action's own label, e.g. "Répondre") rather than an icon: most notification
         * action icons are meant to be tinted/rendered by the system itself, so a raw icon
         * dropped into the widget was often unreadable — the label reads reliably regardless.
         */
        private fun applyActions(context: Context, views: RemoteViews, dataKey: String) {
            val actions = if (WidgetActionsPrefs.isEnabled(context) && liveActionsKey == dataKey) {
                liveActions
            } else {
                emptyList()
            }

            val slots = listOf(
                R.id.widget_action_1 to actions.getOrNull(0),
                R.id.widget_action_2 to actions.getOrNull(1),
                R.id.widget_action_3 to actions.getOrNull(2)
            )
            for ((viewId, action) in slots) {
                if (action == null) {
                    views.setViewVisibility(viewId, View.GONE)
                    continue
                }
                views.setViewVisibility(viewId, View.VISIBLE)
                views.setTextViewText(viewId, action.label)
                views.setOnClickPendingIntent(viewId, action.pendingIntent)
            }

            views.setViewVisibility(
                R.id.widget_actions_container,
                if (actions.isEmpty()) View.GONE else View.VISIBLE
            )
        }

        private fun dismissPendingIntent(context: Context, key: String): PendingIntent {
            val intent = Intent(context, MirrorNotificationListener::class.java).apply {
                action = MirrorNotificationListener.ACTION_DISMISS_WIDGET
                putExtra(MirrorNotificationListener.EXTRA_DISMISS_KEY, key)
            }
            return PendingIntent.getService(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * Fallback used whenever there's no live PendingIntent to attach (i.e. every render
         * that isn't happening right at the moment a notification was posted): opens the
         * source app itself rather than leaving the tap dead. Shared by both views —
         * applyLatestContent (the mirrored app) and applySofascoreMatches (Sofascore itself).
         */
        private fun launchAppPendingIntent(context: Context, packageName: String): PendingIntent? {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return null
            return PendingIntent.getActivity(
                context,
                packageName.hashCode(),
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun appIconBitmap(context: Context, packageName: String): Bitmap? {
            return try {
                drawableToBitmap(context.packageManager.getApplicationIcon(packageName))
            } catch (_: Throwable) {
                null
            }
        }

        private fun drawableToBitmap(drawable: Drawable): Bitmap {
            if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
            val width = drawable.intrinsicWidth.coerceAtLeast(1)
            val height = drawable.intrinsicHeight.coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            return bitmap
        }

        /** Crops [source] into a circle, matching the round chips in the reference design. */
        private fun circularBitmap(source: Bitmap): Bitmap {
            val size = minOf(source.width, source.height).coerceAtLeast(1)
            val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.drawOval(RectF(Rect(0, 0, size, size)), paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            val left = (source.width - size) / 2f
            val top = (source.height - size) / 2f
            canvas.drawBitmap(source, -left, -top, paint)
            return output
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = buildViews(context)
        appWidgetIds.forEach { id -> appWidgetManager.updateAppWidget(id, views) }
    }

    /**
     * Catches the view-toggle broadcast (see toggleViewPendingIntent) before falling through to
     * AppWidgetProvider's own onReceive, which is what normally dispatches to onUpdate/
     * onDeleted/etc. — same "handle our own action, then let the superclass handle everything
     * else" shape as MirrorNotificationListener.onStartCommand's ACTION_DISMISS_WIDGET handling.
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE_VIEW) {
            WidgetViewModePrefs.toggle(context)
            pushToAllWidgets(context, buildViews(context))
            return
        }
        super.onReceive(context, intent)
    }
}
