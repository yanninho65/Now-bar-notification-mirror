package com.yann.nowbarmirror.widget

import android.content.Context
import android.graphics.Bitmap
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the widget's "Toutes notifs" view (see NowBarWidgetProvider.pushToAllNotifications /
 * applyAllNotifs, added 17/09/2026 at Yann's request: "Sur la droite, ajouter un bouton pour
 * changer vue entre sport et toutes notifs. [...] prends les cinq dernieres notifs reçues.").
 *
 * A rolling HISTORY of up to [MAX_SLOTS] notifications RECEIVED, most-recent-first — LIKE
 * SofascoreWidgetStore (and UNLIKE an initial version of this store), an entry is removed as soon
 * as its original notification is dismissed from the shade, by the user, the source app, or
 * "effacer tout" (Yann, 17/09/2026: "Si une notification a été supprimée du centre de notifs, elle
 * ne doit plus apparaître dans le widget") — see [remove], called from
 * MirrorNotificationListener/SofascoreNotificationListenerService's onNotificationRemoved. "History"
 * here just means it can hold MORE than one entry per source — its own MOST RECENT entry (see
 * [get], already most-recent-first) is ALSO what "Dernière notif"/la montre read (MERGED
 * 20/09/2026, see NowBarWidgetProvider.applyLatestContent) — and isn't limited to one entry
 * currently active for a SPECIFIC match the watch complication follows (unlike SofascoreWidgetStore's
 * override system) — it does NOT mean entries survive their own dismissal. An entry with the same [key] as one
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
 * apparaître dans cette barre"; REVU LE MÊME JOUR — Yann, en s'envoyant 5 messages WhatsApp de
 * test : "ça prend les 5 cases alors que ça ne devrait en prendre qu'une", même problème "pour
 * chaque but" d'un match Sofascore): un entry's identity here is [PersistableEntry.key] ALONE for
 * two kinds of source that Yann confirmed should always UPDATE the same tile in place, never
 * spawn a new one, no matter how many times they repost:
 * - [Kind.SOFASCORE_MATCH] entries, unconditionally — CONFIRMÉ SUR APPAREIL (voir
 *   SofascoreNotificationListenerService) : Sofascore poste UNE notification par match, mise à
 *   jour en place à chaque événement (même id/tag, donc même [key], seul
 *   [StatusBarNotification.postTime] change) — exactement ce que montre la capture d'écran native
 *   fournie par Yann le 17/09/2026 (Aurillac - Brive : une seule carte de notification, plusieurs
 *   lignes de score accumulées dedans). Un tile par MATCH, jamais un tile par but.
 * - [PersistableEntry.isConversation] entries (see [PersistableEntry.isConversation]'s doc) — a
 *   messaging-app conversation (WhatsApp, Signal…) is, structurally, the exact same pattern: ONE
 *   Android notification reused per conversation (MessagingStyle), that just accumulates more
 *   lines as messages arrive. 5 self-sent WhatsApp messages in the same conversation are 5
 *   `notify()` calls on that SAME notification, not 5 distinct conversations.
 *
 * For every OTHER [Kind.GENERIC] entry, identity stays the PAIR ([PersistableEntry.key],
 * [PersistableEntry.postTimeMillis]) — this is what keeps the original 17/09/2026 fix intact for
 * an app like Le Monde: it also reuses the same notification id/tag for its "Dernière notif" slot,
 * but each new posting REPLACES the previous one with wholly unrelated content (a different
 * article), rather than accumulating it — from Yann's point of view five different Le Monde
 * articles are five different received notifications, and should still occupy up to five of this
 * history's slots, exactly like an "ALL"-mode app's five notifications would. [key] alone can't
 * tell these two cases apart (both reuse the same Android notification), so
 * [PersistableEntry.isConversation] is the signal MirrorNotificationListener attaches per
 * notification (see its `isConversationNotification()`) to say which behavior applies. Pairing
 * [key] with [postTimeMillis] for the non-collapsed case still separately distinguishes "a
 * genuinely new/updated posting" (new tile) from "the exact same posting being re-pushed" (e.g.
 * MirrorNotificationListener.rebuildStateFromActiveNotifications() re-mirroring an already-active
 * notification on listener reconnect — same key AND same postTime, so [push] still updates that
 * one tile in place rather than duplicating it).
 *
 * Storage mirrors SofascoreWidgetStore (JSON in SharedPreferences for the fields, one PNG file per
 * notification POSTING for images — see WidgetImageFiles, AUDIT 23/09/2026: files used to be named
 * by slot index, which forced every save to decode and re-encode every kept image; an image already
 * on disk is now simply kept, only a genuinely new posting costs one PNG encode). Being a history
 * rather than a full-replace-every-time set, [push] merges the new entry into whatever's already
 * persisted, in memory, then saves once.
 */
object WidgetAllNotificationsStore {

    private const val PREFS_NAME = "widget_all_notifs_prefs"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_ACTIVE_GENERIC_COUNT = "active_generic_count"
    private const val IMAGE_PREFIX = "widget_all_notif_"

    private val parsed = ParsedCache<List<Data>>()

    enum class Kind { GENERIC, SOFASCORE_MATCH }

    /**
     * Per-KIND quota (RESTRUCTURED 23/09/2026 — see [mergeEntry]'s doc for the bug this fixes;
     * Yann: "il faut avoir 5 icones, j'ai ce qu'il faut pour le remplir et je n'en vois que 3
     * là"). Same "+1 in reserve" reasoning as the original single MAX_SLOTS had (so a row that
     * filters OUT the current "Dernière notif" entry — NowBarWidgetProviderCompact's row 1,
     * NowBarWidgetProviderTriple's rows 1 AND 2 — still shows a full 5 once that one entry is
     * excluded), just applied to EACH [Kind] independently now instead of to the combined total.
     */
    const val MAX_SLOTS_PER_KIND = 6

    /**
     * Total on-disk capacity across BOTH kinds combined — [Kind.values().size] ×
     * [MAX_SLOTS_PER_KIND] — used by [save] to size the slot/image-file range. [get]'s own
     * firstOrNull() ("Dernière notif", any kind) and every "up to 5" consumer read the merged,
     * recency-sorted result of [mergeEntry], never this constant directly.
     */
    val MAX_SLOTS = Kind.values().size * MAX_SLOTS_PER_KIND

    /** What [push] needs for one notification. Deliberately Android-widget-agnostic (no PendingIntent — see NowBarWidgetProvider.liveAllNotifIntents, which carries the live one of those but never persists it, same limitation as SofascoreWidgetStore). */
    data class PersistableEntry(
        val key: String,
        val kind: Kind,
        val postTimeMillis: Long,
        // GENERIC fields
        val title: String? = null,
        // Raw notification body — GENERIC: EXTRA_BIG_TEXT/EXTRA_TEXT; SOFASCORE_MATCH: the most
        // recent score/event line (see SofascoreNotificationListenerService.rawTitleAndText).
        // NEW 18/09/2026, "peek" feature (see WidgetPeekPrefs' class doc) — this is what lets a
        // tile in this history be shown full-format (same layout as the LATEST view) when tapped,
        // rather than only ever opening the source app — see NowBarWidgetProvider.resolvePeek.
        val text: String? = null,
        val packageName: String? = null,
        // GENERIC only — see the class doc's IDENTITY section. Whether the source notification is
        // a messaging-app conversation (MessagingStyle/shortcutId/CATEGORY_MESSAGE — see
        // MirrorNotificationListener.isConversationNotification()) rather than plain reused-id
        // content: true collapses every posting sharing [key] into one tile, updated in place,
        // exactly like a [Kind.SOFASCORE_MATCH] entry always does. Always false for
        // SOFASCORE_MATCH entries (irrelevant there — that kind already collapses by key alone
        // unconditionally, see the class doc).
        val isConversation: Boolean = false,
        // SOFASCORE_MATCH fields — same shape as SofascoreWidgetStore.PersistableMatch
        val homeTeam: String? = null,
        val awayTeam: String? = null,
        val homeScore: String? = null,
        val awayScore: String? = null,
        val lastScorer: String? = null,
        val status: String? = null,
        val image: Bitmap?,
        // AUDIT 23/09/2026 — see WidgetImageFiles: the file this entry already had on disk (an
        // entry carried over from a previous save), and a lazy alternative to [image] that's only
        // invoked when this posting has no file on disk yet (lets a refill skip image extraction
        // for postings already stored).
        val existingImageFile: File? = null,
        val imageLoader: (() -> Bitmap?)? = null
    )

    data class Data(
        val key: String,
        val kind: Kind,
        val postTimeMillis: Long,
        val title: String?,
        val text: String?,
        val packageName: String?,
        val isConversation: Boolean,
        val homeTeam: String?,
        val awayTeam: String?,
        val homeScore: String?,
        val awayScore: String?,
        val lastScorer: String?,
        val status: String?,
        val imageFile: File?
    )

    /** True when an entry with this identity must UPDATE its existing tile in place rather than ever spawning a new one — see the class doc's IDENTITY section. */
    private fun collapsesByKeyAlone(kind: Kind, isConversation: Boolean) =
        kind == Kind.SOFASCORE_MATCH || isConversation

    /**
     * [Data] -> [PersistableEntry], carrying its image FILE over as-is (AUDIT 23/09/2026: no
     * longer decoded back to a Bitmap, see WidgetImageFiles) — the same
     * field-by-field copy [push]/[remove]/[pruneAgainstActive] each used to write out inline
     * (three copies of the same 15-field constructor call). Harmonized 20/09/2026 into this one
     * place so a future field addition only needs updating here instead of in three places that
     * could silently drift apart.
     */
    private fun Data.toPersistableEntry(): PersistableEntry = PersistableEntry(
        key = key,
        kind = kind,
        postTimeMillis = postTimeMillis,
        title = title,
        text = text,
        packageName = packageName,
        isConversation = isConversation,
        homeTeam = homeTeam,
        awayTeam = awayTeam,
        homeScore = homeScore,
        awayScore = awayScore,
        lastScorer = lastScorer,
        status = status,
        image = null,
        existingImageFile = imageFile
    )

    /**
     * The actual merge behind [push]/[pushAll]: folds [entry] into [existing] — same tile
     * (collapsed by key alone, or the exact (key, postTimeMillis) pair re-pushed) gets replaced
     * in place, anything else is kept — then re-sorts by recency and caps EACH [Kind]
     * independently to [MAX_SLOTS_PER_KIND] before re-merging by recency. Pure (no I/O), so
     * [pushAll] can call it once per entry in memory without a store round-trip between each one.
     *
     * CORRIGÉ 23/09/2026 (Yann, à propos de la ligne "Dernière notif" — GENERIC uniquement — du
     * nouveau widget triple : "j'ai ce qu'il faut pour le remplir et je n'en vois que 3 là").
     * Cause : le cap était auparavant appliqué au total des deux genres confondus
     * (`.take(MAX_SLOTS)` sur la liste globale déjà triée par recency) — plusieurs matchs
     * Sofascore actifs (remontés en tête à CHAQUE but/évènement, donc quasi toujours plus
     * "récents" que de vraies notifications génériques plus anciennes) pouvaient à eux seuls
     * occuper la totalité des [MAX_SLOTS_PER_KIND] × 2 emplacements et évincer des GENERIC
     * pourtant reçues récemment — exactement le scénario que le propre commentaire de classe de
     * NowBarWidgetProviderTriple anticipait déjà ("il n'y a pas de réserve équivalente pour le
     * filtre 'pas de Sofascore' de la LIGNE 1"). Chaque genre a maintenant son propre quota,
     * plafonné indépendamment l'un de l'autre, donc un afflux de l'un ne peut plus jamais chasser
     * l'autre de l'historique.
     */
    private fun mergeEntry(existing: List<PersistableEntry>, entry: PersistableEntry): List<PersistableEntry> {
        val collapse = collapsesByKeyAlone(entry.kind, entry.isConversation)
        val merged = buildList {
            add(entry)
            existing.forEach { data ->
                val isSameTile = data.key == entry.key &&
                    (collapse || data.postTimeMillis == entry.postTimeMillis)
                if (!isSameTile) add(data)
            }
        }.sortedByDescending { it.postTimeMillis }

        return Kind.values()
            .flatMap { kind -> merged.filter { it.kind == kind }.take(MAX_SLOTS_PER_KIND) }
            .sortedByDescending { it.postTimeMillis }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Pre-audit slot-indexed file name — only read back for entries saved by an older version (migrated on the next save). */
    private fun legacyImageFile(context: Context, slot: Int) =
        File(context.filesDir, "$IMAGE_PREFIX$slot.png")

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
        // See the class doc's IDENTITY section: a Sofascore match or a conversation always
        // updates its ONE existing tile (by key alone), no matter how many times it reposts;
        // anything else only updates in place when it's the exact same posting re-pushed
        // (same key AND same postTimeMillis) — a new posting with a different postTimeMillis is a
        // genuinely new tile. See [mergeEntry].
        val merged = mergeEntry(get(context).map { it.toPersistableEntry() }, entry)
        save(context, merged)
        return get(context)
    }

    /**
     * Batched version of [push] for several entries pushed together — see
     * NowBarWidgetProvider.pushToAllNotificationsBatch's doc for the call site this exists for.
     * Pushing N entries one at a time, each through [push], reads/merges/saves/rebuilds the whole
     * widget N separate times; when a caller already has the full batch in hand (refilling
     * "Toutes notifs" after a dismissal from everything still active in the shade, say), doing N
     * separate saves means the store — and, via NowBarWidgetProvider, the on-screen widget —
     * visibly passes through every intermediate partial state along the way, oldest entry first,
     * before landing on the final top-5 (Yann, 20/09/2026: "quand je supprime une notif [...] je
     * vois apparaitre toutes les notifs dans un ordre chronologique croissant avant de voir la
     * dernière reçue [...] ça pourrait de suite montrer la cinquième"). This instead folds every
     * entry into the SAME in-memory list (via [mergeEntry], applied repeatedly — order-independent
     * for the final result, since capping to [MAX_SLOTS] after each fold only ever discards
     * entries that are already outside the top [MAX_SLOTS] of everything merged so far) and saves
     * ONCE at the end, so there's exactly one store write and the caller triggers exactly one
     * widget rebuild for the whole batch.
     */
    fun pushAll(context: Context, entries: List<PersistableEntry>): List<Data> {
        if (entries.isEmpty()) return get(context)
        var current = get(context).map { it.toPersistableEntry() }
        entries.forEach { entry -> current = mergeEntry(current, entry) }
        save(context, current)
        return get(context)
    }

    // Synchronized (AUDIT 23/09/2026): saves can come from the main thread and from
    // ApiOverrideFollowService's background polling; WidgetImageFiles.commit() must never delete
    // a file another concurrent save just wrote.
    @Synchronized
    private fun save(context: Context, entries: List<PersistableEntry>) {
        val files = WidgetImageFiles(context, IMAGE_PREFIX)
        val array = JSONArray()
        entries.take(MAX_SLOTS).forEach { entry ->
            val imageName = files.persist(entry.key, entry.postTimeMillis, entry.existingImageFile, entry.image, entry.imageLoader)
            array.put(
                JSONObject().apply {
                    put("key", entry.key)
                    put("kind", entry.kind.name)
                    put("postTimeMillis", entry.postTimeMillis)
                    put("title", entry.title ?: JSONObject.NULL)
                    put("text", entry.text ?: JSONObject.NULL)
                    put("packageName", entry.packageName ?: JSONObject.NULL)
                    put("isConversation", entry.isConversation)
                    put("homeTeam", entry.homeTeam ?: JSONObject.NULL)
                    put("awayTeam", entry.awayTeam ?: JSONObject.NULL)
                    put("homeScore", entry.homeScore ?: JSONObject.NULL)
                    put("awayScore", entry.awayScore ?: JSONObject.NULL)
                    put("lastScorer", entry.lastScorer ?: JSONObject.NULL)
                    put("status", entry.status ?: JSONObject.NULL)
                    put("image", imageName ?: JSONObject.NULL)
                }
            )
        }

        prefs(context).edit().putString(KEY_ENTRIES, array.toString()).apply()
        files.commit()
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
     *
     * Returns true when an entry was actually dropped (AUDIT 23/09/2026) — lets
     * MirrorNotificationListener skip the whole refill/widget-rebuild path for removals that never
     * concerned this history (the vast majority: every other app's notifications).
     */
    fun remove(context: Context, key: String, postTimeMillis: Long): Boolean {
        val existing = get(context)
        // For a collapsed tile (Sofascore match, or a conversation) there is only ever ONE entry
        // per key, so the removal of ANY one of its postings must drop that single tile — matching
        // strictly on postTimeMillis too would leave a stale tile behind whenever the notification
        // that gets dismissed isn't the exact posting last recorded here (see the class doc's
        // IDENTITY section). For anything else, only the exact (key, postTimeMillis) posting goes.
        fun matches(data: Data) = data.key == key &&
            (collapsesByKeyAlone(data.kind, data.isConversation) || data.postTimeMillis == postTimeMillis)

        if (existing.none(::matches)) return false

        save(context, existing.filterNot(::matches).map { it.toPersistableEntry() })
        return true
    }

    /**
     * Drops every entry whose underlying notification is no longer present among [active] — a
     * fresh getActiveNotifications() snapshot from EITHER listener service (MirrorNotificationListener
     * or SofascoreNotificationListenerService both see the whole notification shade, not just their
     * own package, so either one can supply it). Uses the SAME identity rules as [remove] (see the
     * class doc's IDENTITY section): a collapsing entry (Sofascore match, conversation) stays valid
     * as long as ANY active notification still carries its [Data.key]; anything else needs the
     * exact (key, postTimeMillis) pair to still be posted.
     *
     * Added 18/09/2026 — Yann, after force-stopping the app to get "Toutes notifs" to catch up, then
     * seeing entries (a Gmail notification marked read/deleted/opened from within Gmail itself, a
     * deleted calendar event) that never disappeared even though they were long gone from the
     * notification center, then after a phone reboot finding 2 stale entries (one Gmail) despite
     * having none active: "il faut vraiment que l'appli lise l'existant, elle s'apercevrait que la
     * notif gmail n'y est plus [...] comme si l'appli gardait en mémoire alors qu'elle doit lire."
     * Root cause: this store is only ever updated by push()/remove() reacting to
     * onNotificationPosted/onNotificationRemoved events — a removal event MISSED because this
     * process was dead at the time (killed in the background, force-stopped, or the phone rebooted)
     * left the entry behind forever, since nothing ever re-checked it against reality. This function
     * is that missing re-check: call it whenever a fresh [active] snapshot is available (listener
     * (re)connect above all — see MirrorNotificationListener.rebuildStateFromActiveNotifications and
     * SofascoreNotificationListenerService.bootstrapAllNotificationsHistory) so those stale entries
     * get dropped instead of surviving indefinitely. No-op if nothing is actually stale, so callers
     * can call this unconditionally on every reconnect.
     */
    fun pruneAgainstActive(context: Context, active: List<StatusBarNotification>) {
        val existing = get(context)
        if (existing.isEmpty()) return

        fun isStillActive(data: Data): Boolean {
            return if (collapsesByKeyAlone(data.kind, data.isConversation)) {
                active.any { it.key == data.key }
            } else {
                active.any { it.key == data.key && it.postTime == data.postTimeMillis }
            }
        }

        val kept = existing.filter(::isStillActive)
        if (kept.size == existing.size) return

        save(context, kept.map { it.toPersistableEntry() })
    }

    fun get(context: Context): List<Data> {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        return parsed.getOrParse(raw) { parse(context, it) }
    }

    private fun parse(context: Context, raw: String): List<Data> {
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
                text = obj.optNullableString("text"),
                packageName = obj.optNullableString("packageName"),
                isConversation = obj.optBoolean("isConversation", false),
                homeTeam = obj.optNullableString("homeTeam"),
                awayTeam = obj.optNullableString("awayTeam"),
                homeScore = obj.optNullableString("homeScore"),
                awayScore = obj.optNullableString("awayScore"),
                lastScorer = obj.optNullableString("lastScorer"),
                status = obj.optNullableString("status"),
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

    /**
     * NEW 23/09/2026 (Yann: "je voulais le même mécanisme pour les notifications autre que
     * sofascore" — see SofascoreWidgetStore.save's own `activeCount` doc for the "+1 alors qu'il y
     * a 17 matchs" bug this mirrors). [get]'s GENERIC entries are capped at [MAX_SLOTS_PER_KIND]
     * (6) by [mergeEntry], so NowBarWidgetProviderTriple's own "+X" overflow badge for the
     * "Dernière notif" row could never say more than "+1" if it only ever counted THOSE — this
     * separately persisted count is the TRUE number of currently active, eligible generic
     * notifications (any app with a mirror mode, not this app's own mirrors, not ongoing/a group
     * summary/media playback — see MirrorNotificationListener.refreshActiveGenericCount, called
     * from onNotificationPosted/onNotificationRemoved/listener reconnect, same
     * always-recompute-from-scratch pattern as SofascoreNotificationListenerService.refresh()),
     * independent of [MAX_SLOTS_PER_KIND]. 0 if never saved yet.
     */
    fun saveActiveGenericCount(context: Context, count: Int) {
        prefs(context).edit().putInt(KEY_ACTIVE_GENERIC_COUNT, count).apply()
    }

    fun getActiveGenericCount(context: Context): Int = prefs(context).getInt(KEY_ACTIVE_GENERIC_COUNT, 0)
}
