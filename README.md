# Now Bar Mirror

Android app for a Samsung Galaxy phone + Galaxy Watch pair. It has two independent jobs:

1. **Notification mirroring** — mirrors selected apps' notifications into persistent notification(s) intended to be eligible for Samsung One UI / Android Live Update surfaces, plus a lock-screen widget showing the latest one.
2. **Live sport scores** — reads Sofascore's own notifications and drives a Wear OS watch-face complication with the live score, optionally refined against TheSportsDB or the Live Tennis API.

The two live on separate tabs of the same screen (**Accueil** / **Sport**) and share only what genuinely overlaps between them (notification listening infrastructure, image extraction) — see [Architecture](#architecture).

It's a two-module Gradle project: the phone app (`app/`) and a Wear OS watch app (`wear/`) for the score complication.

## Working on this repo

This is a public repo — the fastest way to get the CURRENT source is a plain clone, rather than going through any secondary/synced copy of it (a knowledge-base sync can lag behind a recent push, or only surface a subset of files):

```
git clone https://github.com/yanninho65/Now-bar-notification-mirror.git
```

(`git pull` if a clone already exists). No authentication needed for read access; default branch is `main`. There's no local Android SDK available in most working sessions on this project — see [Build](#build) for how a change actually gets compiled and installed (GitHub Actions, then manual install on device — no local `gradle build`/emulator round-trip).

Every change so far has been delivered as a set of updated files (reproducing the repo's folder structure) to merge in by hand, plus a short explanation of what changed and why — folded directly into this README as it's written, not kept in separate per-change notes files (an earlier `Claude/` folder of one-file-per-change delivery notes was retired for this reason — its content lives in the relevant sections below now).

## Accueil tab — notification mirroring

- Listens to notifications using `NotificationListenerService`.
- Ignores its own notifications, ongoing notifications, and group-summary bundles (e.g. WhatsApp's "X new messages").
- Only mirrors apps you've explicitly selected — nothing is mirrored by default.
- Per app, you choose one of two modes:
  - **Dernière notif (LATEST)** — all apps in this mode share a single mirror slot; whichever posts most recently occupies it, replacing whatever was shown before.
  - **Toutes (ALL)** — every distinct notification from this app gets its own persistent mirror, shown at the same time.

  Example: Signal and WhatsApp set to "Toutes", Le Monde and Mediapart set to "Dernière notif". Two WhatsApp messages + two Signal messages + one Mediapart notification all show at once (five mirrors). A Le Monde notification arriving next replaces the Mediapart one, since both share the LATEST slot.
- Per app, an independent "Titre ↔ texte" checkbox swaps which field is treated as the title — a Now-Bar-only concern: it only affects the actual system-notification mirror's own pill (useful when an app's "text" field is actually the more relevant short summary for that compact pill). **Fixed 18/09/2026** (Yann: "l'inversion ne doit servir que pour la now bar") — this used to leak into the lock-screen widget too, both its Dernière notif view and its "Toutes notifs" tiles, which showed the swapped fields even though the widget is a separate surface from the actual Now Bar. Both now always show the notification's ORIGINAL title/text regardless of this setting; only the real Now Bar pill (and the notification-mirror popup) still reflects the swap.
- Copies title and text, the notification's large icon/contact image (or the source app icon as a fallback), and up to three standard action buttons, reusing their original `PendingIntent`.
- If an original notification is removed, its mirror is removed. If a mirror is removed by the user (swipe, or "clear all"), the original is cancelled the same way. Removing our own mirror to replace its content (LATEST-mode swap, ALL-mode update) does **not** trigger this — only a genuine user dismissal does.
- "Revenir à la précédente après suppression" (on by default) — when the shared "Dernière notif" slot is dismissed while other LATEST-mode originals are still active elsewhere, the slot is re-posted with the next most recent survivor instead of just being cleared.
- On listener connect/reconnect (app restart, permission just granted), any already-active eligible notification from a selected app is mirrored immediately rather than waiting for the next one to be posted.
- A "Service actif" switch lets you pause mirroring entirely without uninstalling or revoking notification access; existing delete-sync keeps working for mirrors already showing while paused.
- Settings (per-app modes, invert flags, service on/off, widget actions, fallback) can be exported to / imported from a JSON file via the system file picker.
- On Android 16+, requests a promoted ongoing notification so the system can consider it for Live Update surfaces, and includes the Samsung ongoing-activity application metadata used by current One UI implementations.

### Choosing which apps to mirror

Open **Now Bar Mirror** → **Applications à mirrorer** (Accueil tab). Each installed app with a launcher icon is listed with a segmented mode selector (Aucun / Dernière / Toutes) and the invert-title/text checkbox. The same screen has the service on/off switch, the "revenir à la précédente" fallback switch, the widget-actions switch, and the export/import buttons.

Sofascore itself (`com.sofascore.results`) is deliberately left out of this list (**fixed 18/09/2026**) — it's fully owned by the Sport tab's own listener, which already feeds both the Sport widget view and its own match-tile entries in "Toutes notifs"; selecting it here too used to make this listener race that dedicated one for the same "Toutes notifs" tile (see the "Toutes notifs" bullet below).

### Lock-screen widget

A 4x1, background-less widget (`NowBarWidgetProvider`) has THREE views (`WidgetViewModePrefs` remembers which one is currently showing, and which of Sport/Toutes notifs was shown last), reached with two small buttons:

- **Left button** (rotating arrows, under the app icon) — toggles between **Dernière notif** and whichever of **Sport**/**Toutes notifs** you last had open.
- **Right button** (rotating arrows, at the far right edge of the widget, added alongside the "Toutes notifs" view below) — toggles directly between **Sport** and **Toutes notifs** only; hidden while on **Dernière notif**, since the left button already covers getting to/from that one.

The three views:

- **Dernière notif** (default) — mirrors whichever selected app posted most recently, across ALL- and LATEST-mode apps together, unlike the system mirror slots which keep them separate. Shows the source app's icon, title/text (always the notification's ORIGINAL fields — see the "Titre ↔ texte" fix above, 18/09/2026), the notification's image if any, and a dismiss button; tapping it opens the original notification, or launches the source app if the live `PendingIntent` isn't available anymore (e.g. after a process restart).
- **Sport** — up to 5 Sofascore matches side by side, one per currently active Sofascore notification (`SofascoreWidgetStore`): the combined team image (30dp tall, grown from 20dp on 17/09/2026 at Yann's request — "grossir les logos"), score (bracket around whichever side just scored, same convention as the Sofascore notification itself), and period/status — same top-to-bottom order as the watch's `SMALL_IMAGE` complication (see [Watch complication](#watch-complication-wear)), reworked to phone-sized proportions by `SofascoreMatchPresentation`. Live matches (and anything finished less than 5 minutes ago) sort first, most-recently-notified within each group; anything finished longer ago than that sorts after — recomputed on every render, not just when a new Sofascore notification arrives. Score/period always come straight from the Sofascore notification text, never the API override described below (that system only follows a single match, not all of them at once). Tapping a match opens it in Sofascore, via that notification's own `PendingIntent` exactly like tapping the notification itself, **without** cancelling the source notification — this view deliberately has no dismiss button. The 5 tiles share the row's width evenly rather than each having a fixed size (see the width-responsive-tiles fix below).
- **Toutes notifs** — up to 5 recently received notifications, same tile layout as Sport, fed from two sources into one shared history (`WidgetAllNotificationsStore`): every notification the Accueil tab mirrors (any app, ALL or LATEST mode, always their ORIGINAL title — see the "Titre ↔ texte" fix above), and every Sofascore notification (reusing the exact same parsing as the Sport view, so a Sofascore entry here needs no separate mirroring setup for `com.sofascore.results` — and, since 18/09/2026, no LONGER accepts one either, see [Choosing which apps to mirror](#choosing-which-apps-to-mirror)). Per tile:
  - **Self-healing against the actual notification center (fixed 18/09/2026)** — Yann: "le widget doit afficher toutes les notifications dans le centre de notification [...] aujourd'hui je dois forcer l'arrêt pour que l'application enregistre les 5 derniers puis le nombre se réduit [...] ça devrait toujours être plein"; separately, after marking a Gmail notification read/deleting it/opening it from within Gmail itself (same for a deleted Calendar event), its tile kept showing here even though it was long gone from the shade, and a phone reboot once left 2 stale tiles (one Gmail) despite no notification actually being active. Root cause: this history was purely event-driven (`onNotificationPosted`/`onNotificationRemoved`), so a removal missed while the app's process was dead (background kill, force-stop, reboot) left a tile behind forever, and dismissing one of the 5 shown never pulled in another already-active-but-untracked notification to replace it. Both listeners now reconcile this history against a fresh `getActiveNotifications()` snapshot (`WidgetAllNotificationsStore.pruneAgainstActive` drops anything no longer actually posted, then eligible active notifications are re-pushed to fill any freed slot) on every reconnect AND right after every removal — not just when the app gets force-stopped — so the view stays a live reflection of the notification center instead of a slowly-draining log.
  - **Sofascore no longer races itself here (fixed 18/09/2026)** — Yann: "j'ai encore des applis Sofascore qui s'affichent bien en vue sport mais s'affichent comme les autres notifs en vue toutes notifs. C'est aléatoire et parfois j'ai même deux icônes pour un même match." Cause: if Sofascore's package was ALSO selected in the Accueil app list (a plain "Dernière notif"/"Toutes" app), `MirrorNotificationListener` pushed its OWN generic (image+title) tile for the exact same notification the Sport tab's listener was independently pushing as a match tile into this same shared history — whichever push landed last decided the tile's presentation, and when their identities didn't line up exactly, both ended up coexisting as two tiles for one match. `MirrorNotificationListener` now ignores Sofascore's package unconditionally (regardless of any stored app-selection setting), and it's been removed from the Accueil app-selection list entirely — a Sofascore entry here can now only ever come from the Sport tab's own listener, so it always renders as a match tile, never a generic one, and never twice.
  - A **Sofascore entry** renders EXACTLY like a Sport-view tile — combined image, score, period, and (since 17/09/2026) the SAME 30dp logo height too. A same-day correction (Yann: "si je demande une modification sur l'apparence d'une notif Sofascore dans la vue sport, faire la même sur les notifs Sofascore dans la vue toutes notifs") reversed an initial version of the logo-size increase above that only touched the Sport view — this is now a standing rule for this project: any future appearance change to a Sofascore tile in one of these two views should be mirrored in the other.
  - **Width-responsive tiles (fixed 17/09/2026)** — Yann: "L'augmentation de la taille des logos a fait que la cinquième notif est trop sur le côté. Réduire espace pour que ça passe avec 5. Rendre les espaces dynamiques si besoin en fonction de la taille du widget." Both this view's and the Sport view's 5 tiles used to each have a hard-coded 62dp width plus a fixed 6dp gap, sized for an assumed widget width; once the logos grew taller (and, with them, a bit wider) nothing shrank to compensate, so a lock-widget host giving the widget less room than that let the 5th tile spill past the edge instead of just rendering smaller. Both rows are now sized as 5 even shares of whatever width the widget instance actually has, with the gaps between tiles scaling right along with them — so all 5 always fit, on any host width, and there's no separate "widen the widget" workaround needed anymore.
  - Any other notification renders as its own image on top (or, if it has none, the source app's icon fills that slot directly instead) with a small badge of the source app's icon in the image's corner — only when there IS an image, since otherwise the app icon would just be shown twice — and the title below, truncated to 2 lines rather than ever spilling into the neighbouring tile.
  - A tile disappears as soon as its notification is dismissed anywhere (same rule as the other two views — this is a capped-at-5 "recently received" view, not a permanent log), and tapping it only opens the notification, never dismisses it.
  - **Identity refinement, same day (Yann, having sent himself 5 test WhatsApp messages in one conversation: "ça prend les 5 cases alors que ça ne devrait en prendre qu'une", même souci "pour chaque but" d'un match Sofascore)** — an entry is identified by (notification key, post time), **except** for a Sofascore match or a messaging-app conversation, which always update their ONE existing tile in place instead, by key alone, no matter how many times they repost. Both are, structurally, a single Android notification reused and updated in place (Sofascore: one notification per match, growing score history — confirmed on-device; a conversation: `MessagingStyle`, growing message history) — so 5 self-sent WhatsApp messages, or 5 goals in the same match, now update ONE tile instead of spawning 5. A "conversation" is detected via `shortcutId`, `CATEGORY_MESSAGE`, or the `MessagingStyle` marker (`MirrorNotificationListener.isConversationNotification()`). Everything else keeps the (key, post time) pair from the original 17/09/2026 fix (Yann: "si plusieurs notifications ont été envoyées parmi les applications marquées comme dernière notif, seule la dernière notif apparaît [...] je veux que toutes les notifs puissent apparaître") — an app like Le Monde reuses its notification id too, but each posting REPLACES the previous one with an unrelated article rather than accumulating it, so five different articles still get up to five separate tiles here, exactly like a "Toutes"-mode app's five notifications already did. This replace-by-id behavior stays exactly as before for the actual Now Bar mirror's shared LATEST slot above, where it's the intended, by-design behavior — this fix only concerns this history view.

The left toggle only appears in the Dernière notif view once there's at least one Sport match or Toutes-notifs entry to switch to; it always stays visible on Sport/Toutes notifs so you can get back, even if what's shown drops to zero while you're on it (a small "Aucun match"/"Aucune notification" takes the tiles' place in that case). The right toggle is visible whenever you're on Sport or Toutes notifs, regardless of whether the other one currently has anything to show.

It's meant to sit directly on the lock screen over the wallpaper (no card background), placed there through a third-party lock-widget host such as Samsung's LockStar — it can also be added like any normal widget from the home-screen widget picker. Sport and Toutes notifs both scale their 5 tiles to whatever width the widget instance has (see the width-responsive-tiles fix above), so they always fit — a narrower placement just draws smaller tiles rather than clipping the 5th one.

"Actions dans le widget" (off by default) adds up to three of the notification's own text action buttons (e.g. "Répondre", "Marquer comme lu") in a second row under the title/text, in the Dernière notif view only — only while this app's process has stayed alive since that exact notification arrived, since a `PendingIntent` can't be reconstructed from storage after a process restart (same limitation as tap-to-open in any of the three views).

## Sport tab — live scores on the watch

Sofascore is the source of truth for match identity: team names and the notification's own image always come from Sofascore, never from the API lookups below. The tab lists every Sofascore match currently in your notification center ("Dernière notification" always first), and whichever one is active drives the watch complication.

- **Choosing which match drives the watch**: tap a row to pick it (`SofascorePrefs`) — either "Dernière notification" (auto: whichever Sofascore notification updated most recently) or a specific match. If the chosen match's notification disappears (game over, notification cleared), it automatically falls back to "Dernière notification" rather than showing nothing.
- **Score/status parsed directly from the Sofascore notification text** (`SofascoreNotificationParser`), no network call needed for this part. It recognizes: football, handball and rugby (half-time / full-time — rugby's scoring lines added 17/09/2026 from a real example Yann provided, "Score : H - A Team", same shape as football's "But : H - A" but without a minute), basketball (quarters), tennis, and set-tally sports like table tennis and volleyball — detected from the notification's own wording, since Sofascore doesn't flag the sport explicitly. "Match terminé : H - A" is always trusted first when present, since Sofascore doesn't always post period-by-period lines in strict chronological order at full time.
- **Optional API override, per match** (tap "API" on a row): search TheSportsDB (multi-sport) or the Live Tennis API (tennis-only, needs your own API key, entered in-app and stored locally — never in source) for a specific team/player/league, then pick whether to take the **score** and/or the **period/minute** from that API instead of Sofascore's own text — independently checkable, so you can take just one or both. `ApiOverrideFollowService` polls the chosen API in the background (even app closed) while an override is active, and `ApiOverrideCache` holds its latest result. Team names and the image are unaffected either way — always Sofascore's.
- Overrides are cleared automatically when their Sofascore notification disappears (`SofascoreNotificationListenerService.onNotificationRemoved`).
- Uses its own `NotificationListenerService` (`sport.SofascoreNotificationListenerService`), entirely separate from the Accueil tab's — see [Architecture](#architecture) for why, and [Two separate notification-access toggles](#two-separate-notification-access-toggles) for what that means day to day.

### Watch complication (`wear/`)

The `wear/` Gradle module builds a small Wear OS app whose only job is the "Score en direct" complication (`ScoreComplicationService`, `LONG_TEXT` and/or `SMALL_IMAGE`). The phone (`WatchSync`) pushes the active match to the watch (`MatchListenerService`) over the Wear Data Layer API whenever it changes; `MatchClock` formats the status/period text per sport, `ComplicationImageComposer` composes the notification image for `SMALL_IMAGE`. Tapping the complication opens Sofascore on the watch, if installed there.

Setup on the watch face: assign "Score en direct" to a LONG_TEXT slot and/or a round SMALL_IMAGE slot — no further configuration needed once assigned.

**Signing constraint**: the Wear Data Layer API requires the phone and watch apps to share both the same `applicationId` (`com.yann.nowbarmirror`) and the same signing key — otherwise the phone-side send succeeds silently and nothing ever arrives on the watch. `wear/build.gradle.kts` is set up to match `app/build.gradle.kts` on both counts, reusing the same `DEBUG_KEYSTORE_B64` secret.

## Architecture

The two tabs are deliberately kept separate rather than folded into one system, because they solve different problems (one mirrors arbitrary third-party notifications verbatim, the other parses one specific app's notifications into structured match data) — but they share the two things that genuinely overlap:

- **Notification image extraction** (`NotificationImageExtractor`, top-level package) — one shared helper, used by both `MirrorNotificationListener` and `sport.SofascoreNotificationListenerService`. Tries, in order: MessagingStyle contact photo → `EXTRA_PICTURE` → `getLargeIcon()` → `EXTRA_LARGE_ICON`.
- **"Catch up on already-active notifications" on listener connect** — both listeners mirror/refresh from whatever is already in the notification center the moment they (re)connect, not just notifications posted afterward. Extended 17/09/2026 (Yann: "le comportement doit bien être de lire toutes les notifs dans le centre de notif et non plus uniquement celles reçues après installation de l'appli ou mise à jour") to cover two gaps this same rule had missed: (1) on the Accueil side, `MirrorNotificationListener` used to only push the single most-recently-promoted "Dernière notif" notification into the "Toutes notifs" history at reconnect — every OTHER currently-active LATEST-mode notification is now pushed there too (`pushAllNotifsHistoryOnly`); (2) on the Sport side, `SofascoreNotificationListenerService` used to only feed "Toutes notifs" from `onNotificationPosted`, i.e. notifications posted AFTER it (re)connected — a Sofascore match notification already active at connect time (first install, permission just granted, process restart) is now also pushed there immediately (`bootstrapAllNotificationsHistory`), rather than waiting for its next score update.
- **Forcing a listener reconnect right after an app update** (`PackageUpdateReceiver`, same day) — the catch-up above only ever runs from `onListenerConnected()`, and Android does not reliably call that again just because the APK was updated in place (unlike a fresh install or freshly-granted notification access); a listener's process can otherwise keep running against the old code without ever re-entering `onListenerConnected()`, so the catch-up silently never fires and "Toutes notifs" is stuck showing only what arrived after the update — exactly what Yann reported testing this same day ("je ne vois que celles apparues après la mise à jour"). `PackageUpdateReceiver` listens for `ACTION_MY_PACKAGE_REPLACED` (sent only to this app, only right after it updates) and briefly disables-then-re-enables both `NotificationListenerService` components, which forces Android to unbind and rebind them — re-running `onListenerConnected()`, and with it the already-correct catch-up, without needing the user to toggle notification access or reboot.
- **The widget's Sport and Toutes-notifs views** (`widget.SofascoreWidgetStore`, `widget.WidgetAllNotificationsStore`, `widget.SofascoreMatchPresentation`) — the one deliberate exception to the package split below: `sport.SofascoreNotificationListenerService` pushes match data straight into `widget.NowBarWidgetProvider`, the same way `MirrorNotificationListener` already does for the Dernière notif view — and, since the Toutes-notifs history is one store fed by BOTH listeners, `SofascoreNotificationListenerService` pushes into it too (in its own match-tile shape, tagged so `NowBarWidgetProvider` renders it like a Sport tile rather than the generic image+title one). `SofascoreMatchPresentation` duplicates (rather than shares — the `:wear` module isn't reachable from `:app`) the score/period formatting logic in `wear/MatchScore.kt` and `wear/MatchClock.kt`.

Everything else is intentionally independent: separate `NotificationListenerService`s, separate preference stores, separate packages (`com.yann.nowbarmirror` for the Accueil tab and widget, `com.yann.nowbarmirror.sport` for the Sport tab).

### Two separate notification-access toggles

Because they're two independent `NotificationListenerService`s, Android treats them as two separate switches in **Paramètres > Notifications > Accès aux notifications** — "Now Bar Mirror notification listener" (Accueil) and "Now Bar Mirror — Sport (Sofascore)" (Sport tab). Both need to be granted independently; each tab's screen has its own button that opens that same system screen.

## Style

The app follows Samsung's One UI look: rounded cards grouping related settings, a bold large-title header on the main screen (with an Accueil/Sport tab switcher underneath it), and a light/dark palette that follows the system theme. In the Accueil app list, each app's mode is a segmented Aucun/Dernière/Toutes control — the active mode is filled in blue, the others stay outlined — and the whole row is tinted when that app is actively mirrored, so it's obvious at a glance which apps are on. The launcher icon is a stylized rendition of the Now Bar itself: a dark capsule holding an avatar dot and two content lines, on a blue background, with a themed-icon layer for Android 13+ / One UI icon theming.

## Project layout

```
app/src/main/java/com/yann/nowbarmirror/
├── MainActivity.kt                    entry screen: Accueil/Sport tabs + permissions + link to app selection
├── MirrorNotificationListener.kt      the Accueil tab's NotificationListenerService
├── NotificationImageExtractor.kt      image extraction shared between both listeners
├── PackageUpdateReceiver.kt           forces both listeners to rebind right after an app update, see Architecture
├── settings/                          Accueil tab preferences and screen
│   ├── MirrorMode.kt                  NONE / LATEST / ALL
│   ├── AppMirrorPrefs.kt              per-package mode + invert-title/text storage
│   ├── ServicePrefs.kt                service on/off flag
│   ├── LatestModePrefs.kt             "revenir à la précédente" fallback flag
│   ├── WidgetActionsPrefs.kt          widget action-buttons on/off flag
│   ├── SettingsBackup.kt              JSON export/import of the above
│   ├── AppSelectionActivity.kt        the settings screen
│   └── AppSelectionAdapter.kt         RecyclerView adapter for the app list
├── sport/                             Sport tab — Sofascore + watch complication
│   ├── SofascoreNotificationListenerService.kt  its own NotificationListenerService
│   ├── SofascoreNotificationParser.kt  Sofascore notification text -> MatchResult
│   ├── SofascorePrefs.kt              LATEST / CHOSEN fallback choice
│   ├── SofascoreApiOverridePrefs.kt   per-match score/period override from TheSportsDB/Live Tennis
│   ├── ApiOverrideCache.kt            latest polled result per override
│   ├── ApiOverrideFollowService.kt    background polling for the active override
│   ├── SportsDbApi.kt / LiveTennisApi.kt
│   ├── TennisApiKeyPrefs.kt           locally-stored Live Tennis API key
│   ├── WatchSync.kt                   sends match data to the watch (Wear Data Layer API)
│   ├── Models.kt                      TeamResult / PlayerResult / LeagueResult / MatchResult
│   └── SofascoreHomeAdapter.kt / SimpleListAdapter.kt / MatchesAdapter.kt
└── widget/
    ├── NowBarWidgetProvider.kt         the 4x1 lock-screen widget — all three views (Dernière notif + Sport + Toutes notifs)
    ├── WidgetNotificationStore.kt      persists the widget's current notification (Dernière notif view)
    ├── SofascoreWidgetStore.kt         persists up to 5 Sofascore matches (Sport view)
    ├── WidgetAllNotificationsStore.kt  persists up to 5 recently received notifications, generic + Sofascore (Toutes notifs view)
    ├── SofascoreMatchPresentation.kt   phone-side score/period formatting, shared by the Sport view and Sofascore entries in Toutes notifs
    └── WidgetViewModePrefs.kt          which of the three widget views is currently showing, + which of Sport/Toutes notifs was shown last

wear/src/main/kotlin/com/yann/nowbarmirror/wear/
├── ScoreComplicationService.kt        LONG_TEXT / SMALL_IMAGE complication data source
├── MatchListenerService.kt            receives match data from the phone
├── MatchClock.kt                      formats status/period per sport
├── MatchScore.kt                      score text, incl. the "who just scored" bracket
└── ComplicationImageComposer.kt       composes the SMALL_IMAGE bitmap
```

## Limitations

- The Android notification listener API can observe and cancel notifications, and notification actions expose their `PendingIntent`s. Samsung ultimately controls whether and how an ongoing notification appears in the Now Bar. This project therefore deliberately uses the public Android notification APIs plus the Samsung ongoing-activity hint; it does not attempt to depend on undocumented Samsung framework internals.
- Inline-reply `RemoteInput` actions are not yet reconstructed, either in the system-notification mirror or in the widget.
- The widget's tap-to-open — in all three views alike — and the Dernière notif view's action buttons, and ALL-mode mirror tracking, rely on a live `PendingIntent`/in-memory state held since the notification was mirrored — after the app's process is killed and restarted these reset (tap-to-open falls back to opening the source app, or Sofascore itself for a match tile) until the next notification/event arrives; a Toutes-notifs tile older than that keeps working as long as it stayed in the capped-at-5 history the whole time, but a fresh process restart still resets its live tap target like any other.
- The widget's Sport view never shows the API override (that system only follows one match at a time, see the Sport tab above) and, unlike the watch, doesn't show a live tennis set's game score (just "En direct") or an upcoming match's kickoff time — kept simple to fit 5 small tiles side by side (`SofascoreMatchPresentation`). Same for a Sofascore entry inside the Toutes-notifs view, since it reuses this exact same formatting.
- The Toutes-notifs view is a capped-at-5 "recently received" list, not a log: an entry is dropped as soon as its source notification is dismissed anywhere (shade, source app, "effacer tout"), not kept around for reference afterward. Since 18/09/2026 it also actively reconciles against a fresh `getActiveNotifications()` snapshot (on listener reconnect and after every removal, see the "Toutes notifs" bullet above) rather than relying purely on events, so a removal missed while the app's process was dead can no longer leave a stale tile behind indefinitely, and a freed slot gets refilled from whatever else is already active instead of just shrinking the list.
- Since the app targets API 30+, it declares a `<queries>` entry for the `MAIN`/`LAUNCHER` intent (for the app-selection screen) plus one for `com.sofascore.results` (for the Sport tab) in the manifest — without the first, Android's package-visibility restrictions would hide every user-installed app from the app-selection screen, leaving only system apps visible.
- The Sport tab depends entirely on how Sofascore words its own notifications — a wording change on their end could break parsing until updated here. "Match commencé" (kickoff) briefly shows 0-0 with the football-style status on every sport, including basketball/tennis/set-sports, until their first period actually ends and the sport-specific vocabulary can kick in — a few minutes of cosmetic imprecision, no functional impact.
- The watch complication only updates while the phone is reachable over the Wear Data Layer API (both devices on, Bluetooth/Wi-Fi connected as usual for a paired watch).

## Build

The APK is built via the GitHub Actions workflow in this repo (`.github/workflows/build.yml`) rather than locally in Android Studio, since the day-to-day workflow here is phone-only:

1. Push to `main`, or trigger it manually from the **Actions** tab → "Build debug APK" → **Run workflow**.
2. Once the run finishes, open it and download the `NowBarMirror-debug-apk` artifact (phone) and, if the watch app changed, `NowBarMirror-wear-debug-apk` too.
3. Unzip the phone artifact and install `app-debug.apk`.
4. Open **Now Bar Mirror**: on the **Accueil** tab, enable **Notification access**, allow the app's own notifications (Android 13+), and pick which apps to mirror. On the **Sport** tab, grant notification access separately for Sofascore — it's a distinct toggle in system settings (see [Two separate notification-access toggles](#two-separate-notification-access-toggles)).
5. To place the widget: add it from the home-screen widget picker, or through a lock-widget host like LockStar for the lock screen itself.
6. For the watch app, unzip `wear-debug.apk` and install it on the watch (e.g. via GeminiMan WearOS Manager), then assign the "Score en direct" complication on the watch face.

The debug signing key is generated once via the separate "Generate debug keystore" workflow and stored as the `DEBUG_KEYSTORE_B64` repo secret — no need to re-run it unless the secret is lost.

To build locally instead (Android Studio, JDK 17): open the folder, let Gradle sync, then `app` → `assembleDebug` and/or `wear` → `assembleDebug`.

## Recommended first test

**Accueil tab** — use Samsung Messages, WhatsApp, Signal or another app that produces a normal alert notification with a title, text, image and action buttons:

1. Set it to "Toutes" (or "Dernière notif") in the app-selection screen.
2. Confirm the mirror notification appears — and, if the widget is placed, that it updates too.
3. Confirm the source notification remains visible.
4. Send a second, different notification and confirm the previous behavior (replace for LATEST, new mirror for ALL) matches expectations.
5. Removing the source removes the mirror.
6. Removing the mirror removes the source.
7. Tapping an action performs the source app's action — in the system mirror, and in the widget too if "Actions dans le widget" is on.
8. Toggle "Service actif" off, confirm new notifications from selected apps stop mirroring, then toggle it back on.
9. Toggle "Titre ↔ texte" for one app and confirm the swap on its next notification.
10. Export settings, change a mode, import the file back, and confirm the mode is restored.

**Sport tab** — with a live Sofascore match notification active:

11. Confirm it appears in the Sport tab's list and drives the watch complication within a few seconds.
12. Pick a different match from the list and confirm the complication switches to it.
13. Link a match to a TheSportsDB/Live Tennis result via the "API" button and confirm the chosen field(s) (score and/or period) switch to that source.

**Widget — Sport view** — with at least one Sofascore match active:

14. Confirm the left rotating-arrows toggle appears on the widget and switches it to up to 5 match tiles.
15. Confirm each tile shows the combined team image, score (with a bracket if a side just scored), and period/status.
16. Tap a tile: confirm it opens Sofascore on that exact match, and that the tile's source notification is still present afterward (not dismissed).
17. Tap the left toggle again and confirm it switches back to the Dernière notif view.

**Widget — Toutes notifs view** — with a mix of recent mirrored notifications and at least one Sofascore match active:

18. From the Sport view, confirm the right toggle switches directly to up to 5 recently received notifications, without going through Dernière notif.
19. Confirm a non-Sofascore tile shows its notification's image (or the app icon in its place if there's none) with the app icon badge in the corner only when there IS an image, and its title truncated to 2 lines.
20. Confirm a Sofascore entry in this view looks identical to a Sport-view tile (image/score/period), not the generic image+title style.
21. Dismiss one of the shown notifications (from the shade or the source app) and confirm its tile disappears from Toutes notifs on the next widget refresh.
22. Tap a tile: confirm it opens the notification and that it's still present afterward (not dismissed).
23. Tap the right toggle again and confirm it switches back to Sport; tap the left toggle from either and confirm it goes to Dernière notif.
24. Set an app to "Dernière notif" and send it 3-5 distinct notifications in a row (even if the app itself only ever shows one at a time, replacing the previous one): confirm each one gets its OWN tile here, up to 5, instead of only the latest one being shown.
25. Force-stop the app (kill its process) with a Sofascore match already active, then reopen it: confirm that match appears in Toutes notifs right away, without needing a score update first.
26. Confirm a Sofascore entry's tile in this view is the same size as a match tile in the Sport view (not the smaller original size) — logo-size changes to one should always show up in the other.
27. With 5 matches/notifications active at once, confirm all 5 tiles fit within the widget's actual width on your LockStar placement (none pushed off to the side) — then remove down to 1-2 and confirm the remaining tile(s) stay small/compact rather than stretching to fill the row.
28. Install an update over an existing install (rather than a fresh install) with at least one eligible notification already sitting in the shade beforehand: confirm it shows up in Toutes notifs shortly after the update finishes, without needing to toggle notification access or reboot.
29. Set WhatsApp (or Signal) to "Toutes" and send yourself 3-5 messages in the SAME conversation in a row: confirm only ONE tile appears/updates here for that conversation, not one per message (this is the opposite of step 24, which still applies to a non-conversation app like a news app reusing its notification id).
30. With a Sofascore match live and scoring multiple times in a row: confirm only ONE tile updates for that match here (score/lastScorer refresh in place), not a new tile per goal.
31. With a live rugby match (or any sport whose events are worded "Score : H - A Team" rather than "But : H - A"): confirm the score/bracket parses correctly in both the Sport and Toutes notifs views instead of falling back to the raw notification text.
32. Toggle "Titre ↔ texte" for one app, send it a notification, and confirm the swap only shows up in the actual Now Bar/system-notification mirror — the widget's Dernière notif title/text and that same notification's tile in Toutes notifs should both still show the ORIGINAL (non-swapped) fields.
33. Confirm Sofascore no longer appears at all in the Accueil app-selection list, then confirm a live Sofascore match's tile in Toutes notifs always renders as a match card (score/period), never as a generic image+title tile, and never as two separate tiles for the same match.
34. With more than 5 eligible mirrored notifications active at once (so Toutes notifs is showing 5), dismiss one of the 5 shown WITHOUT sending anything new: confirm a 6th, previously-untracked-but-still-active one takes its place instead of the list just shrinking to 4.
35. Mark a mirrored Gmail notification as read (or delete/open it) from inside Gmail itself, so it disappears from the shade without you touching its widget tile: confirm its Toutes-notifs tile disappears too within a few seconds, without needing to force-stop the app.
36. With at least one stale/already-dismissed entry that somehow lingered, reboot the phone: confirm Toutes notifs comes back empty (or showing only what's genuinely still active) rather than replaying old entries from before the reboot.

## Next iteration

The next useful step is to test this exact build on the S26 Ultra and inspect which notification fields Samsung exposes for contact avatars, action buttons and Now Bar rendering. Then the Samsung-specific layer can be tightened without changing the core listener architecture.

ALL-mode mirror tracking and the widget's live actions/`PendingIntent` are currently in-memory only and reset if the listener process is killed by the system; persisting whatever can safely be persisted (not the `PendingIntent`s themselves) would reduce how often that happens in practice.

The Toutes-notifs tile proportions (32dp image area, 14dp icon badge, 9sp two-line title, tile width now a share of the row rather than a fixed size — see the width-responsive-tiles fix above) are a first pass sized by eye, not measured on the S26 Ultra, same as the Sport view's own tiles originally were — worth a look once flashed, especially whether 5 tiles per row feels too wide or too cramped on your LockStar placement.

## Origin

Now Bar Mirror absorbed a previously separate app, **Sport Watch Complication** (`github.com/yanninho65/Sport-watch-complication`), which is where the Sport tab and the `wear/` module come from. The decision record, file-by-file change list and one-time watch-reinstall steps from that merge are kept in [`FUSION_SPORT_WATCH.md`](FUSION_SPORT_WATCH.md) for reference; this README otherwise documents the app as it stands now, not as a diff against that history.
