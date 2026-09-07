# Widget Now Bar — v2 (correctif crash)

À copier dans le repo `Now-bar-notification-mirror`, en respectant l'arborescence de ce zip.

## Ce qui a changé depuis le premier envoi
`MirrorNotificationListener.kt` et `NowBarWidgetProvider.kt` isolent maintenant tout le code
du widget dans des `try/catch` : une erreur dans le widget ne peut plus faire planter le
service d'écoute des notifications (et donc plus casser le mirroring existant).

Un Toast TEMPORAIRE a été ajouté : si le widget replante encore, un message s'affichera à
l'écran avec le nom exact de l'erreur (ex. "Widget: NullPointerException: ..."). Note-le et
envoie-le-moi pour qu'on corrige précisément. Il sera à retirer une fois que tout est stable.

## Fichiers existants à REMPLACER (contenu entier)
- app/src/main/java/com/yann/nowbarmirror/MirrorNotificationListener.kt
- app/src/main/AndroidManifest.xml
- app/src/main/res/values/strings.xml

## Nouveaux fichiers à CRÉER (ou remplacer s'ils existent déjà depuis le premier envoi)
- app/src/main/java/com/yann/nowbarmirror/widget/WidgetNotificationStore.kt
- app/src/main/java/com/yann/nowbarmirror/widget/NowBarWidgetProvider.kt
- app/src/main/res/layout/widget_now_bar.xml
- app/src/main/res/xml/now_bar_widget_info.xml
- app/src/main/res/drawable/ic_widget_dismiss.xml
- app/src/main/res/drawable/widget_dismiss_background.xml

## Test
1. Build via GitHub Actions.
2. Réinstaller l'APK (si tu avais désinstallé/réinstallé, il faudra sûrement redonner l'accès
   aux notifications dans MainActivity).
3. Renvoyer une notif de test et vérifier que le mirroring "classique" refonctionne.
4. Si un Toast d'erreur apparaît, note le message exact.
