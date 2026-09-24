package com.yann.nowbarmirror.wear

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.wear.widget.SwipeDismissFrameLayout

/**
 * NEW 21/09/2026 — écran plein écran ouvert par un tap sur la complication "Notification" (Yann :
 * "quand je clique sur la complication notification ça ouvre une fenêtre avec en haut l'image et
 * logo de la notif, la notification en grand pour lire le texte en entier et les boutons d'actions
 * de la notification et la possibilité de supprimer la notif. Adopter le style galaxy watch. Pour
 * les notifications comme les messages ou Sofascore, afficher toutes les notifs de l'expéditeur ou
 * toutes celles du match"). Voir NotificationComplicationService.notificationDetailTapAction pour
 * comment on arrive ici, et NotificationInfo/WatchNotificationSync.send pour d'où vient tout ce
 * que cet écran affiche.
 *
 * Style Galaxy Watch : fond noir OLED (activity_notification_detail.xml), gros
 * logo/image en haut, texte centré, boutons d'action en pilule et bouton de suppression rond en
 * bas, et le geste de balayage standard Wear OS pour fermer l'écran ([setupSwipeToDismiss]) plutôt
 * qu'un bouton "retour" dédié.
 *
 * UPDATED 22/09/2026 (Yann, capture à l'appui du vrai menu de notification Galaxy Watch) : les
 * pilules d'action sont désormais PLEINE LARGEUR et empilées verticalement (voir [actionChip],
 * detail_actions_container) plutôt que des petits chips côte à côte, pour coller exactement à ce
 * style natif. Une pilule "Aff. sur tél." (`R.string.action_view_on_phone`) est TOUJOURS ajoutée
 * après les actions propres de la notification — tape sur cette entrée sur le TÉLÉPHONE
 * ([PhoneRelay.sendOpen] -> mobile/WearActionRelayService.kt -> NowBarWidgetProvider.openEntry).
 * Explicitement PAS de pilule "Bloquer notifications" (présente dans le menu système natif mais
 * volontairement exclue ici, Yann : "Ne mets pas bloquer notifications").
 *
 * UPDATED 22/09/2026, deuxième passe (Yann, après premier essai sur l'appareil) : "Aff. sur tél."
 * FIXÉ — voir NowBarWidgetProvider.openEntry pour pourquoi un simple `.send()` direct ne marchait
 * pas ("le bouton [...] ne fait rien"). Pilules d'action limitées à UNE ligne, taille/police reprises
 * du thème système plutôt que codées en dur (voir [actionChip]). En-tête : image de la notif et
 * icône de l'app sur la MÊME ligne (image à gauche, icône à droite), chacune un peu plus grande
 * qu'avant et ne montrant plus que SA propre image (fini le repli de l'une sur l'autre). Le corps
 * (texte simple ou historique) n'a plus de fond derrière chaque ligne, comme une notification
 * système (voir [lineCard]).
 *
 * UPDATED 22/09/2026, troisième passe (Yann) : image de la notif et icône de l'app désormais côte
 * à côte, groupe centré sur la ligne (avant : chacune poussée vers un bord opposé par un View à
 * poids — voir activity_notification_detail.xml). Titre/texte/lignes d'historique reprennent
 * INCONDITIONNELLEMENT la police et la taille du thème système
 * (?android:attr/textAppearanceLarge / Medium — voir le layout XML et [lineCard]) au lieu de
 * tailles codées en dur — "N'en fais pas une option, je voudrais que ça soit toujours le cas".
 *
 * `android.app.Activity` plutôt que ComponentActivity/AppCompatActivity : cet écran n'a besoin
 * d'aucune Fragment ni ActionBar, et ni androidx.activity ni androidx.appcompat ne sont déjà des
 * dépendances de ce module (voir wear/build.gradle.kts) — pas la peine d'en ajouter une pour ça.
 */
class NotificationDetailActivity : Activity() {

    private lateinit var appIconView: ImageView
    private lateinit var appNameView: TextView
    private lateinit var imageView: ImageView
    private lateinit var titleView: TextView
    private lateinit var textView: TextView
    private lateinit var linesContainer: LinearLayout
    private lateinit var actionsContainer: LinearLayout
    private lateinit var deleteButton: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification_detail)

        setupSwipeToDismiss()

        appIconView = findViewById(R.id.detail_app_icon)
        appNameView = findViewById(R.id.detail_app_name)
        imageView = findViewById(R.id.detail_image)
        titleView = findViewById(R.id.detail_title)
        textView = findViewById(R.id.detail_text)
        linesContainer = findViewById(R.id.detail_lines_container)
        actionsContainer = findViewById(R.id.detail_actions_container)
        deleteButton = findViewById(R.id.detail_delete_button)

        // FIXED 21/09/2026 (Yann : "ça ne montre pas toujours la notification qui est affichée sur
        // la complication [...] il faut vraiment que ça soit toujours la même [...] ça reste
        // bloqué sur une ancienne et sans avoir l'exhaustivité") : faire confiance à
        // NotificationInfoStore.current sans jamais le revérifier laissait cette fenêtre bloquée
        // sur une valeur périmée si CE process avait raté la dernière mise à jour en direct (même
        // si la complication, elle, avait par ailleurs reçu la sienne) — voir
        // NotificationComplicationService's doc, qui applique désormais la même correction.
        // Peint immédiatement ce qui est déjà en cache (pour éviter un écran vide le temps de la
        // relecture ci-dessous, souvent déjà correct) puis écrase avec le résultat de la relecture
        // de l'item persistant — un appel purement local, quelques dizaines de ms — dès qu'il
        // arrive, sauf si cette relecture échoue elle-même (repli sur ce qui est déjà affiché).
        //
        // UPDATED 22/09/2026 : cette relecture passe maintenant par
        // NotificationInfoStore.updateIfNotOlder (voir sa doc), qui peut renvoyer soit le résultat
        // de la relecture soit ce qui était déjà affiché (jamais de retour en arrière vers une
        // notification plus ancienne) — donc `render(outcome.info)` ci-dessous ne peut plus
        // régresser visuellement, même si le round-trip Data Layer avait pris du retard.
        NotificationInfoStore.current?.let { render(it) } ?: renderEmpty()

        // Shared re-read (AUDIT 23/09/2026 — PhoneDataLayer.readNotification, same one the
        // "Notification" complication uses, instead of a third private copy of it here).
        Thread {
            val read = PhoneDataLayer.readNotification(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (read) {
                    is PhoneDataLayer.Read.Success -> read.value?.let { render(it) } ?: renderEmpty()
                    PhoneDataLayer.Read.Failed -> Unit
                }
            }
        }.start()
    }

    private fun setupSwipeToDismiss() {
        val swipeLayout = findViewById<SwipeDismissFrameLayout>(R.id.swipe_layout)
        swipeLayout.addCallback(object : SwipeDismissFrameLayout.Callback() {
            override fun onDismissed(layout: SwipeDismissFrameLayout) {
                layout.visibility = View.GONE
                finish()
            }
        })
    }

    private fun renderEmpty() {
        titleView.text = "Aucune notification"
        textView.visibility = View.GONE
        imageView.visibility = View.GONE
        appIconView.visibility = View.GONE
        appNameView.visibility = View.GONE
        linesContainer.visibility = View.GONE
        actionsContainer.visibility = View.GONE
        deleteButton.visibility = View.GONE
    }

    private fun render(info: NotificationInfo) {
        currentInfoForActions = info
        titleView.text = info.title.ifBlank { "Notification" }

        // En-tête, UPDATED 22/09/2026 (Yann : "Mettre image notif et icone appli en haut sur même
        // ligne (image à gauche, icone à droite)") — chaque emplacement montre désormais SA propre
        // image seulement : plus de repli de l'un sur l'autre (avant : l'image en grand retombait
        // sur l'icône de l'app quand la notif n'avait pas sa propre image).
        //
        // UPDATED 22/09/2026 x2 (Yann : "le nom sofascore s'affiche en haut alors que je ne
        // voulais que image et icône. C'est la seule appli pour laquelle ça arrive.") : le nom
        // d'app n'est plus jamais affiché, pour aucune appli — [appNameView] reste dans le layout
        // (au cas où un texte de repli redevienne utile un jour) mais n'est plus jamais rendu
        // visible ; ce cas Sofascore était le SEUL endroit du code qui l'activait (seule appli
        // pour laquelle un nom fiable est connu sans PackageManager côté montre, qui n'a pas
        // forcément l'app source installée), d'où le fait que "ça n'arrivait que pour Sofascore".
        if (info.appIcon != null) {
            appIconView.setImageBitmap(circularBitmap(info.appIcon))
            appIconView.visibility = View.VISIBLE
        } else {
            appIconView.visibility = View.GONE
        }
        appNameView.visibility = View.GONE

        if (info.image != null) {
            imageView.setImageBitmap(circularBitmap(info.image))
            imageView.visibility = View.VISIBLE
        } else {
            imageView.visibility = View.GONE
        }

        // Corps : l'historique (messages de la conversation / lignes d'événements du match) s'il y
        // en a un, sinon le texte simple de la notification — voir NotificationInfo.detailLines'
        // doc pour pourquoi les deux ne sont jamais affichés ensemble (la ligne la plus récente de
        // l'historique EST déjà ce texte).
        if (info.detailLines.isNotEmpty()) {
            textView.visibility = View.GONE
            linesContainer.visibility = View.VISIBLE
            linesContainer.removeAllViews()
            info.detailLines.forEach { line -> linesContainer.addView(lineCard(line)) }
        } else if (info.text.isNotBlank()) {
            linesContainer.visibility = View.GONE
            textView.visibility = View.VISIBLE
            textView.text = info.text
        } else {
            textView.visibility = View.GONE
            linesContainer.visibility = View.GONE
        }

        // Boutons d'action — seuls les LIBELLÉS ont pu traverser jusqu'à la montre (voir
        // NotificationInfo's doc) ; le tap envoie juste "actionIndex=N" au téléphone, qui retrouve
        // le vrai PendingIntent dans son propre cache (NowBarWidgetProvider.fireAction). Style
        // Galaxy Watch natif (Yann, 22/09/2026, capture à l'appui) : une pilule PLEINE LARGEUR par
        // action, empilées verticalement, plutôt que les petits chips côte à côte d'avant — voir
        // actionChip()/activity_notification_detail.xml (detail_actions_container désormais
        // vertical/match_parent). La pilule "Aff. sur tél." (NEW 22/09/2026, Yann : "je veux aussi
        // qu'il y ait afficher sur téléphone pour ouvrir la notification sur le téléphone") est
        // TOUJOURS ajoutée après les actions propres de la notification, contrairement à
        // "Bloquer notifications" du menu système natif que Yann a explicitement demandé de ne PAS
        // reproduire ici.
        actionsContainer.removeAllViews()
        info.actionLabels.forEachIndexed { index, label ->
            actionsContainer.addView(
                actionChip(label) { PhoneRelay.sendAction(applicationContext, currentInfoForActions, index) }
            )
        }
        actionsContainer.addView(
            actionChip(getString(R.string.action_view_on_phone)) {
                PhoneRelay.sendOpen(applicationContext, currentInfoForActions)
            }
        )
        actionsContainer.visibility = View.VISIBLE

        deleteButton.visibility = View.VISIBLE
        addPressFeedback(deleteButton)
        deleteButton.setOnClickListener {
            PhoneRelay.sendDismiss(applicationContext, info)
            // Retour immédiat, sans attendre la confirmation asynchrone du téléphone — même esprit
            // que le reste de cette app (voir PhoneRelay's doc).
            finish()
        }
    }

    /**
     * NEW 22/09/2026 (Yann : "quand je clique sur les boutons de la fenêtre, il manque une
     * animation pour montrer que j'ai appuyé. Par exemple, sur les notifs système, le bouton se
     * réduit légèrement et devient un peu blanc pour le signaler.") : les pilules d'action et le
     * bouton de suppression n'avaient aucun retour tactile — leur fond est un simple `<shape>`
     * sans state list (bg_action_chip.xml / bg_delete_button.xml), donc sans le retour visuel par
     * défaut qu'un Button/ImageButton a normalement avec l'arrière-plan système. Reproduit les
     * DEUX effets décrits : léger rétrécissement (scale 0.94, animé) ET éclaircissement du fond
     * (via l'état `state_pressed` désormais présent dans ces deux drawables, voir
     * detail_chip_bg_pressed/detail_delete_bg_pressed dans colors.xml).
     *
     * En OnTouchListener plutôt que dans OnClickListener pour réagir dès l'appui (ACTION_DOWN),
     * pas seulement au relâchement — exactement comme le retour système. Revient à l'état normal
     * aussi bien sur un relâchement (ACTION_UP) que sur un doigt qui glisse hors du bouton avant
     * de le relâcher (ACTION_CANCEL). Renvoie toujours `false` : ce listener ne fait QUE l'anim,
     * le clic normal (OnClickListener, posé séparément par l'appelant) continue de se déclencher
     * comme avant.
     */
    private fun addPressFeedback(view: View) = DetailViews.addPressFeedback(view)  // shared since 24/09/2026

    /**
     * UPDATED 22/09/2026 (Yann : "Ne pas mettre de fond sur le texte de la notif comme les
     * notifications système") : simple texte empilé, sans carte/fond derrière chaque ligne — juste
     * un espacement vertical entre les lignes, comme le corps d'une notification système.
     *
     * UPDATED 22/09/2026 x2 (Yann : "Grossis les polices [...] taille système [...] toujours le
     * cas") : même traitement que detail_text dans le layout XML
     * (?android:attr/textAppearanceMedium), mais posé en code puisque ces TextViews sont
     * construites dynamiquement — @Suppress("DEPRECATION") : la variante à un seul argument de
     * setTextAppearance n'existe qu'à partir de l'API 23, celle-ci (Context, Int) fonctionne à
     * toutes les API et évite d'ajouter androidx.core comme dépendance juste pour ça.
     */
    private fun lineCard(text: String): TextView = DetailViews.lineCard(this, text)

    /**
     * Une pilule d'action PLEINE LARGEUR, empilée verticalement avec les autres — style menu de
     * notification Galaxy Watch natif (fond gris uni, coins très arrondis), plutôt que les petits
     * chips côte à côte d'avant. [onClick] laisse ce chip servir aussi bien une action propre de
     * la notification (index -> PhoneRelay.sendAction) que la pilule "Aff. sur tél."
     * (PhoneRelay.sendOpen) sans dupliquer la construction du bouton.
     *
     * UPDATED 22/09/2026 (Yann : "limiter à une ligne les actions [...] reprendre la taille et la
     * police du système") : taille/police du Button telles que fournies par le thème système (plus
     * de textSize/typeface gras codés en dur ici), et le libellé est forcé sur UNE seule ligne
     * (tronqué avec "…" s'il ne tient pas) plutôt que de pouvoir passer sur plusieurs lignes.
     */
    private fun actionChip(label: String, onClick: () -> Unit): Button = DetailViews.actionChip(this, label, onClick)

    /** dp -> px, pour les LayoutParams/paddings construits en code (les layouts XML gèrent ça tout seuls). */
    private fun dp(value: Int): Int = DetailViews.dp(this, value)

    // Le chip a besoin de la même NotificationInfo que celle actuellement rendue pour adresser sa
    // demande — voir PhoneRelay.sendAction. Renseigné juste avant de construire les chips.
    private lateinit var currentInfoForActions: NotificationInfo

    /** Recadre [source] en cercle — même algorithme que mobile/widget/NowBarWidgetProvider.circularBitmap (dupliqué, modules Gradle distincts). */
    private fun circularBitmap(source: Bitmap): Bitmap = DetailViews.circularBitmap(source)
}
