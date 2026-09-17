package com.yann.nowbarmirror.sport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.yann.nowbarmirror.R
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * REMPLACE MatchFollowService (retiré le 16/09/2026 — voir README, section
 * de cette date) : Sofascore est maintenant TOUJOURS la source des noms
 * d'équipe et de l'image de la complication (voir
 * SofascoreNotificationListenerService). Ce service ne sert plus qu'à
 * sonder, en tâche de fond, l'API choisie (TheSportsDB ou Live Tennis API)
 * pour le SEUL override actuellement actif (voir
 * [SofascoreApiOverridePrefs], [ApiOverrideCache], [sync]), afin
 * d'alimenter `SofascoreNotificationListenerService.refresh()` avec un
 * score et/ou une période plus précis que ceux déduits du texte de la
 * notif Sofascore.
 *
 * Un seul override sondé à la fois — voir [sync] : Live Tennis API
 * plafonne à 100 requêtes/jour (voir LiveTennisApi.kt), sonder plusieurs
 * matchs en parallèle épuiserait vite ce quota pour rien, puisque la
 * montre n'affiche de toute façon qu'un seul match à la fois (celui de la
 * notification Sofascore active).
 *
 * Foreground service pour la même raison que l'ancien MatchFollowService :
 * survivre à l'app téléphone fermée/balayée (voir note Samsung ci-dessous,
 * inchangée).
 *
 * NOTE Samsung : certains téléphones (dont les Galaxy) appliquent une
 * optimisation batterie agressive qui peut quand même arrêter ce
 * service. Si le suivi s'interrompt de façon inattendue, désactiver
 * l'optimisation de batterie pour cette app dans
 * Paramètres > Batterie > Sports Complication > Non optimisée.
 */
class ApiOverrideFollowService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            return START_NOT_STICKY
        }

        val notifKey = intent?.getStringExtra(EXTRA_NOTIF_KEY)
        val source = intent?.getStringExtra(EXTRA_SOURCE)?.let { name ->
            ApiSource.values().firstOrNull { it.name == name }
        }
        val matchId = intent?.getStringExtra(EXTRA_MATCH_ID)
        val label = intent?.getStringExtra(EXTRA_LABEL).orEmpty()
        if (notifKey == null || source == null || matchId == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startTracking(notifKey, source, matchId, label)
        // NOT_STICKY : mêmes raisons que l'ancien MatchFollowService — si le
        // système tue quand même le processus, on ne veut pas qu'il relance
        // ce service sans contexte (notifKey/override). refresh() relancera
        // le sondage de lui-même au prochain événement Sofascore de toute
        // façon.
        return START_NOT_STICKY
    }

    private fun startTracking(notifKey: String, source: ApiSource, matchId: String, label: String) {
        pollJob?.cancel()
        startForeground(NOTIFICATION_ID, buildNotification(label))

        pollJob = scope.launch {
            var finished = false
            while (isActive && !finished) {
                val fetched = try {
                    when (source) {
                        ApiSource.SPORTS_DB -> SportsDbApi.lookupEvent(matchId)
                        ApiSource.LIVE_TENNIS -> {
                            // Clé absente/vidée pendant le suivi (Yann l'a
                            // effacée dans l'app) : on saute ce cycle plutôt
                            // que de planter, comme une panne réseau — voir
                            // TennisApiKeyPrefs.kt.
                            val apiKey = TennisApiKeyPrefs.get(applicationContext)
                            if (apiKey == null) null else LiveTennisApi.lookupMatch(matchId, apiKey)
                        }
                    }
                } catch (e: Exception) {
                    null
                }

                if (fetched != null) {
                    ApiOverrideCache.update(notifKey, source, matchId, fetched)
                    // Fait relire tout de suite le résultat le plus frais à
                    // la montre, plutôt que d'attendre le prochain événement
                    // Sofascore (qui peut tarder si le match Sofascore
                    // lui-même n'a rien de nouveau à annoncer).
                    SofascoreNotificationListenerService.refreshIfConnected()
                    finished = fetched.isFinished
                }

                if (!finished) delay(pollIntervalMillis(source))
            }

            // Le match suivi par l'API est terminé : plus rien à sonder.
            // L'override reste stocké (Yann peut le retirer/le changer à la
            // main dans l'app) ; seul le sondage en tâche de fond s'arrête.
            clearRunningIfMatches(notifKey, source, matchId)
            stopForegroundAndSelf()
        }
    }

    /**
     * Le foot (TheSportsDB) n'a pas de plafond de requêtes par jour, juste
     * 30/min — 60s de battement est donc sans risque. Le tennis (Live
     * Tennis API, plan gratuit) plafonne lui à 100 requêtes/JOUR en plus
     * du 30/min : à 60s, un seul match de 2-3h épuiserait sinon le quota du
     * jour à lui seul, recherches de joueurs comprises. 3 minutes laisse de
     * la marge (voir LiveTennisApi.kt et README).
     */
    private fun pollIntervalMillis(source: ApiSource): Long = when (source) {
        ApiSource.SPORTS_DB -> POLL_INTERVAL_MS_FOOTBALL
        ApiSource.LIVE_TENNIS -> POLL_INTERVAL_MS_TENNIS
    }

    private fun stopTracking() {
        pollJob?.cancel()
        stopForegroundAndSelf()
    }

    private fun stopForegroundAndSelf() {
        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(label: String): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sport_notification)
            .setContentTitle("Score précis en cours")
            .setContentText(label.ifBlank { "Suivi d'un match via API" })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Score précis (API)",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notification persistante pendant le sondage d'une API de score pour un match Sofascore"
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val POLL_INTERVAL_MS_FOOTBALL = 60_000L
        private const val POLL_INTERVAL_MS_TENNIS = 180_000L
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "api_override_follow"

        private const val ACTION_STOP = "com.yann.nowbarmirror.sport.action.STOP_API_OVERRIDE"
        private const val EXTRA_NOTIF_KEY = "com.yann.nowbarmirror.sport.extra.OVERRIDE_NOTIF_KEY"
        private const val EXTRA_SOURCE = "com.yann.nowbarmirror.sport.extra.OVERRIDE_SOURCE"
        private const val EXTRA_MATCH_ID = "com.yann.nowbarmirror.sport.extra.OVERRIDE_MATCH_ID"
        private const val EXTRA_LABEL = "com.yann.nowbarmirror.sport.extra.OVERRIDE_LABEL"

        // État process-wide de ce qui est actuellement sondé — même principe
        // que SofascoreNotificationListenerService.instance : ce service
        // n'est pas un singleton Kotlin, mais un Service Android démarré par
        // Intent, donc sync() (appelé à chaque refresh(), potentiellement
        // très souvent) a besoin de savoir CE QU'IL A DÉJÀ DEMANDÉ pour ne
        // pas relancer inutilement la coroutine de sondage à chaque appel.
        @Volatile
        private var runningKey: Triple<String, ApiSource, String>? = null

        /**
         * Aligne le sondage en tâche de fond sur l'override de la
         * notification ACTIVE ([notifKey], [override]) — appelé à chaque
         * `SofascoreNotificationListenerService.refresh()`. Ne fait rien si
         * la combinaison (notif, source, match) demandée est déjà celle en
         * cours de sondage. `null` (pas d'override pour la notif active, ou
         * plus aucune notif active) arrête le sondage en cours, s'il y en
         * avait un.
         */
        fun sync(context: Context, notifKey: String?, override: SofascoreApiOverride?) {
            if (notifKey == null || override == null) {
                if (runningKey != null) stop(context)
                return
            }
            val desired = Triple(notifKey, override.source, override.matchId)
            if (runningKey == desired) return
            runningKey = desired
            val intent = Intent(context, ApiOverrideFollowService::class.java).apply {
                putExtra(EXTRA_NOTIF_KEY, notifKey)
                putExtra(EXTRA_SOURCE, override.source.name)
                putExtra(EXTRA_MATCH_ID, override.matchId)
                putExtra(EXTRA_LABEL, override.label)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** Arrête tout sondage en cours (plus aucun override actif à suivre). */
        fun stop(context: Context) {
            runningKey = null
            ApiOverrideCache.clear()
            val intent = Intent(context, ApiOverrideFollowService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }

        private fun clearRunningIfMatches(notifKey: String, source: ApiSource, matchId: String) {
            if (runningKey == Triple(notifKey, source, matchId)) runningKey = null
        }
    }
}
