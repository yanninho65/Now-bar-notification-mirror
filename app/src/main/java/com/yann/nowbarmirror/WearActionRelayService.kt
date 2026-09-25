package com.yann.nowbarmirror

import android.app.PendingIntent
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.yann.nowbarmirror.sport.SofascoreNotificationListenerService
import com.yann.nowbarmirror.widget.NowBarWidgetProvider
import org.json.JSONObject

/**
 * NEW 21/09/2026, écran de détail de la complication montre "Notification" (Yann : "quand je
 * clique sur la complication notification ça ouvre une fenêtre [...] les boutons d'actions de la
 * notification et la possibilité de supprimer la notif").
 *
 * Reçoit, depuis la montre (wear/NotificationDetailActivity.kt via wear/PhoneRelay.kt), les
 * demandes d'action/suppression sur l'entrée actuellement affichée en "Dernière notif" — un
 * PendingIntent ne peut pas traverser vers la montre (voir WatchNotificationSync.send : seuls les
 * LIBELLÉS des actions y sont envoyés), donc la montre ne peut qu'ADRESSER sa demande par
 * l'identité de l'entrée (clé/postTime/kind) ; c'est ce service, tournant côté téléphone, qui
 * retrouve et déclenche le VRAI PendingIntent (ou la VRAIE suppression) — voir
 * NowBarWidgetProvider.fireAction/dismissEntry.
 *
 * Chemins dédiés "/notifdetail/action", "/notifdetail/dismiss" et "/notifdetail/open" (NEW
 * 22/09/2026, bouton "Aff. sur tél." — voir wear/NotificationDetailActivity.kt et
 * NowBarWidgetProvider.openEntry), séparés de "/match"/"/notification" (qui vont dans l'autre
 * sens, téléphone -> montre) — même principe de séparation par chemin que le reste de cette app
 * (voir README, section "Deux accès notifications séparés").
 *
 * Payload JSON plutôt qu'un format délimité par un séparateur simple ("|", "::"...) : une clé de
 * notification Android (StatusBarNotification.key) contient déjà elle-même des "|" ("0|pkg|id|tag|
 * uid"), donc n'importe quel délimiteur simple pourrait la couper au mauvais endroit — org.json
 * (déjà utilisé ailleurs dans cette app, voir WidgetAllNotificationsStore) évite ce problème sans
 * dépendance supplémentaire.
 *
 * `onMessageReceived` tourne déjà sur un thread de fond fourni par le système (pas le thread
 * principal) — même raisonnement que NotificationDataListenerService côté montre — donc les
 * opérations ci-dessous (recherche dans les caches en mémoire, `startService`, `pendingIntent.
 * send()`) sont sans risque ici.
 */
class WearActionRelayService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        try {
            val json = JSONObject(String(event.data, Charsets.UTF_8))
            // NEW 24/09/2026 — watch "Messages" list: addressed by notification key alone, handled
            // by the connected MirrorNotificationListener against the live notification center.
            if (event.path == MSG_OPEN_APP_PATH) {
                openAppOnPhone(json.optString("pkg"))
                return
            }
            // NEW 25/09/2026 — watch "Sport" screen: Sofascore notification key (blank key on
            // "follow" = back to automatic), handled by the connected Sport listener.
            if (event.path.startsWith(SPORT_PREFIX)) {
                val sportKey = json.optString("key")
                when (event.path) {
                    SPORT_FOLLOW_PATH -> SofascoreNotificationListenerService.followFromWatch(sportKey)
                    SPORT_DISMISS_PATH -> if (sportKey.isNotBlank()) SofascoreNotificationListenerService.dismissFromWatch(sportKey)
                    SPORT_OPEN_PATH -> if (sportKey.isNotBlank()) SofascoreNotificationListenerService.openOnPhoneFromWatch(sportKey)
                }
                return
            }
            if (event.path.startsWith(MSG_PREFIX)) {
                val msgKey = json.optString("key")
                if (msgKey.isBlank()) return
                when (event.path) {
                    MSG_ACTION_PATH -> json.optInt("actionIndex", -1).takeIf { it >= 0 }
                        ?.let { MirrorNotificationListener.fireMessageAction(msgKey, it) }
                    MSG_DISMISS_PATH -> MirrorNotificationListener.dismissMessage(msgKey)
                    MSG_OPEN_PATH -> MirrorNotificationListener.openMessageOnPhone(msgKey)
                }
                return
            }
            val kind = json.optString("kind")
            val key = json.optString("key")
            val postTimeMillis = json.optLong("postTimeMillis", -1L)
            if (key.isBlank()) return

            when (event.path) {
                ACTION_PATH -> {
                    val actionIndex = json.optInt("actionIndex", -1)
                    if (actionIndex >= 0) {
                        // NEW 21/09/2026, même correctif que côté widget (voir
                        // widget.WidgetAction.dismissesOnFire) : une action "silencieuse" (marquer
                        // comme lu/supprimer/archiver/muet) doit aussi faire disparaître l'entrée
                        // ici, sans quoi l'écran de détail montre resterait affiché comme si de
                        // rien n'était après l'appui, exactement le même symptôme que sur le widget.
                        val result = NowBarWidgetProvider.fireAction(key, postTimeMillis, actionIndex)
                        if (result == NowBarWidgetProvider.FireActionResult.FIRED_DISMISS) {
                            NowBarWidgetProvider.dismissEntry(applicationContext, kind, key, postTimeMillis)
                        }
                    }
                }
                DISMISS_PATH -> {
                    NowBarWidgetProvider.dismissEntry(applicationContext, kind, key, postTimeMillis)
                }
                // NEW 22/09/2026 — "Aff. sur tél." pill de l'écran de détail montre : ouvre cette
                // entrée sur LE TÉLÉPHONE (voir NowBarWidgetProvider.openEntry) plutôt que de
                // supprimer ou d'actionner un bouton de la notification elle-même.
                OPEN_PATH -> {
                    NowBarWidgetProvider.openEntry(applicationContext, key, postTimeMillis)
                }
            }
        } catch (_: Throwable) {
            // Un relais raté ne doit jamais faire planter ce service — même logique que les
            // try/catch déjà en place autour de tout ce qui touche au widget dans les deux
            // NotificationListenerServices.
        }
    }

    /**
     * NEW 24/09/2026 — app icon of the watch "Messages" list's phone row: opens that app on the
     * phone through the same relay notification + full-screen intent as "Aff. sur tél."
     * (NowBarWidgetProvider.postOpenOnPhone; a background service can't start an Activity itself).
     */
    private fun openAppOnPhone(pkg: String) {
        if (pkg.isBlank()) return
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return
        val pendingIntent = PendingIntent.getActivity(
            this, OPEN_APP_REQUEST_CODE_BASE + (pkg.hashCode() and 0xFFFF), launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
        NowBarWidgetProvider.postOpenOnPhone(applicationContext, label, "", BitmapUtils.AppIcons.get(applicationContext, pkg), pendingIntent)
    }

    companion object {
        private const val OPEN_APP_REQUEST_CODE_BASE = 6_000_000
        const val MSG_OPEN_APP_PATH = "/msgdetail/openapp"
        const val ACTION_PATH = "/notifdetail/action"
        const val DISMISS_PATH = "/notifdetail/dismiss"
        const val OPEN_PATH = "/notifdetail/open"
        const val MSG_PREFIX = "/msgdetail/"
        const val MSG_ACTION_PATH = "/msgdetail/action"
        const val MSG_DISMISS_PATH = "/msgdetail/dismiss"
        const val MSG_OPEN_PATH = "/msgdetail/open"
        const val SPORT_PREFIX = "/sportdetail/"
        const val SPORT_FOLLOW_PATH = "/sportdetail/follow"
        const val SPORT_DISMISS_PATH = "/sportdetail/dismiss"
        const val SPORT_OPEN_PATH = "/sportdetail/open"
    }
}
