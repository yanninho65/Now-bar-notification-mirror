package com.yann.nowbarmirror.wear

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

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
     * Dessine [bitmap] centré sur ([centerX], [centerY]) en conservant son
     * ratio d'origine, mis à l'échelle pour tenir dans [maxWidth]×[maxHeight]
     * (jamais étiré hors de son ratio réel) — remplace, depuis le
     * 16/09/2026, l'étirement dans un cadre fixe 190×100 qui aplatissait
     * l'image de notif Sofascore (voir le commentaire de tête de fichier).
     */
    private fun drawFittedBitmap(canvas: Canvas, bitmap: Bitmap, centerX: Float, centerY: Float, maxWidth: Float, maxHeight: Float) {
        val scale = minOf(maxWidth / bitmap.width, maxHeight / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val dest = RectF(centerX - width / 2f, centerY - height / 2f, centerX + width / 2f, centerY + height / 2f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(bitmap, null, dest, paint)
    }

    /**
     * Dessine [text] centré sur ([centerX], [centerY]), blanc gras avec
     * contour noir (lisible par-dessus une image ou un fond quelconque).
     * Part de [maxTextSize] et réduit jusqu'à [minTextSize] si le texte
     * mesuré dépasse [maxWidth] — garde-fou pour les libellés de période
     * les plus longs (ex. "Prolongation", "Forfait technique") sans
     * jamais dépasser la corde du cercle disponible à cette hauteur, et
     * pour un score à deux chiffres des deux côtés + crochets.
     */
    private fun drawFittedText(
        canvas: Canvas,
        text: String,
        centerX: Float,
        centerY: Float,
        maxTextSize: Float,
        minTextSize: Float,
        maxWidth: Float,
        strokeWidthPx: Float
    ) {
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
            textSize = maxTextSize
        }
        val measuredWidth = fillPaint.measureText(text)
        if (measuredWidth > maxWidth) {
            fillPaint.textSize = (maxTextSize * (maxWidth / measuredWidth)).coerceAtLeast(minTextSize)
        }
        val strokePaint = Paint(fillPaint).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
            color = Color.BLACK
        }
        val bounds = Rect()
        fillPaint.getTextBounds(text, 0, text.length, bounds)
        val baselineY = centerY - bounds.exactCenterY()
        canvas.drawText(text, centerX, baselineY, strokePaint)
        canvas.drawText(text, centerX, baselineY, fillPaint)
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
