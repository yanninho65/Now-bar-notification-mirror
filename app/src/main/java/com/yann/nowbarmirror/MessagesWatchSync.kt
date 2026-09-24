package com.yann.nowbarmirror

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import android.util.LruCache
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.yann.nowbarmirror.settings.MessageAppsPrefs
import com.yann.nowbarmirror.settings.ServicePrefs
import com.yann.nowbarmirror.sport.WatchSync
import com.yann.nowbarmirror.widget.WidgetAction

/**
 * NEW 24/09/2026 — feeds the watch "Messages" complication (wear/MessagesComplicationService.kt)
 * and its list screen (wear/MessagesActivity.kt). Read straight from the notification center
 * (MirrorNotificationListener's getActiveNotifications()), no separate store: every active,
 * non-summary notification of an app chosen in [MessageAppsPrefs], most recent first, capped at
 * [MAX_MESSAGES]. Sent on "/messages" as one DataMap:
 * - "messages": DataMap list (key, postTimeMillis, packageName, title, text, detailLines, actionLabels);
 * - "apps": DataMap list (packageName, label, count) — every selected message app installed on the
 *   phone, with its unread count ([unreadCount]); the watch splits them into "on watch"/"on phone" rows;
 * - images as TOP-LEVEL assets ("img_<index>", "icon_<package>") — not nested in the list items;
 * - "timestamp" (freshness key, see wear/PhoneDataLayer.FreshStore).
 * The "exclude the message already shown by the Notification complication" rule is applied on the
 * watch (it alone knows whether that complication is placed).
 *
 * Deduped by a text signature; images/assets cached per posting (key+postTime) so an unchanged
 * message is never re-extracted/re-encoded.
 */
object MessagesWatchSync {

    const val PATH = "/messages"
    private const val MAX_MESSAGES = 10
    private const val MAX_LINES = 10
    private const val MAX_ACTIONS = 3

    @Volatile
    private var lastSignature: String? = null
    private val imageAssets = LruCache<String, Any>(MAX_MESSAGES * 2)   // Asset, or NO_IMAGE
    private val NO_IMAGE = Any()

    // Mail apps: counted per distinct subject instead of per sender (24/09/2026). A package is also
    // treated as mail when one of its notifications has CATEGORY_EMAIL.
    private val EMAIL_PACKAGES = setOf(
        "com.google.android.gm",
        "com.samsung.android.email.provider",
        "com.microsoft.office.outlook",
        "ch.protonmail.android",
        "com.yahoo.mobile.client.android.mail"
    )

    private fun isSummary(sbn: StatusBarNotification) =
        sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0

    /** Every non-ongoing notification (summaries included) of the selected message apps. */
    private fun messageAppNotifications(context: Context, active: Array<StatusBarNotification>?): List<StatusBarNotification> {
        val apps = MessageAppsPrefs.get(context)
        if (active == null || apps.isEmpty()) return emptyList()
        return active.filter { it.packageName in apps && it.packageName != context.packageName && !it.isOngoing }
    }

    /** Message-app notifications currently in the shade (no group summaries), most recent first. */
    fun collect(context: Context, active: Array<StatusBarNotification>?): List<StatusBarNotification> =
        messageAppNotifications(context, active)
            .filterNot(::isSummary)
            .sortedByDescending { it.postTime }
            .take(MAX_MESSAGES)

    /**
     * Badge count of one app (24/09/2026, Yann : "3 messages de la même personne comptent pour un
     * hormis pour les mails où ça dépend du nombre d'objets différents"):
     * - messaging: distinct conversations (shortcutId, else conversation title, else title);
     * - mail: distinct subjects — InboxStyle lines when present, else EXTRA_TEXT (Gmail: the subject).
     * Group summaries are ignored unless they're all the app posted (then their lines are used).
     */
    private fun unreadCount(pkg: String, notifs: List<StatusBarNotification>): Int {
        if (notifs.isEmpty()) return 0
        val children = notifs.filterNot(::isSummary)
        val email = pkg in EMAIL_PACKAGES || notifs.any { it.notification.category == Notification.CATEGORY_EMAIL }
        if (!email) {
            return children.map { sbn ->
                val extras = sbn.notification.extras
                sbn.notification.shortcutId
                    ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.takeIf { it.isNotBlank() }
                    ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
                    ?: sbn.key
            }.toSet().size
        }
        val source = children.ifEmpty { notifs }
        return source.flatMap { sbn ->
            val extras = sbn.notification.extras
            val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.mapNotNull { it?.toString()?.trim()?.takeIf { l -> l.isNotEmpty() } }
                .orEmpty()
            lines.ifEmpty {
                listOf(
                    extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                        ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                        ?: sbn.key
                )
            }
        }.toSet().size
    }

    private data class AppEntry(val packageName: String, val label: String, val count: Int)

    /** Selected message apps installed (launchable) on the phone, with counts, in Yann's order (MessageAppsActivity, 24/09/2026). */
    private fun appEntries(context: Context, active: Array<StatusBarNotification>?): List<AppEntry> {
        val pm = context.packageManager
        val notifs = messageAppNotifications(context, active).groupBy { it.packageName }
        return MessageAppsPrefs.getOrdered(context)
            .filter { it != context.packageName && pm.getLaunchIntentForPackage(it) != null }
            .map { pkg -> AppEntry(pkg, appName(context, pkg), unreadCount(pkg, notifs[pkg].orEmpty())) }
    }

    /**
     * The notification's own action buttons usable from the watch. Actions with a RemoteInput
     * (inline "Reply") are skipped: text input isn't relayed, firing them empty would do nothing
     * useful. The watch's actionIndex refers to THIS filtered list (recomputed at fire time).
     */
    fun actionsFor(notification: Notification): List<WidgetAction> =
        notification.actions.orEmpty()
            .filter { it.remoteInputs.isNullOrEmpty() }
            .mapNotNull { WidgetAction.from(it) }
            .take(MAX_ACTIONS)

    fun titleOf(context: Context, sbn: StatusBarNotification): String {
        val extras = sbn.notification.extras
        return extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.takeIf { it.isNotBlank() }
            ?: appName(context, sbn.packageName)
    }

    fun textOf(sbn: StatusBarNotification): String {
        val extras = sbn.notification.extras
        return extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: ""
    }

    /** Conversation messages, most recent first, sender prefixed in group chats — shared with MirrorNotificationListener. */
    fun messageLines(sbn: StatusBarNotification, conversationTitle: String, max: Int = MAX_LINES): List<String> {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification)
            ?: return emptyList()
        val all = style.historicMessages + style.messages
        return all.takeLast(max).reversed().mapNotNull { message ->
            val text = message.text?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val sender = message.person?.name?.toString()?.takeIf { it.isNotBlank() && it != conversationTitle }
            if (sender != null) "$sender : $text" else text
        }
    }

    /** Builds and pushes the current list (no-op if unchanged since the last push). Call on the listener's main thread. */
    fun sync(context: Context, active: Array<StatusBarNotification>?) {
        val enabled = ServicePrefs.isEnabled(context)
        val messages = if (enabled) collect(context, active) else emptyList()
        val apps = appEntries(context, if (enabled) active else null)

        data class Item(val sbn: StatusBarNotification, val title: String, val text: String, val lines: List<String>, val actions: List<String>)
        val items = messages.map { sbn ->
            val title = titleOf(context, sbn)
            Item(sbn, title, textOf(sbn), messageLines(sbn, title), actionsFor(sbn.notification).map { it.label })
        }
        val signature = items.joinToString("\u0001") {
            listOf(it.sbn.key, it.sbn.postTime, it.title, it.text, it.lines.joinToString("\u0002"), it.actions.joinToString("\u0002"))
                .joinToString("\u0003")
        } + "\u0004" + apps.joinToString("\u0001") { "${it.packageName}\u0003${it.label}\u0003${it.count}" }
        if (signature == lastSignature) return

        try {
            val request = PutDataMapRequest.create(PATH).apply {
                val list = ArrayList<DataMap>()
                val iconPackages = LinkedHashSet<String>()
                items.forEachIndexed { index, item ->
                    list.add(DataMap().apply {
                        putString("key", item.sbn.key)
                        putLong("postTimeMillis", item.sbn.postTime)
                        putString("packageName", item.sbn.packageName)
                        putString("title", item.title)
                        putString("text", item.text)
                        putStringArrayList("detailLines", ArrayList(item.lines))
                        putStringArrayList("actionLabels", ArrayList(item.actions))
                    })
                    imageAsset(context, item.sbn)?.let { dataMap.putAsset("img_$index", it) }
                    iconPackages.add(item.sbn.packageName)
                }
                val appList = ArrayList<DataMap>()
                apps.forEach { app ->
                    appList.add(DataMap().apply {
                        putString("packageName", app.packageName)
                        putString("label", app.label)
                        putInt("count", app.count)
                    })
                    iconPackages.add(app.packageName)
                }
                dataMap.putDataMapArrayList("apps", appList)
                iconPackages.forEach { pkg ->
                    BitmapUtils.AppIcons.get(context, pkg)?.let { dataMap.putAsset("icon_$pkg", WatchSync.bitmapToAsset(it)) }
                }
                dataMap.putDataMapArrayList("messages", list)
                dataMap.putLong("timestamp", System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context).putDataItem(request)
            lastSignature = signature
        } catch (_: Throwable) {
            // Best effort, like the other watch syncs.
        }
    }

    private fun imageAsset(context: Context, sbn: StatusBarNotification): Asset? {
        val cacheKey = "${sbn.key}@${sbn.postTime}"
        imageAssets.get(cacheKey)?.let { return it as? Asset }
        val asset = NotificationImageExtractor.extract(context, sbn)?.let { WatchSync.bitmapToAsset(it) }
        imageAssets.put(cacheKey, asset ?: NO_IMAGE)
        return asset
    }

    private fun appName(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }
}
