# Vue "Toutes notifs" + 5e match Sport + bouton droit (17/09/2026)

## Correctif du même jour (deuxième passe)
Deux points relevés par Yann après la première livraison :
1. **Suppression** : une notif effacée du centre de notifs doit disparaître
   du widget. La première version de `WidgetAllNotificationsStore` gardait
   volontairement l'historique même après suppression ("les 5 dernières
   REÇUES") — comportement corrigé : `WidgetAllNotificationsStore.remove()`
   (nouveau) est maintenant appelé depuis `MirrorNotificationListener.onNotificationRemoved`
   (pour toute notif générique supprimée, qu'elle soit ou non l'actuelle
   "dernière") et depuis `SofascoreNotificationListenerService.onNotificationRemoved`
   (pour Sofascore, en plus de `refresh()` qui gère déjà la vue Sport
   elle-même). Sans effet si la clé n'était pas dans l'historique.
2. **Apparence Sofascore dans "Toutes notifs"** : déjà conforme depuis la
   première version (`applyAllNotifSlotAsMatch` réutilise exactement
   `SofascoreMatchPresentation.scoreText`/`periodLabel`, mêmes vues
   structurées comme `widget_match_N`) — repointé ici pour confirmation,
   aucun changement de code nécessaire sur ce point.

## Décision
Trois demandes traitées ensemble car elles touchent le même widget lock-screen
(`NowBarWidgetProvider`) :
1. Vue "Sport" : 5e emplacement de match (était 4).
2. Nouveau bouton, à l'extrémité droite du widget, qui bascule directement
   entre la vue Sport et une nouvelle vue "Toutes notifs".
3. Nouvelle vue "Toutes notifs" : les 5 dernières notifications reçues
   (toutes apps mirrorées + Sofascore), présentées comme la vue Sport
   (vignettes côte à côte), avec une présentation par vignette :
   image de la notif en haut, petite icône de l'app en médaillon dans
   l'angle de l'image (sauf si pas d'image → icône appli à la place),
   titre en dessous sur 2 lignes max sans déborder sur la vignette
   voisine. Les notifs Sofascore gardent leur présentation actuelle
   (image + score + période) même dans cette vue.

## Interprétation retenue (à confirmer)
Le message de Yann ne redemandait explicitement que 2 vues ("changer vue
entre sport et toutes notifs"), sans reparler de la vue "dernière
notification" existante (bouton gauche, avec suppression + actions). Choix
fait : NE RIEN retirer de ce qui existe déjà. Le widget a maintenant 3 vues
et 2 boutons :
- **Bouton gauche** (existant, sous l'icône) : inchangé dans son rôle —
  bascule LATEST ⟷ dernière vue Sport/Toutes-notifs consultée (mémorisée
  dans `WidgetViewModePrefs`, au lieu de toujours retomber sur Sport).
- **Bouton droit** (NOUVEAU, extrémité droite du widget) : bascule
  uniquement Sport ⟷ Toutes-notifs, jamais visible sur la vue LATEST.

Si ce n'est pas ce que tu avais en tête (par ex. si tu voulais que "Toutes
notifs" REMPLACE la vue "dernière notification"), dis-le — c'est un
changement localisé à `WidgetViewModePrefs`/`NowBarWidgetProvider`.

## Fichiers
NOUVEAU (à créer) :
- app/src/main/java/com/yann/nowbarmirror/widget/WidgetAllNotificationsStore.kt

MODIFIÉ (à remplacer en entier) :
- app/src/main/res/layout/widget_now_bar.xml
- app/src/main/java/com/yann/nowbarmirror/widget/NowBarWidgetProvider.kt
- app/src/main/java/com/yann/nowbarmirror/widget/WidgetViewModePrefs.kt
- app/src/main/java/com/yann/nowbarmirror/widget/SofascoreWidgetStore.kt (juste MAX_SLOTS 4→5)
- app/src/main/java/com/yann/nowbarmirror/MirrorNotificationListener.kt
- app/src/main/java/com/yann/nowbarmirror/sport/SofascoreNotificationListenerService.kt

Aucune ressource (drawable/string) nouvelle : tout est réutilisé tel quel
(`ic_widget_switch_view` pour le bouton droit aussi, `widget_dismiss_background`
comme fond du médaillon d'icône, `bg_sofascore_placeholder`, `ic_stat_mirror`,
`widget_empty_title`) — c'était explicitement demandé ("garder en commun tout
les assets déjà créés").

## Choix structurants

- **`WidgetAllNotificationsStore`** est un historique (les 5 DERNIÈRES
  notifs REÇUES), volontairement différent de `WidgetNotificationStore`
  (effacé dès que l'original est balayé) et de `SofascoreWidgetStore`
  (les matchs ACTUELLEMENT actifs) : une vignette "Toutes notifs" reste
  affichée même après que la notif source a disparu ailleurs — sinon
  "les 5 dernières reçues" n'aurait pas de sens. Une notif mise à jour en
  place (même clé) remplace l'entrée existante et remonte en tête plutôt
  que de dupliquer.

- **Alimentation à deux sources dans le MÊME store** : `MirrorNotificationListener.mirror()`
  pousse une entrée GENERIC à chaque notif mirrorée (comme il le fait déjà
  pour `WidgetNotificationStore`), et `SofascoreNotificationListenerService.onNotificationPosted`
  pousse une entrée SOFASCORE_MATCH à chaque notif Sofascore, en réutilisant
  EXACTEMENT le même parsing (`toMatchResult`) que pour la vue Sport — c'est
  ce qui permet de garder la présentation Sofascore identique sans dupliquer
  sa logique dans le mirroring générique, et sans que tu aies besoin
  d'activer le mirroring générique pour Sofascore.

- **`WidgetViewModePrefs` passe de 2 à 3 états** (`LATEST`/`SPORT`/`ALL_NOTIFS`),
  avec migration automatique de l'ancien booléen `sofascore_active` au
  premier lancement après mise à jour (tu ne perds pas la vue affichée
  au moment du passage à cette version).

- **5e emplacement Sport** : copie exacte de `widget_match_1..4`
  (`widget_match_5`), `SofascoreWidgetStore.MAX_SLOTS` passé à 5 — sinon
  aucun changement de logique de tri/priorité (toujours "en cours d'abord,
  terminé depuis +5min après").

- **Vignettes "Toutes notifs"** : même largeur (52dp) que les vignettes
  Sport pour l'harmonisation demandée. Chaque emplacement fixe
  (`widget_notif_1..5`) déclare STATIQUEMENT les deux présentations
  possibles (générique : photo 32dp + médaillon d'icône + titre 2 lignes ;
  Sofascore : copie exacte de `widget_match_N`) et bascule leur visibilité
  selon le type de l'entrée — même philosophie "emplacements fixes" que le
  reste de ce fichier (pas de `RemoteViews.addView` dynamique). La zone
  image fait toujours 32dp de haut quel que soit le style affiché, pour que
  la ligne de texte reste alignée entre vignettes voisines de styles
  différents.
  - Avec image : photo pleine + médaillon icône appli en bas-à-droite
    (fond réutilisé `widget_dismiss_background`, cercle translucide).
  - Sans image : icône appli seule à la place de la photo, pas de médaillon
    (aurait été la même icône deux fois).
  - Titre : `maxLines=2` + `ellipsize=end` sur largeur FIXE (52dp) — un
    titre long est tronqué mais ne peut mécaniquement jamais déborder sur
    la vignette voisine, quelle que soit sa longueur.

- **Tap = ouvrir seulement, jamais supprimer** : comme la vue Sport
  actuelle, chaque vignette "Toutes notifs" utilise le `contentIntent`
  propre à cette notif (ou le lancement de l'appli en repli) — aucun
  bouton de suppression n'existe sur cette vue, donc taper dessus ne peut
  pas effacer la notif.

- **Icône de la colonne de gauche en vue "Toutes notifs"** : pas d'appli
  source unique pour un flux fusionné, donc repli sur l'icône de Now Bar
  Mirror lui-même (`ic_stat_mirror`), comme pour l'état "aucune notif" de
  la vue habituelle.

## Largeur du widget
Avec 5 vignettes de 52dp (Sport ET Toutes notifs) plus les deux colonnes de
boutons, le widget est nettement plus large qu'avant (~380dp de contenu).
C'est inhérent à afficher 5 éléments lisibles côte à côte — dis-moi si les
vignettes paraissent trop serrées une fois affichées en vrai sur LockStar,
auquel cas il suffit d'élargir le widget sur l'écran de verrouillage.

## À vérifier sur l'appareil
- Rendu des vignettes 52dp × 32dp (photo) en générique — un premier réglage
  à l'œil, comme pour les vignettes Sport à l'origine.
- Le médaillon d'icône (14dp) est peut-être un peu petit/trop discret selon
  ton écran — simple valeur à ajuster dans `widget_now_bar.xml`
  (`widget_notif_N_badge`) si besoin.
- Confirmer que le bouton droit reste bien aligné verticalement une fois
  affiché (colonne symétrique à la gauche, mais sans icône au-dessus).

## Livraison
Zip (`widget-toutes-notifs-view.zip`) reproduisant l'arborescence des
dossiers depuis la racine du dépôt, à merger dans
`Now-bar-notification-mirror`. Compilation réelle via ton GitHub Actions
existant (`.github/workflows/build.yml`) — aucun SDK Android disponible ici
pour compiler/vérifier localement ; les fichiers Kotlin/XML ont été relus et
vérifiés bien formés (XML) et équilibrés (accolades/parenthèses), et tous
les `R.id`/`R.drawable`/`R.string` référencés existent bien — mais pas de
compilation Gradle réelle de mon côté.
