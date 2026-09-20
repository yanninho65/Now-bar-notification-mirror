# Now Bar Mirror

Android app for a Samsung Galaxy phone + Galaxy Watch pair, two independent jobs:

1. **Notification mirroring** — mirrors selected apps' notifications into persistent notification(s) eligible for Samsung One UI / Android Live Update surfaces, plus a lock-screen widget.
2. **Live sport scores** — reads Sofascore's own notifications and drives a Wear OS watch-face complication, optionally refined against TheSportsDB or the Live Tennis API.

Two tabs of one screen (**Accueil** / **Sport**), sharing only what genuinely overlaps (see [Architecture](#architecture)). Two-module Gradle project: phone app (`app/`) and Wear OS app (`wear/`).

## Working on this repo

Public repo — clone directly rather than through any synced copy (which can lag behind a recent push):

```
git clone https://github.com/yanninho65/Now-bar-notification-mirror.git
```

No auth needed; default branch `main`. No local Android SDK in most working sessions — see [Build](#build) (GitHub Actions, then manual install — no local `gradle build`/emulator round-trip).

Deliver changes as updated files reproducing the repo's folder structure (zip if more than 2 files), never a patch, never pushed to GitHub (Yann does that himself). Update this README only when asked — keep it a snapshot of current behavior, not a changelog: no dates, no quotes, no test checklists, no "next steps".

## Accueil tab — notification mirroring

- `NotificationListenerService` (`MirrorNotificationListener`), ignoring its own notifications, ongoing notifications, and group-summary bundles.
- Only mirrors apps explicitly selected in **Applications à mirrorer**. Per app: **Dernière notif (LATEST)** — one shared mirror slot across all LATEST-mode apps, replaced by whichever posts most recently — or **Toutes (ALL)** — every distinct notification gets its own persistent mirror.
- Per-app "Titre ↔ texte" checkbox swaps which field is the title, but only for the real Now Bar pill/mirror popup — the lock-screen widget always shows the notification's original title/text regardless.
- Copies title, text, large icon/contact image (or app icon fallback), and up to 3 action buttons, reusing their original `PendingIntent`s.
- Mirror ↔ original deletion sync both ways (a LATEST-slot swap/ALL-mode content update is not a "deletion", so doesn't trigger this).
- "Revenir à la précédente après suppression" (default on): dismissing the shared LATEST slot re-posts it with the next most recent still-active survivor instead of just clearing it.
- On listener (re)connect, and right after an app update (`PackageUpdateReceiver` forces a rebind since Android doesn't reliably re-fire `onListenerConnected()` after an in-place update), catches up on any already-active eligible notification instead of only reacting to new ones.
- "Service actif" switch pauses mirroring without revoking notification access. Settings (modes, invert flags, service state, widget-actions flag) export/import as JSON via the system file picker.
- Android 16+: requests a promoted ongoing notification + Samsung ongoing-activity metadata for Now Bar eligibility.

### Lock-screen widget

4×1, background-less (`NowBarWidgetProvider`), meant to sit on the lock screen via a host like Samsung LockStar (also addable as an ordinary home-screen widget). Three views, `WidgetViewModePrefs` remembers which is showing:

- **Dernière notif** (default) — the most recent notification across ALL/LATEST apps together. Shows source icon, original title/text, image, dismiss button; tap opens the notification (or launches the source app if the live `PendingIntent` was lost to a process restart).
- **Sport** — up to 5 Sofascore matches (`SofascoreWidgetStore`): combined team image, score (bracket on whichever side just scored), period/status. Live/recently-finished matches sort first. Tapping a tile peeks it (see below), never dismisses the source notification.
- **Toutes notifs** — up to 5 recently received notifications (`WidgetAllNotificationsStore`), fed by both listeners into one shared history: any mirrored app's notification (original title always, regardless of the invert setting) and every Sofascore notification (rendered exactly like a Sport tile, same image/score/period layout down to matching tile heights — any future appearance change to a Sofascore tile must be mirrored between the two views). Self-heals against a fresh `getActiveNotifications()` snapshot on every listener reconnect and after every removal, so a stale tile can never linger and a freed slot is refilled from whatever's already active — refilled in one batched push (`WidgetAllNotificationsStore.pushAll` / `NowBarWidgetProvider.pushToAllNotificationsBatch`) rather than one push per notification, so the widget lands directly on the final top-5 instead of visibly stepping through each intermediate candidate. Identity: a Sofascore match or a messaging conversation always updates its ONE tile in place (by key); anything else keys by (notification id, post time), so e.g. 5 separate "Dernière notif"-mode postings from one app get 5 separate tiles. A tile disappears as soon as its notification is dismissed anywhere; tapping only opens it, never dismisses it.

Left toggle (rotating arrows, under the app icon): LATEST ↔ whichever of Sport/Toutes-notifs was last shown. Right toggle (far right edge): Sport ↔ Toutes notifs directly. Both scale their 5 tiles to the widget's actual width so all 5 always fit.

**Peek** — tapping a Sport/Toutes-notifs tile shows that one notification full-format (`WidgetPeekPrefs`), reusing the same content block Dernière notif renders with (title, text, image, dismiss, actions), without touching which notification Dernière notif itself shows. Opening a peek is a plain broadcast (`ACTION_OPEN_PEEK`) — no Activity involved, since a lock-screen widget host can dismiss the keyguard as a side effect of launching any Activity at all, which an in-place widget update must avoid. Tapping the peek's own content opens the real notification directly (a plain `PendingIntent`, exactly like Dernière notif's own tap) without dismissing it. The peek closes on its own — either when its notification disappears (`closePeekIfShowing`, called from both listeners), or automatically 15 seconds after it opened (`scheduleAutoClosePeek`, an `AlarmManager` one-shot that only fires if the same entry is still being peeked) — closing is never tied to the content tap itself. While peeking, the left column shows the peeked entry's own app icon plus the small arrows glyph, both bound to "close and return to the tile grid".

"Actions dans le widget" (off by default) adds up to 3 of the notification's text actions under Dernière notif's title/text — only while this app's process has stayed alive since that notification arrived (a `PendingIntent` can't be reconstructed from storage after a restart; same limitation applies to any tap-to-open, in all views alike, and to ALL-mode mirror tracking).

## Sport tab — live scores on the watch

Sofascore is the source of truth for match identity (team names, image) — API lookups below never override those. The tab lists every Sofascore match currently in the notification center ("Dernière notification" always first); whichever is active drives the watch complication.

- **Match selection** (`SofascorePrefs`): "Dernière notification" (auto) or a specific match, tap a row to pick. Falls back to auto if the chosen match's notification disappears.
- **Score/status parsed directly from the notification text** (`SofascoreNotificationParser`, no network call): football/handball/rugby (half-time/full-time), basketball (quarters), tennis, set-tally sports (table tennis, volleyball) — sport detected from wording since Sofascore doesn't flag it explicitly.
- **Optional per-match API override** ("API" button): TheSportsDB (multi-sport) or the Live Tennis API (needs a locally-stored key), independently for score and/or period. `ApiOverrideFollowService` polls in the background while active; cleared automatically when the match's notification disappears.
- Own `NotificationListenerService` (`sport.SofascoreNotificationListenerService`), separate from the Accueil tab's — see [Two separate notification-access toggles](#two-separate-notification-access-toggles).

### Watch complication (`wear/`)

`ScoreComplicationService` (`LONG_TEXT`/`SMALL_IMAGE`). `WatchSync` pushes the active match to the watch (`MatchListenerService`) over the Wear Data Layer API whenever it changes; `MatchClock` formats status/period per sport, `ComplicationImageComposer` composes the `SMALL_IMAGE` bitmap. Tapping the complication opens Sofascore on the watch.

Setup: assign "Score en direct" to a `LONG_TEXT` and/or round `SMALL_IMAGE` slot on the watch face.

**Signing**: phone and watch apps must share `applicationId` (`com.yann.nowbarmirror`) and signing key, or the phone-side send silently goes nowhere. `wear/build.gradle.kts` mirrors `app/build.gradle.kts` on both, reusing the `DEBUG_KEYSTORE_B64` secret.

## Architecture

The two tabs stay deliberately separate (different problems: verbatim mirroring vs. structured match parsing) but share:

- **`NotificationImageExtractor`** (top-level) — used by both listeners: MessagingStyle contact photo → `EXTRA_PICTURE` → `getLargeIcon()` → `EXTRA_LARGE_ICON`.
- **Catch-up on listener (re)connect** — both listeners mirror/refresh from whatever's already active at connect time, not just what arrives afterward; `PackageUpdateReceiver` forces a reconnect right after an in-place app update so this still fires then too.
- **The widget's Sport/Toutes-notifs stores** — `sport.SofascoreNotificationListenerService` pushes match data straight into `widget.NowBarWidgetProvider`/`WidgetAllNotificationsStore`, the one deliberate cross-package exception. `SofascoreMatchPresentation` duplicates (can't share — `:wear` isn't reachable from `:app`) the formatting in `wear/MatchScore.kt`/`MatchClock.kt`.
- **Batched "Toutes notifs" refills** — both listeners' post-dismissal top-up (`MirrorNotificationListener.refillAllNotifsHistory`, `SofascoreNotificationListenerService`'s equivalent) builds every candidate entry first, then hands the whole list to `NowBarWidgetProvider.pushToAllNotificationsBatch` in one call; that single-entry `pushToAllNotifications` is just this with a one-element list, so both call sites share the same store merge (`WidgetAllNotificationsStore.pushAll`) and live-PendingIntent bookkeeping instead of two versions of it.

Otherwise: separate `NotificationListenerService`s, separate preference stores, separate packages (`com.yann.nowbarmirror` for Accueil + widget, `com.yann.nowbarmirror.sport` for Sport). The `sport/` code and the `wear/` module originated from a separate repo (`Sport-watch-complication`) later merged into this one.

### Two separate notification-access toggles

Two independent `NotificationListenerService`s → two separate system switches in **Paramètres > Notifications > Accès aux notifications** ("Now Bar Mirror notification listener" for Accueil, "Now Bar Mirror — Sport (Sofascore)" for Sport), each with its own in-app button to open that screen.

## Style

Samsung One UI look: rounded cards, bold large-title header with an Accueil/Sport tab switcher, light/dark palette follows the system theme. Accueil's app list uses a segmented Aucun/Dernière/Toutes control per row (active mode filled blue), row tinted when actively mirrored. Launcher icon: a stylized Now Bar capsule (avatar dot + two content lines) on blue, with a themed-icon layer for Android 13+/One UI.

## Project layout

```
app/src/main/java/com/yann/nowbarmirror/
├── MainActivity.kt                    entry screen: Accueil/Sport tabs + permissions + app selection link
├── MirrorNotificationListener.kt      Accueil tab's NotificationListenerService
├── NotificationImageExtractor.kt      image extraction shared between both listeners
├── PackageUpdateReceiver.kt           forces both listeners to rebind right after an app update
├── settings/                          Accueil tab preferences and screen
│   ├── MirrorMode.kt                  NONE / LATEST / ALL
│   ├── AppMirrorPrefs.kt              per-package mode + invert-title/text storage
│   ├── ServicePrefs.kt                service on/off flag
│   ├── LatestModePrefs.kt             "revenir à la précédente" fallback flag
│   ├── WidgetActionsPrefs.kt          widget action-buttons on/off flag
│   ├── SettingsBackup.kt              JSON export/import
│   ├── AppSelectionActivity.kt        the settings screen
│   └── AppSelectionAdapter.kt         RecyclerView adapter for the app list
├── sport/                             Sport tab — Sofascore + watch complication
│   ├── SofascoreNotificationListenerService.kt  its own NotificationListenerService
│   ├── SofascoreNotificationParser.kt  notification text -> MatchResult
│   ├── SofascorePrefs.kt              LATEST / CHOSEN fallback choice
│   ├── SofascoreApiOverridePrefs.kt   per-match score/period override
│   ├── ApiOverrideCache.kt            latest polled result per override
│   ├── ApiOverrideFollowService.kt    background polling for the active override
│   ├── SportsDbApi.kt / LiveTennisApi.kt
│   ├── TennisApiKeyPrefs.kt           locally-stored Live Tennis API key
│   ├── WatchSync.kt                   sends match data to the watch (Wear Data Layer API)
│   ├── Models.kt                      TeamResult / PlayerResult / LeagueResult / MatchResult
│   └── SofascoreHomeAdapter.kt / SimpleListAdapter.kt / MatchesAdapter.kt
└── widget/
    ├── NowBarWidgetProvider.kt         the 4x1 lock-screen widget — all three views + the peek overlay
    ├── WidgetNotificationStore.kt      persists the widget's current notification (Dernière notif)
    ├── SofascoreWidgetStore.kt         persists up to 5 Sofascore matches (Sport view)
    ├── WidgetAllNotificationsStore.kt  persists up to 5 recently received notifications, generic + Sofascore
    ├── SofascoreMatchPresentation.kt   phone-side score/period formatting, shared by Sport + Sofascore-in-Toutes-notifs
    ├── WidgetPeekPrefs.kt              which tile (if any) is currently peeked full-format
    └── WidgetViewModePrefs.kt          which of the three views is showing, + which of Sport/Toutes-notifs was shown last

wear/src/main/kotlin/com/yann/nowbarmirror/wear/
├── ScoreComplicationService.kt        LONG_TEXT / SMALL_IMAGE complication data source
├── MatchListenerService.kt            receives match data from the phone
├── MatchClock.kt                      formats status/period per sport
├── MatchScore.kt                      score text, incl. the "who just scored" bracket
└── ComplicationImageComposer.kt       composes the SMALL_IMAGE bitmap
```

## Limitations

- Uses only public Android notification-listener APIs plus the Samsung ongoing-activity hint — doesn't depend on undocumented Samsung internals; Samsung ultimately controls whether/how a notification appears in the actual Now Bar.
- Inline-reply `RemoteInput` actions aren't reconstructed anywhere (system mirror or widget).
- Any tap-to-open, in all widget views and the peek alike, plus ALL-mode mirror tracking, relies on a live `PendingIntent`/in-memory state held since the notification was mirrored — these reset if the app's process is killed and restarted (tap-to-open then falls back to opening the source app, or Sofascore itself for a match), until the next relevant notification/event repopulates them. A Toutes-notifs tile stays otherwise intact across a rebuild as long as it's stayed in the capped-at-5 history the whole time.
- Widget Sport view / a Sofascore entry in Toutes notifs never show the API override or a live tennis set's game score — kept simple to fit small tiles.
- Toutes-notifs is a capped-at-5 "recently received" list, not a log — an entry drops as soon as its source notification is gone (shade, source app, "effacer tout"), not kept for reference.
- Declares `<queries>` for `MAIN`/`LAUNCHER` (app-selection screen) and `com.sofascore.results` (Sport tab), required on API 30+ package-visibility rules.
- Sport tab depends entirely on Sofascore's own notification wording — a wording change on their end could break parsing until updated here. "Match commencé" briefly shows 0-0 with football-style status on every sport until sport-specific vocabulary kicks in (cosmetic, no functional impact).
- Watch complication only updates while both devices are reachable over the Wear Data Layer API (paired, Bluetooth/Wi-Fi connected).

## Build

Built via GitHub Actions (`.github/workflows/build.yml`), not locally in Android Studio (phone-only day-to-day workflow):

1. Push to `main`, or trigger manually from **Actions** → "Build debug APK" → **Run workflow**.
2. Download the `NowBarMirror-debug-apk` artifact (phone) and, if the watch app changed, `NowBarMirror-wear-debug-apk` too.
3. Install `app-debug.apk`.
4. In **Now Bar Mirror**: Accueil tab — enable notification access, allow the app's own notifications (Android 13+), pick apps to mirror. Sport tab — grant notification access separately for Sofascore.
5. Place the widget from the home-screen widget picker, or a lock-widget host (LockStar) for the lock screen.
6. For the watch: install `wear-debug.apk` (e.g. via GeminiMan WearOS Manager), then assign "Score en direct" on the watch face.

Debug signing key: generated once via the separate "Generate debug keystore" workflow, stored as the `DEBUG_KEYSTORE_B64` repo secret.

To build locally instead (Android Studio, JDK 17): open the folder, let Gradle sync, `app`/`wear` → `assembleDebug`.
