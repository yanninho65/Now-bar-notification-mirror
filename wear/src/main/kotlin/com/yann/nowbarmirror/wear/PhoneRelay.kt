package com.yann.nowbarmirror.wear

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * NEW 21/09/2026, écran de détail de la complication "Notification" (voir
 * NotificationDetailActivity.kt) — envoie au téléphone les demandes d'action/suppression sur
 * l'entrée actuellement affichée, reçues côté téléphone par
 * mobile/WearActionRelayService.kt (voir sa doc pour le format JSON et le choix des chemins).
 *
 * Un PendingIntent ne peut pas traverser vers la montre (voir NotificationInfo's doc) : la montre
 * ne peut qu'ADRESSER sa demande par l'identité de l'entrée ([entryKey]/[entryPostTimeMillis]/
 * [kind]) — c'est le téléphone qui retrouve et déclenche le vrai PendingIntent/la vraie
 * suppression dans son propre cache en mémoire.
 *
 * Envoi "fire and forget" sur un thread dédié plutôt que d'attendre la confirmation d'envoi —
 * même esprit que le reste de cette app (ex. SofascoreNotificationListenerService.dismiss, dont le
 * bouton de suppression ne "attend" jamais le round-trip asynchrone d'onNotificationRemoved pour
 * son propre retour visuel immédiat) : l'appelant (l'Activity) ferme/actualise son UI tout de
 * suite, sans dépendre du réseau Bluetooth téléphone-montre pour rester réactif.
 */
object PhoneRelay {

    private const val ACTION_PATH = "/notifdetail/action"
    private const val DISMISS_PATH = "/notifdetail/dismiss"
    private const val OPEN_PATH = "/notifdetail/open"
    private const val NODE_FETCH_TIMEOUT_SECONDS = 3L

    fun sendAction(context: Context, info: NotificationInfo, actionIndex: Int) {
        send(context, ACTION_PATH, payload(info) { put("actionIndex", actionIndex) })
    }

    fun sendDismiss(context: Context, info: NotificationInfo) {
        send(context, DISMISS_PATH, payload(info))
    }

    // NEW 22/09/2026 — pill "Aff. sur tél." de l'écran de détail : demande au téléphone d'ouvrir
    // cette entrée là-bas (voir NowBarWidgetProvider.openEntry côté mobile/WearActionRelayService.kt).
    fun sendOpen(context: Context, info: NotificationInfo) {
        send(context, OPEN_PATH, payload(info))
    }

    // NEW 24/09/2026 — "Messages" list (MessagesActivity), addressed by the phone notification key
    // alone; handled on the phone by WearActionRelayService -> MirrorNotificationListener.
    fun sendMessageAction(context: Context, key: String, actionIndex: Int) {
        send(context, "/msgdetail/action", JSONObject().put("key", key).put("actionIndex", actionIndex).toString().toByteArray(Charsets.UTF_8))
    }

    fun sendMessageDismiss(context: Context, key: String) {
        send(context, "/msgdetail/dismiss", JSONObject().put("key", key).toString().toByteArray(Charsets.UTF_8))
    }

    fun sendMessageOpen(context: Context, key: String) {
        send(context, "/msgdetail/open", JSONObject().put("key", key).toString().toByteArray(Charsets.UTF_8))
    }

    // NEW 24/09/2026 — phone-row app icon of MessagesActivity: open that app on the phone.
    fun sendOpenAppOnPhone(context: Context, packageName: String) {
        send(context, "/msgdetail/openapp", JSONObject().put("pkg", packageName).toString().toByteArray(Charsets.UTF_8))
    }

    // NEW 25/09/2026 — "Sport" screen (SportActivity), addressed by the phone's Sofascore notification
    // key; handled on the phone by WearActionRelayService -> SofascoreNotificationListenerService.
    // Follow with a blank key = back to automatic (latest match).
    fun sendSportFollow(context: Context, key: String) {
        send(context, "/sportdetail/follow", JSONObject().put("key", key).toString().toByteArray(Charsets.UTF_8))
    }

    fun sendSportDismiss(context: Context, key: String) {
        send(context, "/sportdetail/dismiss", JSONObject().put("key", key).toString().toByteArray(Charsets.UTF_8))
    }

    /** Fires Sofascore's "Mettre l'événement en silencieux" on the phone, which then deletes the notification. */
    fun sendSportMute(context: Context, key: String) {
        send(context, "/sportdetail/mute", JSONObject().put("key", key).toString().toByteArray(Charsets.UTF_8))
    }

    fun sendSportOpen(context: Context, key: String) {
        send(context, "/sportdetail/open", JSONObject().put("key", key).toString().toByteArray(Charsets.UTF_8))
    }

    private inline fun payload(info: NotificationInfo, extra: JSONObject.() -> Unit = {}): ByteArray {
        val json = JSONObject().apply {
            put("kind", info.kind)
            put("key", info.entryKey)
            put("postTimeMillis", info.entryPostTimeMillis)
            extra()
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    private fun send(context: Context, path: String, payload: ByteArray) {
        Thread {
            try {
                val nodes = Tasks.await(
                    Wearable.getNodeClient(context).connectedNodes,
                    NODE_FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS
                )
                nodes.forEach { node ->
                    Wearable.getMessageClient(context).sendMessage(node.id, path, payload)
                }
            } catch (_: Exception) {
                // Téléphone injoignable (Bluetooth coupé, app téléphone tuée...) : rien à faire de
                // plus ici, même raisonnement "best effort" que le reste de la synchronisation
                // Data Layer de cette app (voir WatchSync/WatchNotificationSync côté téléphone).
            }
        }.start()
    }
}
