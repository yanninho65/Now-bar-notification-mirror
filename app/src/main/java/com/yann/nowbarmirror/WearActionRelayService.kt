package com.yann.nowbarmirror

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
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
 * Chemins dédiés "/notifdetail/action" et "/notifdetail/dismiss", séparés de "/match"/
 * "/notification" (qui vont dans l'autre sens, téléphone -> montre) — même principe de séparation
 * par chemin que le reste de cette app (voir README, section "Deux accès notifications séparés").
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
            }
        } catch (_: Throwable) {
            // Un relais raté ne doit jamais faire planter ce service — même logique que les
            // try/catch déjà en place autour de tout ce qui touche au widget dans les deux
            // NotificationListenerServices.
        }
    }

    companion object {
        const val ACTION_PATH = "/notifdetail/action"
        const val DISMISS_PATH = "/notifdetail/dismiss"
    }
}
