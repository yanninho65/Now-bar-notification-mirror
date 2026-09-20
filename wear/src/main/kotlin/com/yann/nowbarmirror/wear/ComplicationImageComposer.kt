package com.yann.nowbarmirror.wear

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils

/**
 * Compose les images utilisées par les complications de score.
 *
 * - [composeRoundImage] : image UNIQUE (logos/notifImage + score + période,
 *   cuits dans le même bitmap, fond circulaire plein), pour le SMALL_IMAGE
 *   du rond Dashboard (voir ScoreComplicationService.buildSmallImage et le
 *   README, section "SMALL_IMAGE : disposition verticale (image en haut,
 *   score au milieu, période en bas)").
 *
 * Disposition REVUE le 16/09/2026 (retour à trois lignes empilées
 * verticalement, capture d'écran fournie par Yann à l'appui) : le
 * correctif du 15/09/2026 ("tout sur une ligne", voir historique README)
 * avait résolu le problème d'affichage mais produisait un rendu jugé trop
 * plat par Yann — l'image de notif, étirée dans un cadre 190×100 SANS
 * conserver son ratio d'origine, apparaissait aplatie, et le score,
 * minuscule à côté d'elle, était peu lisible. Cette version :
 * 1. image de notif (ou logos) EN HAUT, réduite, ratio d'origine conservé
 *    ([drawFittedBitmap] — fini l'étirement qui aplatissait l'image) ;
 * 2. score AU MILIEU, très agrandi (voir `SCORE_MAX_TEXT_SIZE` — encore
 *    revu à la hausse le 16/09/2026 suite au retour de Yann après cette
 *    première capture) ;
 * 3. période/statut (`MatchClock.label`) EN BAS, plus petit que le score,
 *    réduit automatiquement si le texte est trop long pour la corde du
 *    cercle à cette hauteur ([drawFittedText]) — absent de la version du
 *    15/09/2026, qui n'avait pas de place pour une 3e ligne dans son
 *    agencement "tout sur une ligne".
 * Les trois lignes restent chacune sous le rayon du cercle (vérifié au
 * pire cas, coin des rectangles compris) pour ne rien perdre au
 * recadrage circulaire du rond Dashboard.
 *
 * Les anciens composeurs d'icône carrée pour SHORT_TEXT (composeColorIcon,
 * composeMonochromeIcon) ont été RETIRÉS le 16/09/2026 en même temps que
 * SHORT_TEXT lui-même (voir README) — SMALL_IMAGE couvrait déjà tous les
 * cas d'usage réels.
 *
 * REPLI DEUX-LOGOS RETIRÉ le 16/09/2026 (même date, refonte "Sofascore
 * comme base" — voir README) : `drawColorLogo`/`MatchScore.homeLogo`/
 * `awayLogo` dessinaient les deux logos d'équipe API côte à côte quand
 * [MatchScore.notifImage] était `null`. Depuis cette date, Sofascore est
 * TOUJOURS la source de l'image (l'API ne fournit plus jamais de logo,
 * voir mobile/WatchSync.kt) : ce repli ne pouvait plus jamais afficher de
 * vrais logos, seulement ses deux pastilles grises placeholder — remplacé
 * par [drawPlaceholder], une SEULE pastille, plus simple pour le même
 * résultat visuel (montrer qu'il n'y a pas d'image, sans rien inventer).
 *
 * - [composeNotificationImage] (AJOUTÉ 20/09/2026, demande de Yann) : image ronde pour la
 *   complication SMALL_IMAGE distincte "Notification" (voir NotificationComplicationService),
 *   pensée pour le rond central du tableau de bord Samsung. Reprend le même principe que
 *   [composeRoundImage] (fond circulaire plein, contenu empilé verticalement, texte blanc gras +
 *   contour noir) mais avec 4 lignes au lieu de 3 : image de notif en haut (avec un petit badge
 *   rond de l'icône de l'app en bas à droite — [drawCircularBadge] — même idée que la vue "Toutes
 *   notifs" du widget téléphone, NowBarWidgetProvider.applyAllNotifSlotAsGeneric : même
 *   traitement pour toutes les apps, pas de cas particulier comme Sofascore), titre sur une ligne
 *   ([drawFittedText] avec `ellipsizeIfNeeded = true`), puis le texte de la notif sur jusqu'à 2
 *   lignes avec la taille de police ajustée automatiquement pour maximiser le nombre de
 *   caractères affichés tout en restant lisible ([drawWrappedBodyText], via StaticLayout).
 */
object ComplicationImageComposer {

    // Constantes de composeRoundImage — image UNIQUE pour le rond
    // Dashboard Samsung en SMALL_IMAGE (voir ScoreComplicationService.
    // buildSmallImage). Fond circulaire plein peint sous tout le contenu
    // (nécessaire pour rester visible quel que soit le cadran derrière la
    // complication — voir l'historique README du 15/09/2026, section
    // "SMALL_IMAGE : fond plein + tout sur une ligne").
    private const val ROUND_SIZE = 320
    private const val ROUND_CENTER = ROUND_SIZE / 2f
    private const val ROUND_RADIUS = ROUND_SIZE / 2f
    private const val ROUND_BACKGROUND_COLOR = "#1B1F27"

    // Ligne du HAUT — image de notif Sofascore (ratio conservé, voir
    // drawFittedBitmap) ou, à défaut (extraction échouée côté téléphone),
    // une pastille placeholder unique (voir drawPlaceholder — plus de
    // repli "deux logos API" depuis le 16/09/2026, voir doc de classe).
    // Cadre agrandi le 16/09/2026 (retour de Yann : "les logos doivent
    // être plus gros") — la ligne du score a été descendue pour lui
    // laisser la place (voir plus bas) plutôt que de la faire déborder du
    // cercle. Vérifié au pire cas (coin du cadre 180×86, le plus loin du
    // centre du cercle) : distance ≈149px pour un rayon de 160px, marge de
    // sécurité ~11px.
    private const val TOP_ROW_CENTER_Y = ROUND_CENTER - 76f
    private const val TOP_NOTIF_MAX_WIDTH = 180f
    private const val TOP_NOTIF_MAX_HEIGHT = 86f
    private const val TOP_PLACEHOLDER_RADIUS = 40f

    // Ligne du MILIEU — score, encore agrandi et descendu le 16/09/2026
    // (retour de Yann : "le score doit être plus gros et peut être mis
    // plus bas pour laisser de la place aux logos"). Toujours un bon
    // espace au-dessus avec la ligne du haut agrandie (~28px de marge) et
    // en dessous avec la ligne du bas (~40px). maxWidth généreux (la
    // ligne du centre est la corde la plus large du cercle à ce décalage
    // encore modeste) : le repli sur drawFittedText ne sert que de
    // garde-fou pour un score à deux chiffres des deux côtés + crochets
    // (ex. "[12]-11") — vérifié au pire cas (maxWidth atteint) : marge
    // ~12px sous le rayon du cercle.
    private const val SCORE_CENTER_Y = ROUND_CENTER + 28f
    private const val SCORE_MAX_TEXT_SIZE = 76f
    private const val SCORE_MIN_TEXT_SIZE = 46f
    private const val SCORE_MAX_TEXT_WIDTH = 270f
    private const val SCORE_STROKE_WIDTH = 8f

    // Ligne du BAS — période/statut (MatchClock.label), légèrement
    // agrandie le 16/09/2026 (retour de Yann), toujours plus petite que
    // le score. Absente si MatchClock.label renvoie une chaîne vide (voir
    // MatchClock.kt). Décalage et largeur max légèrement resserrés par
    // rapport à la version précédente (106px/220px) pour compenser
    // l'agrandissement du texte et garder de la marge sous le rayon du
    // cercle (vérifié au pire cas, texte au maxWidth ET à la taille
    // maximale non réduite simultanément : marge ~7px).
    private const val PERIOD_CENTER_Y = ROUND_CENTER + 100f
    private const val PERIOD_MAX_TEXT_SIZE = 34f
    private const val PERIOD_MIN_TEXT_SIZE = 18f
    private const val PERIOD_MAX_TEXT_WIDTH = 200f
    private const val PERIOD_STROKE_WIDTH = 5f

    // Constantes de composeNotificationImage (AJOUTÉES 20/09/2026) — complication "Notification",
    // 4 lignes empilées au lieu de 3 (image+titre+2 lignes de texte contre image+score+période),
    // donc des marges un peu plus serrées que composeRoundImage ci-dessus pour tout garder sous
    // le rayon du cercle (ROUND_RADIUS=160). Vérifiées au pire cas (coin le plus loin du centre
    // pour chaque zone) : marge ~5-13px selon la zone, comparable aux marges déjà en place plus
    // haut. Valeurs de départ, à ajuster si besoin (Yann : "on ajustera si besoin") une fois vues
    // sur la montre.

    // Ligne du HAUT — image de la notif (n'importe quelle app, ratio d'origine conservé, voir
    // drawFittedBitmap) avec un petit badge rond de l'icône de l'app en bas à droite de l'image
    // (drawCircularBadge, même idée que la vue "Toutes notifs" du widget téléphone). Sans image :
    // l'icône de l'app remplit directement la zone (pas de badge alors, même règle que le
    // widget) ; sans image NI icône (résolution PackageManager échouée) : pastille placeholder,
    // réutilise TOP_PLACEHOLDER_RADIUS ci-dessus.
    private const val NOTIF_IMAGE_CENTER_Y = ROUND_CENTER - 90f
    private const val NOTIF_IMAGE_MAX_WIDTH = 170f
    private const val NOTIF_IMAGE_MAX_HEIGHT = 78f
    private const val NOTIF_BADGE_RADIUS = 26f
    private const val NOTIF_BADGE_RING_WIDTH = 4f

    // Ligne du TITRE — une seule ligne, réduite puis, en dernier recours (encore trop long au
    // NOTIF_TITLE_MIN_TEXT_SIZE), tronquée avec "…" ([drawFittedText], ellipsizeIfNeeded = true).
    // Proche du centre du cercle (corde la plus large disponible), marge large. Remontée de 12px
    // le 20/09/2026 (ROUND_CENTER-10 -> ROUND_CENTER-22) pour laisser plus de place au corps du
    // texte en dessous, agrandi et passé à 3 lignes ce même jour (Yann : "le texte de la
    // notification peut être plus gros et occuper une troisième ligne") — marge quasi inchangée,
    // cette ligne reste tout près du centre du cercle (corde large).
    private const val NOTIF_TITLE_CENTER_Y = ROUND_CENTER - 22f
    private const val NOTIF_TITLE_MAX_TEXT_SIZE = 32f
    private const val NOTIF_TITLE_MIN_TEXT_SIZE = 20f
    private const val NOTIF_TITLE_MAX_WIDTH = 250f
    private const val NOTIF_TITLE_STROKE_WIDTH = 4f

    // Corps du texte — REVU 20/09/2026 (Yann : "le texte de la notification peut être plus gros
    // et occuper une troisième ligne"), jusqu'à NOTIF_BODY_MAX_LINES=3 lignes (contre 2
    // auparavant) et NOTIF_BODY_MAX_TEXT_SIZE=28 (contre 26), taille ajustée automatiquement pour
    // tenir dans NOTIF_BODY_MAX_LINES en affichant le plus de caractères possible tout en restant
    // lisible, avec troncature "…" sur la dernière ligne en dernier recours si le texte ne tient
    // toujours pas à NOTIF_BODY_MIN_TEXT_SIZE ([drawWrappedBodyText], via StaticLayout).
    // NOTIF_BODY_MAX_WIDTH réduit (230 -> 210) et NOTIF_BODY_LINE_SPACING_MULT resserré
    // (1.05 -> 1.0) pour garder de la marge sous le rayon du cercle malgré la 3e ligne — vérifié
    // au pire cas (3 lignes au NOTIF_BODY_MAX_TEXT_SIZE, largeur au maxWidth) : marge ~8-12px
    // selon l'estimation de hauteur de ligne réelle du système. Valeurs de départ, à ajuster si
    // besoin une fois vues sur la montre.
    private const val NOTIF_BODY_TOP_Y = ROUND_CENTER + 4f
    private const val NOTIF_BODY_MAX_WIDTH = 210f
    private const val NOTIF_BODY_MAX_LINES = 3
    private const val NOTIF_BODY_MAX_TEXT_SIZE = 28f
    private const val NOTIF_BODY_MIN_TEXT_SIZE = 16f
    private const val NOTIF_BODY_LINE_SPACING_MULT = 1.0f
    private const val NOTIF_BODY_STROKE_WIDTH = 3.5f

    /**
     * Image UNIQUE pour le rond Dashboard Samsung en SMALL_IMAGE (voir
     * ScoreComplicationService.buildSmallImage) — fond circulaire plein,
     * puis trois lignes empilées verticalement : image de notif Sofascore
     * (ou une pastille placeholder) en haut, score au milieu en grand,
     * période en bas en plus petit. Voir le commentaire de tête de fichier
     * pour le détail de la revue du 16/09/2026.
     *
     * Contenu de la ligne du haut : [MatchScore.notifImage] si disponible
     * (image déjà combinée par Sofascore lui-même, dessinée en conservant
     * son ratio d'origine via [drawFittedBitmap] — fini l'étirement
     * 190×100 de la version précédente, qui l'aplatissait) ; sinon une
     * pastille placeholder unique (via [drawPlaceholder] — plus de repli
     * "deux logos API" depuis le 16/09/2026, voir doc de classe).
     */
    fun composeRoundImage(match: MatchScore?): Bitmap {
        val bitmap = Bitmap.createBitmap(ROUND_SIZE, ROUND_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(ROUND_BACKGROUND_COLOR)
        }
        canvas.drawCircle(ROUND_CENTER, ROUND_CENTER, ROUND_RADIUS, backgroundPaint)

        val notifImage = match?.notifImage
        if (notifImage != null) {
            drawFittedBitmap(canvas, notifImage, ROUND_CENTER, TOP_ROW_CENTER_Y, TOP_NOTIF_MAX_WIDTH, TOP_NOTIF_MAX_HEIGHT)
        } else {
            drawPlaceholder(canvas, ROUND_CENTER, TOP_ROW_CENTER_Y, TOP_PLACEHOLDER_RADIUS)
        }

        drawFittedText(
            canvas, match?.scoreText() ?: "vs",
            ROUND_CENTER, SCORE_CENTER_Y,
            SCORE_MAX_TEXT_SIZE, SCORE_MIN_TEXT_SIZE, SCORE_MAX_TEXT_WIDTH, SCORE_STROKE_WIDTH
        )

        val periodLabel = match?.let { MatchClock.label(it) }.orEmpty()
        if (periodLabel.isNotBlank()) {
            drawFittedText(
                canvas, periodLabel,
                ROUND_CENTER, PERIOD_CENTER_Y,
                PERIOD_MAX_TEXT_SIZE, PERIOD_MIN_TEXT_SIZE, PERIOD_MAX_TEXT_WIDTH, PERIOD_STROKE_WIDTH
            )
        }

        return bitmap
    }

    /**
     * Image UNIQUE pour la complication ronde "Notification" (AJOUTÉ 20/09/2026, demande de
     * Yann) — pensée pour le rond central du tableau de bord Samsung, distincte de
     * [composeRoundImage]/"Score en direct". Fond circulaire plein (même couleur que
     * [composeRoundImage], pour rester cohérent visuellement entre les deux complications), puis
     * 4 éléments empilés verticalement :
     * 1. image de la notif (n'importe quelle app mirorée, même traitement pour toutes — pas de
     *    cas particulier comme Sofascore en Score en direct), avec un petit badge rond de l'icône
     *    de l'app en bas à droite de l'image ([drawCircularBadge]) — même idée que la vue "Toutes
     *    notifs" du widget téléphone. Sans image : l'icône de l'app remplit directement la zone
     *    (pas de badge). Sans image ni icône : pastille placeholder ([drawPlaceholder]).
     * 2. titre sur une seule ligne, réduit puis tronqué en dernier recours
     *    ([drawFittedText], `ellipsizeIfNeeded = true`).
     * 3. texte de la notif sur jusqu'à 2 lignes, taille ajustée automatiquement pour maximiser le
     *    nombre de caractères affichés tout en restant lisible ([drawWrappedBodyText]).
     *
     * `notification == null` (rien reçu du téléphone depuis le dernier redémarrage du processus
     * watch, voir NotificationInfoStore) affiche juste "Aucune notification" à la place du corps
     * du texte, pas de titre ni d'image.
     */
    fun composeNotificationImage(notification: NotificationInfo?): Bitmap {
        val bitmap = Bitmap.createBitmap(ROUND_SIZE, ROUND_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(ROUND_BACKGROUND_COLOR)
        }
        canvas.drawCircle(ROUND_CENTER, ROUND_CENTER, ROUND_RADIUS, backgroundPaint)

        val notifImage = notification?.image
        val appIcon = notification?.appIcon
        when {
            notifImage != null -> {
                val dest = drawFittedBitmap(canvas, notifImage, ROUND_CENTER, NOTIF_IMAGE_CENTER_Y, NOTIF_IMAGE_MAX_WIDTH, NOTIF_IMAGE_MAX_HEIGHT)
                if (appIcon != null) {
                    drawCircularBadge(
                        canvas, appIcon,
                        dest.right - NOTIF_BADGE_RADIUS * 0.7f, dest.bottom - NOTIF_BADGE_RADIUS * 0.7f,
                        NOTIF_BADGE_RADIUS
                    )
                }
            }
            appIcon != null -> {
                // Pas d'image de notif : l'icône de l'app remplit directement la zone — même
                // règle que le widget téléphone (NowBarWidgetProvider.applyAllNotifSlotAsGeneric),
                // jamais les deux à la fois, donc pas de badge non plus ici.
                drawFittedBitmap(canvas, appIcon, ROUND_CENTER, NOTIF_IMAGE_CENTER_Y, NOTIF_IMAGE_MAX_WIDTH, NOTIF_IMAGE_MAX_HEIGHT)
            }
            else -> drawPlaceholder(canvas, ROUND_CENTER, NOTIF_IMAGE_CENTER_Y, TOP_PLACEHOLDER_RADIUS)
        }

        val title = notification?.title.orEmpty()
        if (title.isNotBlank()) {
            drawFittedText(
                canvas, title,
                ROUND_CENTER, NOTIF_TITLE_CENTER_Y,
                NOTIF_TITLE_MAX_TEXT_SIZE, NOTIF_TITLE_MIN_TEXT_SIZE, NOTIF_TITLE_MAX_WIDTH, NOTIF_TITLE_STROKE_WIDTH,
                ellipsizeIfNeeded = true
            )
        }

        val body = notification?.text.orEmpty()
        if (body.isNotBlank()) {
            drawWrappedBodyText(
                canvas, body,
                ROUND_CENTER, NOTIF_BODY_TOP_Y,
                NOTIF_BODY_MAX_WIDTH, NOTIF_BODY_MAX_LINES,
                NOTIF_BODY_MAX_TEXT_SIZE, NOTIF_BODY_MIN_TEXT_SIZE,
                NOTIF_BODY_LINE_SPACING_MULT, NOTIF_BODY_STROKE_WIDTH
            )
        } else if (notification == null) {
            drawFittedText(
                canvas, "Aucune notification",
                ROUND_CENTER, NOTIF_TITLE_CENTER_Y + 44f,
                24f, 16f, NOTIF_BODY_MAX_WIDTH, 3f,
                ellipsizeIfNeeded = true
            )
        }

        return bitmap
    }

    /**
     * Dessine [bitmap] centré sur ([centerX], [centerY]) en conservant son
     * ratio d'origine, mis à l'échelle pour tenir dans [maxWidth]×[maxHeight]
     * (jamais étiré hors de son ratio réel) — remplace, depuis le
     * 16/09/2026, l'étirement dans un cadre fixe 190×100 qui aplatissait
     * l'image de notif Sofascore (voir le commentaire de tête de fichier).
     *
     * Renvoie le rectangle de destination réellement dessiné (AJOUTÉ 20/09/2026) — utilisé par
     * composeNotificationImage pour positionner le badge d'icône d'app au bon endroit (coin bas
     * droit de l'image telle qu'affichée, pas du cadre maximal théorique). Ignoré par
     * composeRoundImage, qui n'en a pas besoin.
     */
    private fun drawFittedBitmap(canvas: Canvas, bitmap: Bitmap, centerX: Float, centerY: Float, maxWidth: Float, maxHeight: Float): RectF {
        val scale = minOf(maxWidth / bitmap.width, maxHeight / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val dest = RectF(centerX - width / 2f, centerY - height / 2f, centerX + width / 2f, centerY + height / 2f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(bitmap, null, dest, paint)
        return dest
    }

    /**
     * Dessine [text] centré sur ([centerX], [centerY]), blanc gras avec
     * contour noir (lisible par-dessus une image ou un fond quelconque).
     * Part de [maxTextSize] et réduit jusqu'à [minTextSize] si le texte
     * mesuré dépasse [maxWidth] — garde-fou pour les libellés de période
     * les plus longs (ex. "Prolongation", "Forfait technique") sans
     * jamais dépasser la corde du cercle disponible à cette hauteur, et
     * pour un score à deux chiffres des deux côtés + crochets.
     *
     * [ellipsizeIfNeeded] (AJOUTÉ 20/09/2026, `false` par défaut — composeRoundImage n'en a pas
     * besoin, ses libellés sont toujours courts) : si `true` et que le texte dépasse encore
     * [maxWidth] une fois réduit à [minTextSize] (titre de notif arbitrairement long), tronque
     * avec "…" plutôt que de laisser le texte déborder du cercle.
     */
    private fun drawFittedText(
        canvas: Canvas,
        text: String,
        centerX: Float,
        centerY: Float,
        maxTextSize: Float,
        minTextSize: Float,
        maxWidth: Float,
        strokeWidthPx: Float,
        ellipsizeIfNeeded: Boolean = false
    ) {
        // TextPaint (pas juste Paint) : requis par TextUtils.ellipsize ci-dessous, qui n'accepte
        // qu'un TextPaint — sans effet sur measureText/getTextBounds/drawText, qui fonctionnent
        // pareil sur les deux (TextPaint hérite de Paint).
        val fillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
            textSize = maxTextSize
        }
        var measuredWidth = fillPaint.measureText(text)
        if (measuredWidth > maxWidth) {
            fillPaint.textSize = (maxTextSize * (maxWidth / measuredWidth)).coerceAtLeast(minTextSize)
            measuredWidth = fillPaint.measureText(text)
        }
        var displayText = text
        if (ellipsizeIfNeeded && measuredWidth > maxWidth) {
            displayText = TextUtils.ellipsize(text, fillPaint, maxWidth, TextUtils.TruncateAt.END).toString()
        }
        val strokePaint = Paint(fillPaint).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
            color = Color.BLACK
        }
        val bounds = Rect()
        fillPaint.getTextBounds(displayText, 0, displayText.length, bounds)
        val baselineY = centerY - bounds.exactCenterY()
        canvas.drawText(displayText, centerX, baselineY, strokePaint)
        canvas.drawText(displayText, centerX, baselineY, fillPaint)
    }

    /**
     * Corps de texte sur jusqu'à [maxLines] lignes (AJOUTÉ 20/09/2026 pour composeNotificationImage)
     * — cherche la plus grande taille entre [maxTextSize] et [minTextSize] (pas de 1px) pour
     * laquelle [text], une fois retourné à la ligne dans [maxWidth], tient en [maxLines] lignes
     * SANS troncature ("afficher le max de caractères tout en gardant lisibilité", demande de
     * Yann). Si même [minTextSize] ne suffit pas, tronque la dernière ligne avec "…"
     * (StaticLayout.Builder.setEllipsize) plutôt que de déborder du cercle ou de continuer à
     * réduire sous le seuil de lisibilité.
     *
     * Centré horizontalement (Layout.Alignment.ALIGN_CENTER) — StaticLayout ignore
     * Paint.textAlign, d'où le canvas.translate vers le coin haut-gauche du bloc plutôt que
     * [centerX] directement. Même technique contour noir + remplissage blanc que
     * [drawFittedText] (deux passes de layout.draw avec le style du Paint changé entre les
     * deux, la mesure/le retour à la ligne ne dépendant pas du style du trait).
     */
    private fun drawWrappedBodyText(
        canvas: Canvas,
        text: String,
        centerX: Float,
        topY: Float,
        maxWidth: Float,
        maxLines: Int,
        maxTextSize: Float,
        minTextSize: Float,
        lineSpacingMultiplier: Float,
        strokeWidthPx: Float
    ) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val widthPx = maxWidth.toInt().coerceAtLeast(1)

        var chosenLayout: StaticLayout? = null
        var size = maxTextSize
        while (size >= minTextSize) {
            paint.textSize = size
            val candidate = StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, lineSpacingMultiplier)
                .setIncludePad(false)
                .build()
            if (candidate.lineCount <= maxLines) {
                chosenLayout = candidate
                break
            }
            size -= 1f
        }

        val layout = chosenLayout ?: run {
            // Même à minTextSize, le texte complet ne tient pas en maxLines lignes — tronque la
            // dernière avec "…" plutôt que de le laisser déborder du cercle.
            paint.textSize = minTextSize
            StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, lineSpacingMultiplier)
                .setIncludePad(false)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setEllipsizedWidth(widthPx)
                .build()
        }

        val left = centerX - maxWidth / 2f
        canvas.save()
        canvas.translate(left, topY)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidthPx
        paint.color = Color.BLACK
        layout.draw(canvas)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        layout.draw(canvas)
        canvas.restore()
    }

    /**
     * Petit badge rond de l'icône de l'app source, en bas à droite de l'image de la notif
     * (AJOUTÉ 20/09/2026 pour composeNotificationImage — même idée que la vue "Toutes notifs" du
     * widget téléphone). Anneau de la couleur de fond du rond ([ROUND_BACKGROUND_COLOR]) sous
     * l'icône pour la détacher visuellement de l'image derrière, quelle que soit sa couleur ;
     * icône recadrée en cercle via un BitmapShader plutôt qu'un simple drawBitmap (pas de coins
     * carrés qui dépasseraient du badge rond pour une icône non déjà circulaire).
     */
    private fun drawCircularBadge(canvas: Canvas, bitmap: Bitmap, centerX: Float, centerY: Float, radius: Float) {
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(ROUND_BACKGROUND_COLOR)
        }
        canvas.drawCircle(centerX, centerY, radius + NOTIF_BADGE_RING_WIDTH, ringPaint)

        val scale = (radius * 2f) / minOf(bitmap.width, bitmap.height)
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(centerX - bitmap.width * scale / 2f, centerY - bitmap.height * scale / 2f)
        }
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(matrix)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
        canvas.drawCircle(centerX, centerY, radius, paint)
    }

    /**
     * Repli quand [MatchScore.notifImage] est indisponible (extraction
     * échouée côté téléphone) — une seule pastille centrée, depuis que
     * Sofascore est la SEULE source d'image (16/09/2026, voir doc de
     * classe) : plus de logos séparés à afficher côte à côte. #5A6478
     * (pas #3A4150, presque invisible sur le fond #1B1F27 du rond — voir
     * README, section "SMALL_IMAGE : fond plein + tout sur une ligne") :
     * montrer qu'il n'y a pas d'image, plutôt qu'un trou quasi
     * indiscernable du fond.
     */
    private fun drawPlaceholder(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#5A6478")
        }
        canvas.drawCircle(centerX, centerY, radius, paint)
    }
}
