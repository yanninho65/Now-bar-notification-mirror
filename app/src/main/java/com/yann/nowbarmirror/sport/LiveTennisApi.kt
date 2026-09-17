package com.yann.nowbarmirror.sport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Client minimal pour Live Tennis API (https://livetennisapi.com),
 * utilisé uniquement quand "Live Tennis API" est sélectionné dans l'app
 * (voir MainActivity.ApiMode) — TheSportsDB (SportsDbApi.kt) reste
 * utilisé pour les autres sports, inchangé.
 *
 * CLÉ API REQUISE, SAISIE DANS L'APP — PAS DANS CE FICHIER : le dépôt
 * GitHub est PUBLIC, donc aucune clé n'est codée en dur ici
 * (contrairement à TheSportsDB, dont "3" est une clé de TEST publique
 * documentée, faite pour être partagée). Chaque fonction ci-dessous
 * reçoit sa clé en paramètre ([apiKey]), lue par l'appelant
 * (MainActivity, ApiOverrideFollowService) dans TennisApiKeyPrefs
 * (SharedPreferences locales, remplies via le champ qui apparaît sur
 * l'écran principal une fois "Live Tennis API" sélectionné — voir
 * TennisApiKeyPrefs.kt). Inscription gratuite sur
 * https://livetennisapi.com/subscribe/free (email uniquement, aucune
 * carte bancaire), clé affichée immédiatement sur
 * https://livetennisapi.com/account.
 *
 * PLAN GRATUIT — LIMITE IMPORTANTE, différente de TheSportsDB : 30
 * requêtes/minute **ET seulement 100/jour** (TheSportsDB free n'a pas de
 * plafond journalier, juste 30/min). C'est pourquoi ApiOverrideFollowService
 * interroge cette API toutes les 3 minutes pour le tennis au lieu de
 * toutes les 60 secondes comme pour TheSportsDB : à 60s, un seul match
 * de 2-3h épuiserait à lui seul le quota du jour, recherches comprises.
 * Voir https://docs.livetennisapi.com pour la référence complète.
 *
 * Endpoints utilisés, tous FREE :
 * - GET /players?search=      → recherche de joueur par nom
 * - GET /matches?status=…&player=<id>  → matchs (en direct/à venir) d'un joueur
 * - GET /matches/{id}         → détail + score d'un match, pour le polling
 */
object LiveTennisApi {

    private const val BASE_URL = "https://api.livetennisapi.com/api/public/v1"

    /** Recherche de joueurs par nom (ATP/WTA/Challenger/ITF confondus). */
    suspend fun searchPlayers(query: String, apiKey: String): List<TennisPlayerResult> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val json = fetchJson("$BASE_URL/players?search=$encoded", apiKey)
        val players = json.optJSONArray("data") ?: return@withContext emptyList()

        (0 until players.length()).mapNotNull { i ->
            val p = players.optJSONObject(i) ?: return@mapNotNull null
            if (!p.has("id")) return@mapNotNull null
            TennisPlayerResult(
                id = p.optInt("id"),
                name = p.optString("name", "?"),
                tour = nullableString(p, "tour"),
                ranking = if (p.has("ranking") && !p.isNull("ranking")) p.optInt("ranking") else null,
                country = nullableString(p, "country")
            )
        }
    }

    /**
     * Matchs du jour pour un joueur donné : en direct + à venir aujourd'hui.
     * Filtré côté serveur (from/to) plutôt que côté client (contrairement à
     * SportsDbApi.getMatchesForTeam) pour économiser le quota journalier.
     */
    suspend fun getMatchesForPlayerToday(playerId: Int, apiKey: String): List<MatchResult> = withContext(Dispatchers.IO) {
        val today = todayUtcDateString()
        val live = fetchMatches("$BASE_URL/matches?status=live&player=$playerId", apiKey)
        val upcoming = fetchMatches("$BASE_URL/matches?status=upcoming&player=$playerId&from=$today&to=$today", apiKey)
        live + upcoming
    }

    /** Relit un match précis par son id — utilisé pour le polling périodique (voir ApiOverrideFollowService). */
    suspend fun lookupMatch(matchId: String, apiKey: String): MatchResult? = withContext(Dispatchers.IO) {
        val json = fetchJson("$BASE_URL/matches/$matchId", apiKey)
        if (!json.has("id")) return@withContext null
        parseMatch(json)
    }

    fun todayUtcDateString(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())

    private fun fetchMatches(url: String, apiKey: String): List<MatchResult> {
        val json = fetchJson(url, apiKey)
        val matches = json.optJSONArray("data") ?: return emptyList()
        return (0 until matches.length()).mapNotNull { i ->
            matches.optJSONObject(i)?.let { parseMatch(it) }
        }
    }

    /**
     * [m] est un objet "Match" de Live Tennis API (même forme sur
     * /matches, /matches/{id} et /history/matches) : players.p1/p2.name,
     * score.sets ([sets_p1, sets_p2]), status brut ("live"/"upcoming"/
     * "completed"/"cancelled"), scheduled_time en ISO-8601 UTC.
     *
     * homeScore/awayScore portent le nombre de SETS gagnés (pas de jeux ni
     * de points). currentSetHomeGames/currentSetAwayGames portent le score
     * de JEUX du set en cours (dernier élément de `score.games`, voir
     * ci-dessous) — c'est wear/MatchClock.kt qui met tout ça en forme
     * ("3e set 4-3"), pas ce fichier : le téléphone relaie des données
     * brutes, comme SportsDbApi.kt le fait déjà avec strStatus.
     */
    private fun parseMatch(m: JSONObject): MatchResult? {
        if (!m.has("id")) return null
        val id = m.optInt("id").toString()
        val players = m.optJSONObject("players")
        val homeTeam = players?.optJSONObject("p1")?.optString("name") ?: "?"
        val awayTeam = players?.optJSONObject("p2")?.optString("name") ?: "?"

        val score = m.optJSONObject("score")
        val sets = score?.optJSONArray("sets")
        val homeScore = if (sets != null && sets.length() > 0) sets.optInt(0).toString() else null
        val awayScore = if (sets != null && sets.length() > 1) sets.optInt(1).toString() else null

        // "games" = [games_p1, games_p2], chaque liste étant le score de
        // jeux PAR SET joué — son DERNIER élément est donc le set en cours
        // (ou le dernier set joué si le match est terminé). Voir
        // https://docs.livetennisapi.com : "[[6,3,2],[4,6,1]] reads 6-4,
        // 3-6, 2-1".
        val games = score?.optJSONArray("games")
        val homeGamesPerSet = games?.optJSONArray(0)
        val awayGamesPerSet = games?.optJSONArray(1)
        val currentSetHomeGames = homeGamesPerSet?.takeIf { it.length() > 0 }?.let { it.optInt(it.length() - 1) }
        val currentSetAwayGames = awayGamesPerSet?.takeIf { it.length() > 0 }?.let { it.optInt(it.length() - 1) }

        val scheduledTime = nullableString(m, "scheduled_time")

        return MatchResult(
            id = id,
            source = ApiSource.LIVE_TENNIS,
            homeTeam = homeTeam,
            awayTeam = awayTeam,
            homeScore = homeScore,
            awayScore = awayScore,
            currentSetHomeGames = currentSetHomeGames,
            currentSetAwayGames = currentSetAwayGames,
            date = scheduledTime?.substringBefore("T") ?: todayUtcDateString(),
            time = scheduledTime?.let { formatTimeUtc(it) },
            // Statut brut de l'API ("live"/"upcoming"/"completed"/"cancelled"),
            // stocké tel quel — même principe que SportsDbApi avec strStatus.
            status = m.optString("status", ""),
            league = m.optString("tournament", ""),
            kickoffEpochMillis = parseIsoEpochMillis(scheduledTime)
        )
    }

    /** "2026-07-18T10:41:07Z" -> "10:41:07". */
    private fun formatTimeUtc(isoTimestamp: String): String? =
        isoTimestamp.substringAfter("T", "").removeSuffix("Z").ifBlank { null }

    private fun parseIsoEpochMillis(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try {
            val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            formatter.parse(iso)?.time
        } catch (e: Exception) {
            null
        }
    }

    private fun nullableString(json: JSONObject, key: String): String? {
        if (!json.has(key) || json.isNull(key)) return null
        val value = json.optString(key)
        return value.ifBlank { null }
    }

    /** Authentification par en-tête (voir doc) plutôt que par ?token=, pour ne pas laisser la clé traîner dans des logs d'URL. */
    private fun fetchJson(urlString: String, apiKey: String): JSONObject {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(urlString).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("X-API-Key", apiKey)
            val code = connection.responseCode
            if (code !in 200..299) return JSONObject()
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body)
        } catch (e: Exception) {
            JSONObject()
        } finally {
            connection?.disconnect()
        }
    }
}
