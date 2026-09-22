# Now Bar Mirror

Android app for a Samsung Galaxy phone + Galaxy Watch pair, two independent jobs:

1. **Notification mirroring** — mirrors selected apps' notifications into persistent notification(s) eligible for Samsung One UI / Android Live Update surfaces, plus a lock-screen widget and a watch-face complication.
2. **Live sport scores** — reads Sofascore's own notifications and drives a separate Wear OS watch-face complication, optionally refined against TheSportsDB or the Live Tennis API.

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
- On listener (re)connect, and right after an app update (`PackageUpdateReceiver` forces a rebind since Android doesn't reliably re-fire `onListenerConnected()` after an in-place update), catches up on any already-active eligible notification instead of only reacting to new ones. "Dernière notif" (widget + watch complication) needs no separate catch-up: it's derived live from `WidgetAllNotificationsStore`, itself self-healed against `getActiveNotifications()` on every reconnect (see below).
- "Service actif" switch pauses mirroring without revoking notification access. Settings (modes, invert flags, service state, widget-actions flag) export/import as JSON via the system file picker.
- Android 16+: requests a promoted ongoing notification + Samsung ongoing-activity metadata for Now Bar eligibility.

### Lock-screen widget

4×1, background-less (`NowBarWidgetProvider`), meant to sit on the lock screen via a host like Samsung LockStar (also addable as an ordinary home-screen widget). Three views, `WidgetViewModePrefs` remembers which is showing:

- **Dernière notif** (default) — derived directly from `WidgetAllNotificationsStore`'s most recent entry (same store as Toutes notifs below, any kind: ALL/LATEST app notification or a Sofascore match) — no separate store to keep in sync. Shows source icon, original title/text, image, dismiss button; tap opens the notification (or launches the source app if the live `PendingIntent` was lost to a process restart).
- **Sport** — up to 5 Sofascore matches (`SofascoreWidgetStore`): combined team image, score (bracket on whichever side just scored), period/status. Live/recently-finished matches sort first. Tapping a tile peeks it (see below), never dismisses the source notification.
- **Toutes notifs** — up to 5 recently received notifications (`WidgetAllNotificationsStore`), fed by both listeners into one shared history: any mirrored app's notification (original title always, regardless of the invert setting) and every Sofascore notification (rendered exactly like a Sport tile, same image/score/period layout down to matching tile heights — any future appearance change to a Sofascore tile must be mirrored between the two views). Self-heals against a fresh `getActiveNotifications()` snapshot on every listener reconnect and after every removal, so a stale tile can never linger and a freed slot is refilled from whatever's already active — refilled in one batched push (`WidgetAllNotificationsStore.pushAll` / `NowBarWidgetProvider.pushToAllNotificationsBatch`) rather than one push per notification, so the widget lands directly on the final top-5 instead of visibly stepping through each intermediate candidate. Identity: a Sofascore match or a messaging conversation always updates its ONE tile in place (by key); anything else keys by (notification id, post time), so e.g. 5 separate "Dernière notif"-mode postings from one app get 5 separate tiles. A tile disappears as soon as its notification is dismissed anywhere; tapping only opens it, never dismisses it.

Left toggle (rotating arrows, under the app icon): LATEST ↔ whichever of Sport/Toutes-notifs was last shown. Right toggle (far right edge): Sport ↔ Toutes notifs directly. Both scale their 5 tiles to the widget's actual width so all 5 always fit.

**Peek** — tapping a Sport/Toutes-notifs tile shows that one notification full-format (`WidgetPeekPrefs`), reusing the same content block Dernière notif renders with (title, text, image, dismiss, actions), without touching which notification Dernière notif itself shows. Opening a peek is a plain broadcast (`ACTION_OPEN_PEEK`) — no Activity involved, since a lock-screen widget host can dismiss the keyguard as a side effect of launching any Activity at all, which an in-place widget update must avoid. Tapping the peek's own content opens the real notification directly (a plain `PendingIntent`, exactly like Dernière notif's own tap) without dismissing it. The peek closes on its own — either when its notification disappears (`closePeekIfShowing`, called from both listeners), or automatically 15 seconds after it opened (`scheduleAutoClosePeek`, an `AlarmManager` one-shot that only fires if the same entry is still being peeked) — closing is never tied to the content tap itself. While peeking, the left column shows the peeked entry's own app icon plus the small arrows glyph, both bound to "close and return to the tile grid".

"Actions dans le widget" (off by default) adds up to 3 of the notification's text actions under Dernière notif's title/text — only while this app's process has stayed alive since that notification arrived (a `PendingIntent` can't be reconstructed from storage after a restart; same limitation applies to any tap-to-open, in all views alike, and to ALL-mode mirror tracking). A "silent" action (mark as read/delete/archive/mute, detected from the action's own semantic role) is routed through the same listener service the dismiss button uses instead of firing its captured `PendingIntent` directly: replaying that `PendingIntent` alone does trigger the source app's real handling, but not the notification's disappearance — that only happens for a genuine notification tap, as a system side effect of that specific dispatch path, not of the `PendingIntent` itself. So after firing, this also cancels the source notification the same way the dismiss button does, matching what tapping that same action for real (Now Bar/shade) already looks like. Every other action (Répondre, Appeler…) still fires its `PendingIntent` directly, unchanged. Same mechanism on the watch's notification-detail screen (`WearActionRelayService`).

## Sport tab — live scores on the watch

Sofascore is the source of truth for match identity (team names, image) — API lookups below never override those. The tab lists every Sofascore match currently in the notification center ("Dernière notification" always first); whichever is active drives the watch complication.

- **Match selection** (`SofascorePrefs`): "Dernière notification" (auto) or a specific match, tap a row to pick. Falls back to auto if the chosen match's notification disappears.
- **Score/status parsed directly from the notification text** (`SofascoreNotificationParser`, no network call): football/handball (half-time/full-time, extra time — ET1/MTP/ET2 — and penalty shootouts — PEN while awaiting/during the shootout, final score shown as "match (penalties)" once decided), rugby (half-time/full-time, plus period 1/2 on scoring lines that carry no minute), basketball (quarters), tennis (shown by set number like the set-tally sports below, not a "live" label), set-tally sports (table tennis, volleyball) — sport detected from wording since Sofascore doesn't flag it explicitly. Team names split on the title's " - ", or on "@" for American-sports notifications ("Away @ Home").
- **Optional per-match API override** ("API" button): TheSportsDB (multi-sport) or the Live Tennis API (needs a locally-stored key), independently for score and/or period. `ApiOverrideFollowService` polls in the background while active; cleared automatically when the match's notification disappears.
- Own `NotificationListenerService` (`sport.SofascoreNotificationListenerService`), separate from the Accueil tab's — see [Two separate notification-access toggles](#two-separate-notification-access-toggles).

### Watch complications (`wear/`)

Two independent complications, assignable separately on the watch face:

- **"Score en direct"** (`ScoreComplicationService`, `LONG_TEXT`/`SMALL_IMAGE`) — the active Sofascore match. `WatchSync` pushes it to the watch (`MatchListenerService`) over the Wear Data Layer API (path `/match`) whenever it changes; `MatchClock` formats status/period per sport, `ComplicationImageComposer` composes the `SMALL_IMAGE` bitmap. `ScoreComplicationService.fetchPersistedMatch` re-reads the Data Layer item directly as a fallback when the watch process was killed and restarted since the last push (`onDataChanged` doesn't re-fire on its own for a DataItem already synced before that restart) — decoding shared with `MatchListenerService` via `MatchDataCodec`. Tapping opens Sofascore on the watch.
- **"Notification"** (`NotificationComplicationService`, `SMALL_IMAGE` only) — the same "dernière notif" the lock-screen widget shows (any mirrored app, ALL/LATEST/Sofascore, no distinction), kept as its own complication rather than folded into "Score en direct" so the two can be assigned to different slots. `WatchNotificationSync` (phone, path `/notification`) is called from `NowBarWidgetProvider.syncWatchToLatest()`, on every widget rebuild, reading the same `WidgetAllNotificationsStore` entry the widget's own "Dernière notif" view derives from — one source of truth, no separate store to drift out of sync. `NotificationDataListenerService` receives live updates; `NotificationComplicationService.fetchPersistedNotification` re-reads the `/notification` Data Layer item directly, decoding shared with `NotificationDataListenerService` via `NotificationDataCodec`. Unlike `ScoreComplicationService` below, this re-read now runs on EVERY `onComplicationRequest` (not just when the in-memory `NotificationInfoStore` is empty) — a purely local call, no network — so the complication can't get stuck on a stale in-memory value just because this process's own `onDataChanged` happened to miss a live update. This re-read writes through `NotificationInfoStore.updateIfNotOlder` (see below) rather than overwriting the store unconditionally, so it can't regress to an older notification if it races a live push that already landed. Tapping opens a full-screen detail screen — see below.

### Notification detail screen (`NotificationDetailActivity`)

Full-screen view opened by tapping the "Notification" complication, matching the real Wear OS/Galaxy Watch notification look, inside a `SwipeDismissFrameLayout` (swipe-to-dismiss the screen itself, `androidx.wear:wear`):
- Header: the notification's own image and the source app's icon side by side, centered as one group (each shows only its own picture, no falling back of one onto the other).
- Title, then text/detail lines, in the system's own text appearance (`?android:attr/textAppearanceLarge`/`Medium`, unconditional — not a hardcoded size) and no background behind the text, same as a real system notification's body.
- One full-width pill per notification action (single line, ellipsized if too long, system text size/font), always followed by an "Aff. sur tél." pill (never a "Bloquer notifications" one, unlike the real system menu), then a round grey delete button. All of these (action pills, "Aff. sur tél.", delete) give immediate touch feedback — a slight shrink plus a lighter fill on press, matching a real system notification's buttons — since a plain shape background has no press state of its own.

For a messaging conversation or a Sofascore match, the body instead lists every line Android's own notification already bundles for that one notification — no separate history is built or persisted anywhere:
- Sofascore groups several updates into one `InboxStyle` notification; `SofascoreNotificationListenerService.collectLines` reads its `EXTRA_TEXT_LINES` (`detailLines`), same lines Android's own notification shade would show, capped at 6 by Android itself.
- A conversation notification carries its own message list via `NotificationCompat.MessagingStyle`; `MirrorNotificationListener.messageLinesFor` reads `.messages`/`.historicMessages` (capped at 10, newest first).

`WatchNotificationSync.send` carries these lines plus the action labels and the entry's identity (`entryKey`/`entryPostTimeMillis`/`kind`) to the watch alongside the usual title/text/images (`NotificationInfo`, decoded by `NotificationDataCodec`). A `PendingIntent` can't cross devices, so only the labels travel — tapping an action button, the delete button, or "Aff. sur tél." sends a one-way message back to the phone (`PhoneRelay`, `MessageClient`, paths `/notifdetail/action`, `/notifdetail/dismiss`, `/notifdetail/open`) identifying the entry; the phone's `WearActionRelayService` receives it and calls `NowBarWidgetProvider.fireAction` (fires the real `PendingIntent` from its live in-memory action cache), `.dismissEntry` (routes to the right listener's own `ACTION_DISMISS_WIDGET`, Sofascore vs. generic, by `kind`), or `.openEntry` for "Aff. sur tél.".

`.openEntry` doesn't fire the entry's own `PendingIntent` directly: a plain background service reacting to a Bluetooth message from the watch (no visible window) is blocked by Android's background-activity-launch restrictions from starting an Activity that way — especially the app's own fallback `PendingIntent` (`launchAppPendingIntent`, used whenever the entry has no `contentIntent` of its own, which is the common case for a Sofascore match). Instead it posts a dismissible, high-importance relay notification (its own channel, `open_on_phone_v2`, vibration on; the `_v2` suffix because a channel's importance is fixed at first creation and can't be changed in place afterwards) carrying the resolved open target both as its `contentIntent` (manual tap) and as a `setFullScreenIntent` (auto-launch), reusing the same title/text/image resolution the widget's "Dernière notif" and the watch sync already compute (`resolveAllNotifEntryContent`). Silently does nothing if the entry has already aged out of the phone's capped-at-5 "Toutes notifs" history by the time the watch's request arrives.

The full-screen intent only actually auto-launches while the phone is locked/screen off (Android's own rule for every app, call/alarm-style apps included — a device already unlocked always falls back to a plain tappable heads-up instead, so a manual tap keeps working there exactly as before) and only once Yann has granted the app "Use full screen intent" access: on Android 14+ this isn't auto-granted to a non-telephony/alarm app, so `MainActivity`'s Accueil tab has a dedicated button (`full_screen_intent_button`, hidden below API 34) opening `Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`, plus a status line reading `NotificationManager.canUseFullScreenIntent()`. When the phone was locked at post time, `openEntry` also schedules `ACTION_AUTO_CANCEL_OPEN_ON_PHONE` (`NowBarWidgetProvider`'s own `onReceive`, an `AlarmManager` one-shot ~2.5s out) to cancel the relay notification itself, since the full-screen launch has done its job by then and there's no point in it lingering in the shade; when the phone was unlocked, it's left alone since it's then the only way left to open the target.

`NotificationDetailActivity` applies the same always-re-read-the-persisted-item logic as the "Notification" complication above: it paints whatever's in `NotificationInfoStore.current` instantly, then immediately re-reads the persisted `/notification` DataItem in the background and applies that once it resolves — so a tap always ends up showing exactly what a concurrent complication refresh would also compute, instead of two independently stale in-memory snapshots.

Both re-reads write through `NotificationInfoStore.updateIfNotOlder` (comparing `entryPostTimeMillis`) instead of overwriting `current` unconditionally: a direct Data Layer re-read isn't guaranteed to be at least as fresh as what's already in memory — a notification that just arrived can already be correctly reflected via the live `onDataChanged` push while a concurrent re-read still sees an older, not-yet-propagated synced item. Without this guard, that re-read could silently regress a correct, freshly-pushed display back to an older notification; `null` ("cleared", see `WatchNotificationSync.sendCleared`) is always accepted, since it's an explicit signal rather than a competing older notification.

**Known issue**: none of the above fixes the case where the phone-side push itself never happens at all — if Samsung battery/background-app management freezes the two `NotificationListenerService`s, nothing new ever reaches the watch's Data Layer for either re-read to find, and force-stopping the phone app (which reconnects the listeners) remains the only known recovery. Not yet root-caused; check the phone app's battery settings (unrestricted / not in sleeping-apps list) before assuming the watch side is still at fault. `ScoreComplicationService` ("Score en direct") still has the OLD blind-cache pattern (`MatchScoreStore.current ?: fetchPersistedMatch()`, only re-reads when the in-memory cache is empty, no freshness guard against regressing) and hasn't been updated to match — same class of bug could still show there.

Setup: assign "Score en direct" to a `LONG_TEXT` and/or round `SMALL_IMAGE` slot, and/or "Notification" to another round `SMALL_IMAGE` slot, on the watch face.

**Signing**: phone and watch apps must share `applicationId` (`com.yann.nowbarmirror`) and signing key, or the phone-side send silently goes nowhere. `wear/build.gradle.kts` mirrors `app/build.gradle.kts` on both, reusing the `DEBUG_KEYSTORE_B64` secret. Also depends on `androidx.wear:wear:1.3.0` (classic Wear support library, for `SwipeDismissFrameLayout` in the notification detail screen) — deliberately not Compose for Wear, to avoid adding the Compose Compiler Gradle plugin.

## Architecture

The two tabs stay deliberately separate (different problems: verbatim mirroring vs. structured match parsing) but share:

- **`NotificationImageExtractor`** (top-level) — used by both listeners: MessagingStyle contact photo → `EXTRA_PICTURE` → `getLargeIcon()` → `EXTRA_LARGE_ICON`.
- **`WatchNotificationSync`** (top-level) — mirrors "Dernière notif" (`WidgetAllNotificationsStore`'s most recent entry) to the watch's "Notification" complication, called from `NowBarWidgetProvider.syncWatchToLatest()` on every widget rebuild — same derived value the widget itself renders, so the two never drift independently. Also carries what the watch's notification detail screen needs (detail lines, action labels, entry identity) — see [Notification detail screen](#notification-detail-screen-notificationdetailactivity).
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
├── WatchNotificationSync.kt           sends "Dernière notif" to the watch's "Notification" complication, incl. detail lines/actions/entry identity (Wear Data Layer API)
├── WearActionRelayService.kt          receives action-button-tap/dismiss/"Aff. sur tél." requests from the watch's detail screen (paths /notifdetail/action, /notifdetail/dismiss, /notifdetail/open), relays to NowBarWidgetProvider.fireAction/dismissEntry/openEntry
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
    ├── NowBarWidgetProvider.kt         the 4x1 lock-screen widget — all three views + the peek overlay; derives "Dernière notif" from WidgetAllNotificationsStore and syncs the watch (syncWatchToLatest)
    ├── SofascoreWidgetStore.kt         persists up to 5 Sofascore matches (Sport view)
    ├── WidgetAllNotificationsStore.kt  persists up to 5 recently received notifications, generic + Sofascore — also the sole source for "Dernière notif" (most-recent entry, any kind)
    ├── SofascoreMatchPresentation.kt   phone-side score/period formatting, shared by Sport + Sofascore-in-Toutes-notifs
    ├── WidgetPeekPrefs.kt              which tile (if any) is currently peeked full-format
    └── WidgetViewModePrefs.kt          which of the three views is showing, + which of Sport/Toutes-notifs was shown last

wear/src/main/kotlin/com/yann/nowbarmirror/wear/
├── ScoreComplicationService.kt        "Score en direct" — LONG_TEXT / SMALL_IMAGE complication data source, with a persisted-DataItem fallback for a freshly-restarted watch process
├── MatchListenerService.kt            receives match data from the phone (path /match)
├── MatchDataCodec.kt                  shared decode of the /match DataMap
├── MatchClock.kt                      formats status/period per sport
├── MatchScore.kt                      score text, incl. the "who just scored" bracket, + in-memory MatchScoreStore
├── ComplicationImageComposer.kt       composes the SMALL_IMAGE bitmap for both complications
├── NotificationComplicationService.kt "Notification" — SMALL_IMAGE-only complication data source, with a persisted-DataItem fallback for a freshly-restarted watch process
├── NotificationDataListenerService.kt receives "Dernière notif" updates from the phone (path /notification)
├── NotificationDataCodec.kt           shared decode of the /notification DataMap (incl. detail lines/actions/entry identity)
├── NotificationInfo.kt                decoded payload + in-memory NotificationInfoStore
├── NotificationDetailActivity.kt      full-screen Galaxy Watch-style detail screen opened by tapping "Notification" (image/icon header, title/text or detail lines, action pills + "Aff. sur tél.", delete) — see Notification detail screen
└── PhoneRelay.kt                      sends action-tap/dismiss/"Aff. sur tél." requests from the detail screen back to the phone (Wear Data Layer API messages)
```

## Limitations

- Uses only public Android notification-listener APIs plus the Samsung ongoing-activity hint — doesn't depend on undocumented Samsung internals; Samsung ultimately controls whether/how a notification appears in the actual Now Bar.
- Inline-reply `RemoteInput` actions aren't reconstructed anywhere (system mirror or widget).
- Any tap-to-open, in all widget views and the peek alike, plus ALL-mode mirror tracking, relies on a live `PendingIntent`/in-memory state held since the notification was mirrored — these reset if the app's process is killed and restarted (tap-to-open then falls back to opening the source app, or Sofascore itself for a match), until the next relevant notification/event repopulates them. A Toutes-notifs tile stays otherwise intact across a rebuild as long as it's stayed in the capped-at-5 history the whole time.
- Widget Sport view / a Sofascore entry in Toutes notifs never show the API override or a live tennis set's game score — kept simple to fit small tiles.
- Toutes-notifs is a capped-at-5 "recently received" list, not a log — an entry drops as soon as its source notification is gone (shade, source app, "effacer tout"), not kept for reference.
- Declares `<queries>` for `MAIN`/`LAUNCHER` (app-selection screen) and `com.sofascore.results` (Sport tab), required on API 30+ package-visibility rules.
- Sport tab depends entirely on Sofascore's own notification wording — a wording change on their end could break parsing until updated here. "Match commencé" briefly shows 0-0 with football-style status on every sport until sport-specific vocabulary kicks in (cosmetic, no functional impact).
- Both watch complications only update live while both devices are reachable over the Wear Data Layer API (paired, Bluetooth/Wi-Fi connected); both also re-read the last persisted Data Layer item on their own if the watch process restarted since the last live push (`ScoreComplicationService.fetchPersistedMatch`, `NotificationComplicationService.fetchPersistedNotification`) — see the Known issue under [Watch complications](#watch-complications-wear) for a case this doesn't fully cover.

## Build

Built via GitHub Actions (`.github/workflows/build.yml`), not locally in Android Studio (phone-only day-to-day workflow):

1. Push to `main`, or trigger manually from **Actions** → "Build debug APK" → **Run workflow**.
2. Download the `NowBarMirror-debug-apk` artifact (phone) and, if the watch app changed, `NowBarMirror-wear-debug-apk` too.
3. Install `app-debug.apk`.
4. In **Now Bar Mirror**: Accueil tab — enable notification access, allow the app's own notifications (Android 13+), on Android 14+ grant "Use full screen intent" access (see [Notification detail screen](#notification-detail-screen-notificationdetailactivity) for what it unlocks), pick apps to mirror. Sport tab — grant notification access separately for Sofascore.
5. Place the widget from the home-screen widget picker, or a lock-widget host (LockStar) for the lock screen.
6. For the watch: install `wear-debug.apk` (e.g. via GeminiMan WearOS Manager), then assign "Score en direct" and/or "Notification" on the watch face.

Debug signing key: generated once via the separate "Generate debug keystore" workflow, stored as the `DEBUG_KEYSTORE_B64` repo secret.

To build locally instead (Android Studio, JDK 17): open the folder, let Gradle sync, `app`/`wear` → `assembleDebug`.
