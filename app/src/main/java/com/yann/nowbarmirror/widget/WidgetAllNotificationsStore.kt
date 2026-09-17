package com.yann.nowbarmirror.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Persists the widget's "Toutes notifs" view (see NowBarWidgetProvider.pushToAllNotifications /
 * applyAllNotifs, added 17/09/2026 at Yann's request: "Sur la droite, ajouter un bouton pour
 * changer vue entre sport et toutes notifs. [...] prends les cinq dernieres notifs reçues.").
 *
 * A rolling HISTORY of up to [MAX_SLOTS] notifications RECEIVED, most-recent-first — LIKE
 * WidgetNotificationStore/SofascoreWidgetStore (and UNLIKE an initial version of this store), an
 * entry is removed as soon as its original notification is dismissed from the shade, by the user,
 * the source app, or "effacer tout" (Yann, 17/09/2026: "Si une notification a été supprimée du
 * centre de notifs, elle ne doit plus apparaître dans le widget") — see [remove], called from
 * MirrorNotificationListener/SofascoreNotificationListenerService's onNotificationRemoved. "History"
 * here just means it can hold MORE than one entry per source and isn't limited to whichever
 * notification is single most recent (unlike WidgetNotificationStore) or currently active for a
 * SPECIFIC match the watch complication follows (unlike SofascoreWidgetStore's override system) —
 * it does NOT mean entries survive their own dismissal. An entry with the same [key] as one
 * already present is treated as an UPDATE of that same notification (e.g. a Sofascore score change
 * posted in place) and is bumped back to the front rather than creating a duplicate.
 *
 * Two kinds of entry, both fed into this SAME shared store so they interleave by recency:
 * - [Kind.GENERIC]: any notification mirrored by MirrorNotificationListener (any app configured
 *   with a mirror mode, ALL or LATEST alike — see its mirror()), pushed once per received event.
 * - [Kind.SOFASCORE_MATCH]: a Sofascore notification, pushed by
 *   com.yann.nowbarmirror.sport.SofascoreNotificationListenerService.onNotificationPosted using
 *   the SAME parsed match data (teams/score/status) as the dedicated Sport view — this is what
 *   lets a Sofascore entry keep exactly today's match-tile presentation instead of the generic
 *   image+title one (see NowBarWidgetProvider.applyAllNotifs), without this store or the generic
 *   mirror listener needing to know anything about Sofascore's own parsing.
 *
 * IDENTITY (fixed 17/09/2026 — Yann: "si plusieurs notifications ont été envoyées parmi les
 * applications marquées comme dernière notif, seule la dernière notif apparaît [...] ce
 * comportement ne doit être utilisé que pour la Now Bar, je veux que toutes les notifs puissent
 * apparaître dans cette barre"): an entry's identity here is the PAIR ([PersistableEntry.key],
 * [PersistableEntry.postTimeMillis]), NOT [PersistableEntry.key] alone. A "Dernière notif"-mode
 * app is typically set to that mode BECAUSE it keeps a single rolling Android notification (same
 * notification id/tag — i.e. the same [StatusBarNotification.getKey]) and just replaces its
 * content for each new item — that's the correct, intentional behavior for the actual Now Bar
 * mirror's shared LATEST slot (see MirrorNotificationListener), but collapsing every one of those
 * distinct notifications into a single "Toutes notifs" tile just because they share that reused
 * key was wrong: from Yann's point of view five different Le Monde articles are five different
 * received notifications, and should occupy up to five of this history's slots, exactly like an
 * "ALL"-mode app's five notifications would (those already got their own tiles, since apps in
 * ALL mode typically use a distinct key per item). [StatusBarNotification.postTime] changes on
 * every `notify()` call — including in-place updates to the same id — so pairing it with [key]
 * distinguishes "a genuinely new/updated posting" (new tile) from "the exact same posting being
 * re-pushed" (e.g. MirrorNotificationListener.rebuildStateFromActiveNotifications() re-mirroring
 * an already-active notification on listener reconnect — same key AND same postTime, so [push]
 * still updates that one tile in place rather than duplicating it).
 *
 * Storage mirrors SofascoreWidgetStore (JSON in SharedPreferences for the fields, one PNG file per
 * SLOT INDEX for images) but, being a history rather than a full-replace-every-time set, [push]
 * has to merge the new entry into whatever's already persisted rather than just being handed the
 * complete list already computed by the caller — so on every call it decodes the currently kept
 * images back to Bitmaps, merges/dedupes/caps in memory, then rewrites everything, same as
 * SofascoreWidgetStore.save() always does.
 */
object WidgetAllNotificationsStore {

    private const val PREFS_NAME = "widget_all_notifs_prefs"
    private const val KEY_ENTRIES = "entries"

    /** Kept in sync with NowBarWidgetProvider's fixed widget_notif_1..5 layout slots. */
    const val MAX_SLOTS = 5

    enum class Kind { GENERIC, SOFASCORE_MATCH }

    /** What [push] needs for one notification. Deliberately Android-widget-agnostic (no PendingIntent — see NowBarWidgetProvider.liveAllNotifIntents, which carries the live one of those but never persists it, same limitation as WidgetNotificationStore/SofascoreWidgetStore). */
    data class PersistableEntry(
        val key: String,
        val kind: Kind,
        val postTimeMillis: Long,
        // GENERIC fields
        val title: String? = null,
        val packageName: String? = null,
        // SOFASCORE_MATCH fields — same shape as SofascoreWidgetStore.PersistableMatch
        val homeTeam: String? = null,
        val awayTeam: String? = null,
        val homeScore: String? = null,
        val awayScore: String? = null,
        val lastScorer: String? = null,
        val status: String? = null,
        val apiSource: String? = null,
        val image: Bitmap?
    )

    data class Data(
        val key: String,
        val kind: Kind,
        val postTimeMillis: Long,
        val title: String?,
        val packageName: String?,
        val homeTeam: String?,
        val awayTeam: String?,
        val homeScore: String?,
        val awayScore: String?,
        val lastScorer: String?,
        val status: String?,
        val apiSource: String?,
        val imageFile: File?
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun imageFile(context: Context, slot: Int) =
        File(context.filesDir, "widget_all_notif_$slot.png")

    /**
     * Merges [entry] into the currently persisted history: the SAME (key, postTimeMillis) PAIR as
     * an existing entry updates it in place (bumped to the front — this is the "re-pushing the
     * exact same posting" case, see the class doc's IDENTITY section), otherwise it's inserted as
     * a NEW tile — even when [PersistableEntry.key] matches an existing entry's, since a
     * "Dernière notif"-mode app reusing its notification id for a new item is a genuinely new
     * received notification here, not an update of the old one. Capped to [MAX_SLOTS], dropping
     * the oldest beyond that. Returns the resulting list (already in the same order [get] would
     * return) so the caller (NowBarWidgetProvider.pushToAllNotifications) can trim its in-memory
     * PendingIntent map to exactly the (key, postTimeMillis) pairs still kept, without a second read.
     */
    fun push(context: Context, entry: PersistableEntry): List<Data> {
        val existing = get(context)

        val merged = buildList {
            add(entry)
            existing.forEach { data ->
                if (!(data.key == entry.key && data.postTimeMillis == entry.postTimeMillis)) {
                    add(
                        PersistableEntry(
                            key = data.key,
                            kind = data.kind,
                            postTimeMillis = data.postTimeMillis,
                            title = data.title,
                            packageName = data.packageName,
                            homeTeam = data.homeTeam,
                            awayTeam = data.awayTeam,
                            homeScore = data.homeScore,
                            awayScore = data.awayScore,
                            lastScorer = data.lastScorer,
                            status = data.status,
                            apiSource = data.apiSource,
                            image = data.imageFile?.let { BitmapFactory.decodeFile(it.path) }
                        )
                    )
                }
            }
        }.sortedByDescending { it.postTimeMillis }.take(MAX_SLOTS)

        save(context, merged)
        return get(context)
    }

    private fun save(context: Context, entries: List<PersistableEntry>) {
        for (slot in 0 until MAX_SLOTS) imageFile(context, slot).delete()

        val array = JSONArray()
        entries.take(MAX_SLOTS).forEachIndexed { slot, entry ->
            array.put(
                JSONObject().apply {
                    put("key", entry.key)
                    put("kind", entry.kind.name)
                    put("postTimeMillis", entry.postTimeMillis)
                    put("title", entry.title ?: JSONObject.NULL)
                    put("packageName", entry.packageName ?: JSONObject.NULL)
                    put("homeTeam", entry.homeTeam ?: JSONObject.NULL)
                    put("awayTeam", entry.awayTeam ?: JSONObject.NULL)
                    put("homeScore", entry.homeScore ?: JSONObject.NULL)
                    put("awayScore", entry.awayScore ?: JSONObject.NULL)
                    put("lastScorer", entry.lastScorer ?: JSONObject.NULL)
                    put("status", entry.status ?: JSONObject.NULL)
                    put("apiSource", entry.apiSource ?: JSONObject.NULL)
                }
            )

            if (entry.image != null) {
                try {
                    FileOutputStream(imageFile(context, slot)).use { out ->
                        entry.image.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                } catch (_: Throwable) {
                    imageFile(context, slot).delete()
                }
            }
        }

        prefs(context).edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    fun clear(context: Context) {
        for (slot in 0 until MAX_SLOTS) imageFile(context, slot).delete()
        prefs(context).edit().remove(KEY_ENTRIES).apply()
    }

    /**
     * Drops the entry matching BOTH [key] and [postTimeMillis], if present — see the class doc's
     * IDENTITY section for why postTimeMillis is part of the identity here too (17/09/2026): with
     * a reused Android notification id, several tiles here can share the same [key] but each has
     * its own postTimeMillis, and only the specific posting that was actually dismissed should
     * disappear — the app's earlier, already-superseded postings for that same id stay until
     * they age out of the capped history on their own, exactly like an ALL-mode app's history
     * entries do. No-op if no entry matches both (never mirrored, or already pushed out by newer
     * entries), so callers can call this unconditionally on every onNotificationRemoved without
     * checking first.
     */
    fun remove(context: Context, key: String, postTimeMillis: Long) {
        val existing = get(context)
        if (existing.none { it.key == key && it.postTimeMillis == postTimeMillis }) return

        val kept = existing.filter { !(it.key == key && it.postTimeMillis == postTimeMillis) }.map { data ->
            PersistableEntry(
                key = data.key,
                kind = data.kind,
                postTimeMillis = data.postTimeMillis,
                title = data.title,
                packageName = data.packageName,
                homeTeam = data.homeTeam,
                awayTeam = data.awayTeam,
                homeScore = data.homeScore,
                awayScore = data.awayScore,
                lastScorer = data.lastScorer,
                status = data.status,
                apiSource = data.apiSource,
                image = data.imageFile?.let { BitmapFactory.decodeFile(it.path) }
            )
        }
        save(context, kept)
    }

    fun get(context: Context): List<Data> {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        val array = try { JSONArray(raw) } catch (_: Throwable) { return emptyList() }

        return (0 until array.length()).mapNotNull { slot ->
            val obj = array.optJSONObject(slot) ?: return@mapNotNull null
            val key = obj.optString("key", "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val kind = try {
                Kind.valueOf(obj.optString("kind", Kind.GENERIC.name))
            } catch (_: Throwable) {
                Kind.GENERIC
            }
            Data(
                key = key,
                kind = kind,
                postTimeMillis = obj.optLong("postTimeMillis", 0L),
                title = obj.optNullableString("title"),
                packageName = obj.optNullableString("packageName"),
                homeTeam = obj.optNullableString("homeTeam"),
                awayTeam = obj.optNullableString("awayTeam"),
                homeScore = obj.optNullableString("homeScore"),
                awayScore = obj.optNullableString("awayScore"),
                lastScorer = obj.optNullableString("lastScorer"),
                status = obj.optNullableString("status"),
                apiSource = obj.optNullableString("apiSource"),
                imageFile = imageFile(context, slot).takeIf { it.exists() }
            )
        }
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null
}
