package com.yann.nowbarmirror.widget

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Persists the up-to-[MAX_SLOTS] Sofascore matches shown in the widget's "Sport" view (see
 * NowBarWidgetProvider.pushSofascoreMatches / applySofascoreMatches) — already sorted and capped
 * by NowBarWidgetProvider before being saved here, this store just remembers whatever it's
 * handed, in order. Mirrors WidgetNotificationStore's approach (JSON in SharedPreferences for the
 * fields, PNG files on disk for the images — see its class doc for why a Bitmap can't just go in
 * SharedPreferences) extended to a small ordered list instead of a single entry, using org.json
 * like SettingsBackup.kt already does elsewhere in this app.
 *
 * Images are saved by SLOT INDEX (0 until [MAX_SLOTS]), not by notification key: the whole list
 * is rewritten on every [save] (see SofascoreNotificationListenerService.pushWidgetMatches), so
 * there's nothing to reconcile — old slot files are simply deleted first.
 */
object SofascoreWidgetStore {

    private const val PREFS_NAME = "sofascore_widget_prefs"
    private const val KEY_MATCHES = "matches"

    /** Kept in sync with NowBarWidgetProvider's fixed widget_match_1..4 layout slots. */
    const val MAX_SLOTS = 4

    /** What [save] needs for one match. Deliberately Android-widget-agnostic (no PendingIntent — see NowBarWidgetProvider.SofascoreWidgetMatch, which carries the live one of those but never persists it, same limitation as WidgetNotificationStore's original PendingIntent). */
    data class PersistableMatch(
        val key: String,
        val homeTeam: String,
        val awayTeam: String,
        val homeScore: String?,
        val awayScore: String?,
        val lastScorer: String?,
        val status: String,
        val apiSource: String,
        val postTimeMillis: Long,
        val image: Bitmap?
    )

    data class Data(
        val key: String,
        val homeTeam: String,
        val awayTeam: String,
        val homeScore: String?,
        val awayScore: String?,
        val lastScorer: String?,
        val status: String,
        val apiSource: String,
        val postTimeMillis: Long,
        val imageFile: File?
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun imageFile(context: Context, slot: Int) =
        File(context.filesDir, "sofascore_widget_match_$slot.png")

    /** [matches] beyond [MAX_SLOTS] are silently dropped here as a safety net — the caller (NowBarWidgetProvider) is expected to have already capped the list. */
    fun save(context: Context, matches: List<PersistableMatch>) {
        for (slot in 0 until MAX_SLOTS) imageFile(context, slot).delete()

        val array = JSONArray()
        matches.take(MAX_SLOTS).forEachIndexed { slot, match ->
            array.put(
                JSONObject().apply {
                    put("key", match.key)
                    put("homeTeam", match.homeTeam)
                    put("awayTeam", match.awayTeam)
                    put("homeScore", match.homeScore ?: JSONObject.NULL)
                    put("awayScore", match.awayScore ?: JSONObject.NULL)
                    put("lastScorer", match.lastScorer ?: JSONObject.NULL)
                    put("status", match.status)
                    put("apiSource", match.apiSource)
                    put("postTimeMillis", match.postTimeMillis)
                }
            )

            if (match.image != null) {
                try {
                    FileOutputStream(imageFile(context, slot)).use { out ->
                        match.image.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                } catch (_: Throwable) {
                    imageFile(context, slot).delete()
                }
            }
        }

        prefs(context).edit().putString(KEY_MATCHES, array.toString()).apply()
    }

    fun clear(context: Context) {
        for (slot in 0 until MAX_SLOTS) imageFile(context, slot).delete()
        prefs(context).edit().remove(KEY_MATCHES).apply()
    }

    fun get(context: Context): List<Data> {
        val raw = prefs(context).getString(KEY_MATCHES, null) ?: return emptyList()
        val array = try { JSONArray(raw) } catch (_: Throwable) { return emptyList() }

        return (0 until array.length()).mapNotNull { slot ->
            val obj = array.optJSONObject(slot) ?: return@mapNotNull null
            val key = obj.optString("key", "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Data(
                key = key,
                homeTeam = obj.optString("homeTeam", ""),
                awayTeam = obj.optString("awayTeam", ""),
                homeScore = obj.optNullableString("homeScore"),
                awayScore = obj.optNullableString("awayScore"),
                lastScorer = obj.optNullableString("lastScorer"),
                status = obj.optString("status", ""),
                apiSource = obj.optString("apiSource", "SPORTS_DB"),
                postTimeMillis = obj.optLong("postTimeMillis", 0L),
                imageFile = imageFile(context, slot).takeIf { it.exists() }
            )
        }
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null
}
