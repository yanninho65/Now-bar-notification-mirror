# Widget Now Bar — v3 (ouvre le bon message/article)

Seuls ces 3 fichiers ont changé, à REMPLACER en entier :
- app/src/main/java/com/yann/nowbarmirror/MirrorNotificationListener.kt
- app/src/main/java/com/yann/nowbarmirror/widget/WidgetNotificationStore.kt
- app/src/main/java/com/yann/nowbarmirror/widget/NowBarWidgetProvider.kt

## Ce qui a changé
Le widget reçoit maintenant le PendingIntent d'ouverture au moment exact où la notif arrive,
au lieu d'essayer de le sauvegarder puis de le reconstruire plus tard (technique qui ne
fonctionne pas de façon fiable pour un PendingIntent). Le tap sur le widget doit maintenant
ouvrir directement la conversation/l'article, comme le fait déjà le mirroir "Now Bar" système.

Si la notif semblait disparaître au tap, c'était probablement un effet de bord d'ouvrir
l'appli en générique plutôt que le contenu précis — à vérifier une fois ce correctif en place.

Le Toast temporaire de diagnostic est toujours là si jamais une erreur survient.
