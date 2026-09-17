# Now Bar Mirror

Android app for a Samsung Galaxy phone + Galaxy Watch pair. It has two independent jobs:

1. **Notification mirroring** — mirrors selected apps' notifications into persistent notification(s) intended to be eligible for Samsung One UI / Android Live Update surfaces, plus a lock-screen widget showing the latest one.
2. **Live sport scores** — reads Sofascore's own notifications and drives a Wear OS watch-face complication with the live score, optionally refined against TheSportsDB or the Live Tennis API.

The two live on separate tabs of the same screen (**Accueil** / **Sport**) and share only what genuinely overlaps between them (notification listening infrastructure, image extraction) — see [Architecture](#architecture).

It's a two-module Gradle project: the phone app (`app/`) and a Wear OS watch app (`wear/`) for the score complication.

## Accueil tab — notification mirroring

- Listens to notifications using `NotificationListenerService`.
- Ignores its own notifications, ongoing notifications, and group-summary bundles (e.g. WhatsApp's "X new messages").
- Only mirrors apps you've explicitly selected — nothing is mirrored by default.
- Per app, you choose one of two modes:
  - **Dernière notif (LATEST)** — all apps in this mode share a single mirror slot; whichever posts most recently occupies it, replacing whatever was shown before.
  - **Toutes (ALL)** — every distinct notification from this app gets its own persistent mirror, shown at the same time.

  Example: Signal and WhatsApp set to "Toutes", Le Monde and Mediapart set to "Dernière notif". Two WhatsApp messages + two Signal messages + one Mediapart notification all show at once (five mirrors). A Le Monde notification arriving next replaces the Mediapart one, since both share the LATEST slot.
- Per app, an independent "Titre ↔ texte" checkbox swaps which field is treated as the title — useful when an app's "text" field is actually the more relevant short summary for compact surfaces like the widget.
- Copies title and text, the notification's large icon/contact image (or the source app icon as a fallback), and up to three standard action buttons, reusing their original `PendingIntent`.
- If an original notification is removed, its mirror is removed. If a mirror is removed by the user (swipe, or "clear all"), the original is cancelled the same way. Removing our own mirror to replace its content (LATEST-mode swap, ALL-mode update) does **not** trigger this — only a genuine user dismissal does.
- "Revenir à la précédente après suppression" (on by default) — when the shared "Dernière notif" slot is dismissed while other LATEST-mode originals are still active elsewhere, the slot is re-posted with the next most recent survivor instead of just being cleared.
- On listener connect/reconnect (app restart, permission just granted), any already-active eligible notification from a selected app is mirrored immediately rather than waiting for the next one to be posted.
- A "Service actif" switch lets you pause mirroring entirely without uninstalling or revoking notification access; existing delete-sync keeps working for mirrors already showing while paused.
- Settings (per-app modes, invert flags, service on/off, widget actions, fallback) can be exported to / imported from a JSON file via the system file picker.
- On Android 16+, requests a promoted ongoing notification so the system can consider it for Live Update surfaces, and includes the Samsung ongoing-activity application metadata used by current One UI implementations.

### Choosing which apps to mirror

Open **Now Bar Mirror** → **Applications à mirrorer** (Accueil tab). Each installed app with a launcher icon is listed with a segmented mode selector (Aucun / Dernière / Toutes) and the invert-title/text checkbox. The same screen has the service on/off switch, the "revenir à la précédente" fallback switch, the widget-actions switch, and the export/import buttons.

### Lock-screen widget

A 4x1, background-less widget (`NowBarWidgetProvider`) mirrors whichever selected app posted most recently — across ALL- and LATEST-mode apps together, unlike the system mirror slots which keep them separate. It shows the source app's icon, title/text, the notification's image if any, and a dismiss button; tapping it opens the original notification, or launches the source app if the live `PendingIntent` isn't available anymore (e.g. after a process restart).

It's meant to sit directly on the lock screen over the wallpaper (no card background), placed there through a third-party lock-widget host such as Samsung's LockStar — it can also be added like any normal widget from the home-screen widget picker.

"Actions dans le widget" (off by default) adds up to three of the notification's own text action buttons (e.g. "Répondre", "Marquer comme lu") in a second row under the title/text — only while this app's process has stayed alive since that exact notification arrived, since a `PendingIntent` can't be reconstructed from storage after a process restart (same limitation as the tap-to-open).

## Sport tab — live scores on the watch

Sofascore is the source of truth for match identity: team names and the notification's own image always come from Sofascore, never from the API lookups below. The tab lists every Sofascore match currently in your notification center ("Dernière notification" always first), and whichever one is active drives the watch complication.

- **Choosing which match drives the watch**: tap a row to pick it (`SofascorePrefs`) — either "Dernière notification" (auto: whichever Sofascore notification updated most recently) or a specific match. If the chosen match's notification disappears (game over, notification cleared), it automatically falls back to "Dernière notification" rather than showing nothing.
- **Score/status parsed directly from the Sofascore notification text** (`SofascoreNotificationParser`), no network call needed for this part. It recognizes: football and handball (half-time / full-time), basketball (quarters), tennis, and set-tally sports like table tennis and volleyball — detected from the notification's own wording, since Sofascore doesn't flag the sport explicitly. "Match terminé : H - A" is always trusted first when present, since Sofascore doesn't always post period-by-period lines in strict chronological order at full time.
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
- **"Catch up on already-active notifications" on listener connect** — both listeners mirror/refresh from whatever is already in the notification center the moment they (re)connect, not just notifications posted afterward.

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
    ├── NowBarWidgetProvider.kt        the 4x1 lock-screen widget
    └── WidgetNotificationStore.kt     persists the widget's current notification

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
- Both the widget's tap-to-open and its action buttons, and ALL-mode mirror tracking, rely on a live `PendingIntent`/in-memory state held since the notification was mirrored — after the app's process is killed and restarted these reset (tap-to-open falls back to opening the source app; actions just don't show) until the next notification arrives.
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

## Next iteration

The next useful step is to test this exact build on the S26 Ultra and inspect which notification fields Samsung exposes for contact avatars, action buttons and Now Bar rendering. Then the Samsung-specific layer can be tightened without changing the core listener architecture.

ALL-mode mirror tracking and the widget's live actions/`PendingIntent` are currently in-memory only and reset if the listener process is killed by the system; persisting whatever can safely be persisted (not the `PendingIntent`s themselves) would reduce how often that happens in practice.

## Origin

Now Bar Mirror absorbed a previously separate app, **Sport Watch Complication** (`github.com/yanninho65/Sport-watch-complication`), which is where the Sport tab and the `wear/` module come from. The decision record, file-by-file change list and one-time watch-reinstall steps from that merge are kept in [`FUSION_SPORT_WATCH.md`](FUSION_SPORT_WATCH.md) for reference; this README otherwise documents the app as it stands now, not as a diff against that history.
