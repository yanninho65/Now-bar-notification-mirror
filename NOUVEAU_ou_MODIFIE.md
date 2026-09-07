# Widget Now Bar — fichiers à intégrer

À copier dans le repo `Now-bar-notification-mirror`, en respectant l'arborescence de ce zip.

## Fichiers existants à REMPLACER (contenu entier)
- app/src/main/java/com/yann/nowbarmirror/MirrorNotificationListener.kt
- app/src/main/AndroidManifest.xml
- app/src/main/res/values/strings.xml

## Nouveaux fichiers à CRÉER
- app/src/main/java/com/yann/nowbarmirror/widget/WidgetNotificationStore.kt
- app/src/main/java/com/yann/nowbarmirror/widget/NowBarWidgetProvider.kt
- app/src/main/res/layout/widget_now_bar.xml
- app/src/main/res/xml/now_bar_widget_info.xml
- app/src/main/res/drawable/ic_widget_dismiss.xml
- app/src/main/res/drawable/widget_dismiss_background.xml

## Test
1. Build via GitHub Actions comme d'habitude.
2. Installer l'APK, vérifier que l'accès aux notifications est toujours actif.
3. Dans LockStar : ajouter un widget → "Now Bar Mirror" → format 4x1.
4. Envoyer une notif de test (WhatsApp, Messages...) et vérifier : contenu affiché, tap = ouvre la notif, × = supprime la notif source.
