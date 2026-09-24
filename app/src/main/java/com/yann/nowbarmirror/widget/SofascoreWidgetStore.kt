package com.yann.nowbarmirror.widget

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the up-to-[MAX_SLOTS] Sofascore matches shown in the widget's "Sport" view (see
 * NowBarWidgetProvider.pushSofascoreMatches / applySofascoreMatches) — already sorted and capped
 * by NowBarWidgetProvider before being saved here, this store just remembers whatever it's
 * handed, in order. Same storage approach as WidgetAllNotificationsStore (JSON in SharedPreferences
 * for the fields, PNG files on disk for the images — a Bitmap can't just go in SharedPreferences),
 * using org.json like SettingsBackup.kt already does elsewhere in this app.
 *
 * Images are saved per notification POSTING (key + postTime — see WidgetImageFiles, AUDIT
 * 23/09/2026), no longer by slot index: the list is still rewritten on every [save], but a match
 * whose notification didn't change keeps its image file untouched instead of being re-encoded, and
 * its image isn't even re-extracted from the notification ([PersistableMatch.imageLoader]).
 *
 * [title]/[text] (NEW 18/09/2026, "peek" feature — see WidgetPeekPrefs' class doc) are the raw
 * Android notification title/text for this match (title is literally "$homeTeam - $awayTeam",
 * text is the most recent score/event line — see
 * SofascoreNotificationListenerService.rawTitleAndText), kept alongside the already-parsed
 * team/score/status fields above so a tap on this match's tile can show it full-format, in the
 * EXACT SAME shape as any other notification's "peek" — see NowBarWidgetProvider.resolvePeek.
 * PersistableMatch/Data mirror each other 1:1 the same way they already did before this addition.
 */
object SofascoreWidgetStore {

    private const val PREFS_NAME = "sofascore_widget_prefs"
    private const val KEY_MATCHES = "matches"
    private const val KEY_ACTIVE_COUNT = "active_count"
    private const val IMAGE_PREFIX = "sofascore_widget_match_"

    private val parsed = ParsedCache<List<Data>>()

    /**
     * The main 4x1 widget's own fixed widget_match_1..5 layout slots only ever render the first 5
     * (5th slot added 17/09/2026 at Yann's request) — so why 6 (RAISED 23/09/2026, Yann: "Je
     * voulais qu'il y en ait toujours 5 [dans la ligne d'icônes du widget 4x2]. Ça veut dire que
     * tu affiches la sixième en attente de l'autre widget."): the compact 4x2 widget's row 1
     * (NowBarWidgetProviderCompact.applyIconsRow) shows this SAME list with whichever match is
     * currently "Dernière notif" filtered OUT first — so with only 5 ever kept here, that filter
     * could leave as few as 4 to show. Keeping one extra "in reserve" means there's still a 6th
     * one ready to take that slot whenever the excluded match is actually part of this list, so
     * the compact widget's row 1 shows a full 5 whenever at least 6 matches are actually active,
     * exactly like it would if the excluded one had simply never been mirrored at all.
     */
    const val MAX_SLOTS = 6

    /** What [save] needs for one match. Deliberately Android-widget-agnostic (no PendingIntent — see NowBarWidgetProvider.SofascoreWidgetMatch, which carries the live one of those but never persists it, same limitation as WidgetAllNotificationsStore.PersistableEntry). */
    data class PersistableMatch(
        val key: String,
        val homeTeam: String,
        val awayTeam: String,
        val homeScore: String?,
        val awayScore: String?,
        val lastScorer: String?,
        val status: String,
        val postTimeMillis: Long,
        val title: String,
        val text: String,
        val image: Bitmap?,
        // AUDIT 23/09/2026 — lazy alternative to [image], only invoked when this posting has no
        // image file on disk yet (see WidgetImageFiles).
        val imageLoader: (() -> Bitmap?)? = null
    )

    data class Data(
        val key: String,
        val homeTeam: String,
        val awayTeam: String,
        val homeScore: String?,
        val awayScore: String?,
        val lastScorer: String?,
        val status: String,
        val postTimeMillis: Long,
        val title: String,
        val text: String,
        val imageFile: File?
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Pre-audit slot-indexed file name — only read back for data saved by an older version. */
    private fun legacyImageFile(context: Context, slot: Int) =
        File(context.filesDir, "$IMAGE_PREFIX$slot.png")

    /**
     * [matches] beyond [MAX_SLOTS] are silently dropped here as a safety net — the caller
     * (NowBarWidgetProvider) is expected to have already capped the list.
     *
     * [activeCount] (NEW 23/09/2026, Yann: "pour sofascore je vois +1 alors qu'il y a 17 matchs en
     * tout" — NowBarWidgetProviderTriple's own "+X" overflow badge, see its applyOverflowBadge)
     * defaults to [matches]'s own (already-capped) size, but NowBarWidgetProvider.pushSofascoreMatches
     * passes the TRUE pre-cap count of currently active matches instead: with only the capped
     * [matches] to go on, the badge could only ever say "+1" (MAX_SLOTS − the 5 shown), no matter
     * how many matches were actually active beyond that — this is what lets the badge reflect
     * reality (e.g. "+12" for 17 active matches, 5 shown) instead of the storage cap.
     */
    // Synchronized (AUDIT 23/09/2026): saves can come from the main thread and from
    // ApiOverrideFollowService's background polling; WidgetImageFiles.commit() must never delete
    // a file another concurrent save just wrote.
    @Synchronized
    fun save(context: Context, matches: List<PersistableMatch>, activeCount: Int = matches.size) {
        val existing = get(context).associateBy { "${it.key}::${it.postTimeMillis}" }
        val files = WidgetImageFiles(context, IMAGE_PREFIX)
        val array = JSONArray()
        matches.take(MAX_SLOTS).forEach { match ->
            val imageName = files.persist(
                match.key,
                match.postTimeMillis,
                existing["${match.key}::${match.postTimeMillis}"]?.imageFile,
                match.image,
                match.imageLoader
            )
            array.put(
                JSONObject().apply {
                    put("key", match.key)
                    put("homeTeam", match.homeTeam)
                    put("awayTeam", match.awayTeam)
                    put("homeScore", match.homeScore ?: JSONObject.NULL)
                    put("awayScore", match.awayScore ?: JSONObject.NULL)
                    put("lastScorer", match.lastScorer ?: JSONObject.NULL)
                    put("status", match.status)
                    put("postTimeMillis", match.postTimeMillis)
                    put("title", match.title)
                    put("text", match.text)
                    put("image", imageName ?: JSONObject.NULL)
                }
            )
        }

        prefs(context)
            .edit()
            .putString(KEY_MATCHES, array.toString())
            .putInt(KEY_ACTIVE_COUNT, activeCount)
            .apply()
        files.commit()
    }

    /** See [save]'s own doc (its `activeCount` param) — the true number of currently active matches, independent of [MAX_SLOTS]. 0 if never saved yet. */
    fun getActiveCount(context: Context): Int = prefs(context).getInt(KEY_ACTIVE_COUNT, 0)

    fun get(context: Context): List<Data> {
        val raw = prefs(context).getString(KEY_MATCHES, null) ?: return emptyList()
        return parsed.getOrParse(raw) { parse(context, it) }
    }

    private fun parse(context: Context, raw: String): List<Data> {
        val array = try { JSONArray(raw) } catch (_: Throwable) { return emptyList() }

        return (0 until array.length()).mapNotNull { slot ->
            val obj = array.optJSONObject(slot) ?: return@mapNotNull null
            val key = obj.optString("key", "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val homeTeam = obj.optString("homeTeam", "")
            val awayTeam = obj.optString("awayTeam", "")
            Data(
                key = key,
                homeTeam = homeTeam,
                awayTeam = awayTeam,
                homeScore = obj.optNullableString("homeScore"),
                awayScore = obj.optNullableString("awayScore"),
                lastScorer = obj.optNullableString("lastScorer"),
                status = obj.optString("status", ""),
                postTimeMillis = obj.optLong("postTimeMillis", 0L),
                // Fallback rebuilds the raw title exactly like the notification's own EXTRA_TITLE
                // ("$homeTeam - $awayTeam") for anything persisted before this field existed.
                title = obj.optNullableString("title") ?: "$homeTeam - $awayTeam",
                text = obj.optNullableString("text") ?: "",
                imageFile = if (obj.has("image")) {
                    WidgetImageFiles.resolve(context, obj.optNullableString("image"))
                } else {
                    legacyImageFile(context, slot).takeIf { it.exists() }
                }
            )
        }
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null
}
