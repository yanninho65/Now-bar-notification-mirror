# Now Bar Mirror

Android app for Yann's Samsung Galaxy phone + Galaxy Watch. Two-module Gradle project: phone app `app/`, Wear OS app `wear/` (same `applicationId` `com.yann.nowbarmirror`, same signing key — mandatory for the Data Layer).

Two independent jobs, two tabs of one screen (**Accueil** / **Sport**):

1. **Notification mirroring** (Accueil) — mirrors selected apps' notifications into persistent notifications eligible for the Samsung Now Bar, feeds three lock-screen widgets and the watch's **"Notification"** complication. The same listener also feeds the watch's **"Messages"** complication from the apps chosen in **Applications de messagerie (montre)** (independent of mirror modes).
2. **Live sport scores** (Sport) — parses Sofascore's own notifications and drives the watch's **"Score en direct"** complication (optionally refined by TheSportsDB / Live Tennis API).

This README is a snapshot of current behavior for the next working session — not a changelog, no dates/quotes/test lists. Update it only when Yann asks.

## Session workflow (read first)

- Clone directly (public, no auth, branch `main`): `git clone https://github.com/yanninho65/Now-bar-notification-mirror.git`. Never push.
- Deliver changed files reproducing the repo tree — a zip if more than 2 files — never a patch. List explicitly any file Yann must **delete** by hand on GitHub (uploads can't delete).
- No Android SDK, no Google Maven in the sandbox. Real build = GitHub Actions (see [Build](#build)). **Before delivering, run the type-check**: `bash tools/compile-check/check.sh` from the repo root (downloads kotlinc 2.2.20 + android-36.jar on first run, then compiles `app/` minus UI files (MainActivity, AppSelection*, MessageAppsActivity, adapters) and `wear/` against `tools/compile-check/stubs/`). 0 errors = Kotlin-level OK; resources/manifest/real library signatures still only checked by CI. If code starts using a new androidx/Play Services API, add it to the stubs (and check the baseline compiles first to tell stub gaps from real errors).
- Yann works from his phone, so no logcat: some error paths show a `Toast` ("Widget: …") as a temporary diagnostic.
- Code comments are long and dated (history of each fix, Yann's quotes). Keep new comments shorter; don't rewrite old ones needlessly.

## Architecture at a glance

```
Phone                                                         Watch
─────                                                         ─────
MirrorNotificationListener ──┐                                PhoneDataListenerService
  (apps set LATEST/ALL)      ├─► WidgetAllNotificationsStore      ├─ /match        → MatchScoreStore       → ScoreComplicationService
SofascoreNotificationListener┘    (history, 6/kind)               ├─ /notification → NotificationInfoStore → NotificationComplicationService
MirrorNotificationListener ──► MessagesWatchSync (/messages) ───► └─ /messages     → MessagesStore         → MessagesComplicationService
  (message apps, active notifs)                                                                              → MessagesActivity
  (com.sofascore.results)  ──► SofascoreWidgetStore (6 matches)                                              → NotificationDetailActivity
                                   │                                                                         PhoneRelay (action/dismiss/open)
            NowBarWidgetProvider.requestUpdate()  (coalesced, 300 ms)                                              │
                                   ▼                                                                               │
            refreshAllNow(): syncWatchToLatest() → WatchNotificationSync (/notification)                           │
                             4x1 + Compact 4x2 + Triple 4x2 widgets                                                │
            SofascoreNotificationListenerService.refresh() → WatchSync (/match)                                    │
            WearActionRelayService ◄── /notifdetail/{action,dismiss,open}, /msgdetail/{action,dismiss,open,openapp} ┘
```

Single source of truth: **"Dernière notif"** (widget LATEST view, the watch "Notification" complication, the detail screen) is always `WidgetAllNotificationsStore.get().first()` — never a separate store.

## Phone — notification mirroring (`MirrorNotificationListener`)

- Ignores its own notifications, ongoing, group summaries, media playback (`isMirrorableShape`). Mirrors only apps chosen in **Applications à mirorer**: **LATEST** (one shared mirror id `9001`, replaced by the most recent LATEST-mode post) or **ALL** (one mirror per original, ids from `9100`).
- Per-app "Titre ↔ texte" swap applies to the Now Bar mirror only — widgets/watch always show the original title/text.
- Copies title, text, image (`NotificationImageExtractor`: MessagingStyle photo → `EXTRA_PICTURE` → `getLargeIcon()` → `EXTRA_LARGE_ICON`), up to 3 actions (original `PendingIntent`s). Android 16+: promoted-ongoing request + Samsung ongoing-activity meta-data.
- Deletion sync both ways (user swipe of a mirror = `REASON_CANCEL`/`REASON_CANCEL_ALL` only). "Revenir à la précédente" (default on): dismissing the LATEST slot promotes the next still-active one.
- On (re)connect (`rebuildStateFromActiveNotifications`): rebuilds in-memory mirror bookkeeping from posted mirrors' extras (`mirror.original.key`), drops orphan mirrors, prunes the widget history against `getActiveNotifications()`, bootstraps mirrors for already-active notifications, then one batched `refillAllNotifsHistory`. `PackageUpdateReceiver` forces a rebind of both listeners after an app update.
- `isEligibleGeneric` = shape filters + not Sofascore + mode ≠ NONE: defines what counts in "Toutes notifs" and its "+X" badge (`WidgetAllNotificationsStore.saveActiveGenericCount`).
- Battery gating: `onNotificationPosted` reads the app mode first and returns for NONE before any `getActiveNotifications()`; `onNotificationRemoved` only prunes/refills/refreshes when the removed entry was tracked or is eligible; Sofascore removals are left to the Sofascore listener.
- "Service actif" switch pauses without revoking access. Settings export/import as JSON (`SettingsBackup`).

## Phone — watch "Messages" feed (`MessagesWatchSync`)

- Source = the notification center itself (`getActiveNotifications()` of the connected `MirrorNotificationListener`), never a separate store: non-ongoing, non-summary notifications of the apps in `MessageAppsPrefs` (defaults until saved: WhatsApp(+Business), Google Messages, Samsung Messages, Signal, Telegram, Messenger), most recent first, max 10. Picked in `settings.MessageAppsActivity` (button in Accueil; code-built checkbox list; saved in settings export/import).
- Listener hooks: `onNotificationPosted`/`onNotificationRemoved` of a message app (before the mirror-mode gating) and `onListenerConnected` → `scheduleMessagesSync()` (coalesced 400 ms, main thread). "Service actif" off → empty list sent.
- `/messages` DataMap: `messages` (key, postTime, pkg, title, text, detailLines = MessagingStyle lines via shared `messageLines`, actionLabels), `apps` (every selected app launchable on the phone: pkg, label, count), top-level assets `img_<i>` (contact photo, `NotificationImageExtractor`, cached per posting) and `icon_<pkg>`. Deduped by a text signature.
- `actionsFor`: the notification's actions minus those with a RemoteInput (inline reply not relayed), max 3; the watch's index refers to this filtered list, recomputed at fire time.
- Unread count per app: distinct conversations (shortcutId → conversation title → title); mail apps (known packages or CATEGORY_EMAIL) count distinct subjects (InboxStyle lines, else EXTRA_TEXT). Summaries only used if the app posted nothing else.
- Watch → phone: `/msgdetail/{action,dismiss,open}` (JSON `key`) → `MirrorNotificationListener.fireMessageAction` / `dismissMessage` / `openMessageOnPhone` (static entry points on the connected instance, run on its main thread, against the live notification). `/msgdetail/openapp` (JSON `pkg`) → `WearActionRelayService.openAppOnPhone`. Both "open" paths reuse `NowBarWidgetProvider.postOpenOnPhone` (relay notification + full-screen intent, extracted from `openEntry`).

## Phone — Sport (`sport.SofascoreNotificationListenerService`)

- Separate listener with its own system toggle ("Now Bar Mirror — Sport (Sofascore)"). One Sofascore notification = one match, updated in place (`InboxStyle`, `EXTRA_TEXT_LINES` most recent first, max 6). Never groups by `groupKey` (Sofascore puts all matches in one group).
- Teams from title split on `" - "` or `"@"` (American sports, order kept). Score/status parsed from text by `SofascoreNotificationParser` (football/handball incl. ET/penalties, rugby, basketball quarters, tennis sets, table tennis/volleyball); unknown wording → raw-line fallback. Wording-dependent.
- Watched match (`SofascorePrefs`): LATEST (auto) or CHOSEN (falls back to auto when its notification disappears). In auto mode `pickLatestAvoidingDuplicate` skips the match already shown as "Dernière notif" when ≥ 2 matches are active. Order matters: the history push runs **before** `refresh()` in every caller.
- Optional per-match API override (`SofascoreApiOverridePrefs`, TheSportsDB / Live Tennis API with local key): `ApiOverrideFollowService` foreground-polls (60 s football, 180 s tennis) and calls `refreshIfConnected()` — from a background thread. Widget never shows overrides.
- `refresh()` sends `/match` only when `key|postTime|MatchResult` changed (`lastWatchSignature`); `sendCleared` also deduped.

## Phone — widgets (`widget/`)

Three background-less widgets (lock screen via LockStar, or home screen), all rendered by `NowBarWidgetProvider`'s internal functions:

- **4x1** `NowBarWidgetProvider` — one row, three views (`WidgetViewModePrefs`): **Dernière notif**, **Sport** (5 matches, live/recent first via `sortedForWidget`, finished > 5 min demoted), **Toutes notifs** (5 entries, generic + Sofascore tiles). Left toggle: LATEST ↔ last of Sport/Toutes; right toggle: Sport ↔ Toutes.
- **4x2** `NowBarWidgetProviderCompact` — row 1 = Sport/Toutes strip (shared toggle state, current "Dernière notif" filtered out), row 2 = Dernière notif/peek.
- **4x2 double row** `NowBarWidgetProviderTriple` — row 1 generic-only notifs, row 2 Sofascore matches, row 3 Dernière notif/peek; no toggle; "+X" overflow badges from the TRUE active counts (generic count excludes Sofascore; minus 5 shown, minus 1 if row 3 shows that kind). Declared 4x2 (`minHeight=140dp`).
- Picker previews are static XML mockups (`*_preview.xml`), never touched by code.
- PendingIntent request-code ranges must stay distinct: 4x1 peeks 4300/4400, Compact 4700/4800, Triple 4900/5000, auto-close peek 4200, action-fire 4500+, open-on-phone cancel 4600, toggles 0/1, close-peek 2.
- **Peek**: tapping a tile shows it full-format (`WidgetPeekPrefs`) via a plain broadcast (`ACTION_OPEN_PEEK`) — **never an Activity** (any Activity launched from a lock-screen widget interacts with the keyguard; several trampoline attempts broke). Closes when its notification disappears (`closePeekIfShowing`, both listeners) or after 15 s (non-wakeup alarm, same-entry check).
- "Actions dans le widget" (off by default): up to 3 text actions. "Silent" actions (semantic mark-read/delete/archive/mute) are routed through the listener (`ACTION_FIRE_WIDGET_ACTION`) which fires the PendingIntent then cancels the source notification; others fire directly.
- Refresh model: listeners write stores synchronously, then `requestUpdate()` (throttled, max one refresh per 300 ms, any thread). `refreshAllNow()` runs `syncWatchToLatest()` (deduped by entry identity) then rebuilds only the widgets actually placed. Direct taps (`onReceive`) call `refreshAllNow()` immediately. The Compact/Triple bottom row and refresh body are shared (`renderPeekOrLatestRow`, `refreshProvider`).

### Stores

- `WidgetAllNotificationsStore` — history, max 6 per kind (GENERIC / SOFASCORE_MATCH, capped independently, merged by recency). Identity: Sofascore match or conversation (`isConversation`: shortcutId / CATEGORY_MESSAGE / MessagingStyle) collapses by key; anything else is (key, postTime). Self-heals with `pruneAgainstActive` + batched refill (`pushAll`) — one store write per batch.
- `SofascoreWidgetStore` — the 6 matches shown by Sport views + true active count. `pushSofascoreMatches` skips save/refresh when the kept list's signature is unchanged.
- Both: JSON in SharedPreferences (parsed once per change, `ParsedCache`), images as **content-addressed PNG files** (`WidgetImageFiles`: one file per posting key+postTime, kept across saves, orphans deleted; legacy slot files migrated), downscaled to 256 px, images passed lazily (`imageLoader`) so unchanged postings are never re-extracted/re-encoded. `save` is `@Synchronized` (main thread + API-poll thread).
- Live `PendingIntent`s/actions/detail lines are in memory only (`liveAllNotifIntents`, `liveSofascoreIntents`…): lost on process restart → taps fall back to launching the source app (Sofascore for a match).
- `BitmapUtils` (top-level): single `drawableToBitmap`/`circular`/`downscale`, LRU caches for app icons (`AppIcons`) and decoded store images (`ImageFiles`, keyed by path + lastModified).

## Watch (`wear/`)

- `PhoneDataListenerService` receives both `/match` and `/notification` (one service, two `<data>` filters), applies them with `FreshStore.setLive`, and requests the matching complication refresh.
- `PhoneDataLayer`: shared path-specific re-read of the persisted DataItem (`readMatch`/`readNotification`), asset decoding, complication refresh request. `FreshStore` (base of `MatchScoreStore`/`NotificationInfoStore`) orders values by the phone's send `timestamp`: live pushes always apply, re-reads apply only if not older (covers "cleared" too).
- Both complications re-read the persisted item on **every** request (local, cheap: codecs reuse the in-memory value — no asset decode — when `syncTimestamp` matches), fall back to memory only if the read fails, and cache the composed bitmap (`ComposedImageCache`). `UPDATE_PERIOD_SECONDS=600` (safety net only; updates are push-driven).
- **"Score en direct"** (`ScoreComplicationService`, LONG_TEXT / SMALL_IMAGE) — tap opens Sofascore on the watch. `MatchClock` formats status per sport; `ComplicationImageComposer.composeRoundImage` draws image/score/period (320 px).
- **"Notification"** (`NotificationComplicationService`, SMALL_IMAGE) — tap opens `NotificationDetailActivity`: image + app icon header, title/text or detail lines (conversation messages via MessagingStyle, max 10; Sofascore lines, max 6), action pills + "Aff. sur tél." + delete, all with press feedback, `SwipeDismissFrameLayout` (`androidx.wear:wear`, not Compose).
- **"Messages"** (`MessagesComplicationService`, SMALL_IMAGE) — `ComplicationImageComposer.composeMessagesImage`: up to 4 contact circles (most recent top-left; 1–3 laid out centered), app-icon badge bottom-right, initial on a colored disc when no photo; nothing to show → white envelope with "0" below. If the "Notification" complication is placed (`NotificationComplicationService.Presence`: instance ids persisted from onComplicationActivated/Deactivated + every request) and its entry key equals a message's key, that message is left out (`visibleMessages`). Tap opens `MessagesActivity`.
- `MessagesActivity` — same look as the detail screen (`item_message.xml` mirrors `activity_notification_detail.xml`; `DetailViews` = shared pills/lines/press feedback/circular crop, also used by `NotificationDetailActivity`). Top: "Sur la montre" row (selected apps whose package is launchable on the watch → open there) and "Sur le téléphone" row (the others → `/msgdetail/openapp`); an app on both is only in the watch row; red count badge top-right. Then every message (unfiltered): action pills, "Aff. sur montre" only if the package exists on the watch, "Aff. sur tél.", delete (hidden locally until the next push). Live re-render via `MessagesStore.onChanged`.
- All SMALL_IMAGE bitmaps have a **transparent background** (no background disc).
- `PhoneRelay` sends `/notifdetail/{action,dismiss,open}` messages (JSON kind/key/postTime/actionIndex). Phone side `WearActionRelayService` → `NowBarWidgetProvider.fireAction` / `dismissEntry` / `openEntry`. `openEntry` can't start an Activity from the background: it posts a high-importance relay notification (channel `open_on_phone_v2`) with content + full-screen intent; auto-cancelled 2.5 s later if the phone was locked. Needs "Use full screen intent" access (button in Accueil, Android 14+).

## Known issues / limitations

- If Samsung battery management freezes the phone listeners, nothing reaches the watch or widgets until the app is force-stopped. Check the app is "Unrestricted" and not in sleeping apps before debugging watch code.
- Inline-reply `RemoteInput` not reconstructed. PendingIntent-dependent features (tap-to-open, actions, ALL-mode tracking) degrade after a process restart until new events arrive.
- Widget Sport tiles never show API overrides or tennis game score.
- Sport parsing depends on Sofascore's wording; "Match commencé" briefly shows football-style 0-0 on every sport.
- `<queries>` for LAUNCHER apps and `com.sofascore.results` required (API 30+) — on the watch too (LAUNCHER, for "Aff. sur montre" / the watch row).
- "Messages": no inline reply from the watch; "Aff. sur montre"/watch row open the app, not the conversation, and only when the watch app has the phone's package name (Samsung Messages may differ — unverified). Mail counts need the mail app ticked in the message-app list.
- The `settings` package's folder should be lowercase `settings/`; if an uppercase `Settings/` folder is still present in the repo, the move hasn't been finished.

## Project layout

```
app/src/main/java/com/yann/nowbarmirror/
├── MainActivity.kt                 Accueil/Sport tabs, permission buttons, Sofascore match list
├── MirrorNotificationListener.kt   Accueil listener (mirrors + generic history feed)
├── NotificationImageExtractor.kt   image extraction, shared by both listeners
├── BitmapUtils.kt                  shared bitmap helpers + icon/image caches
├── PackageUpdateReceiver.kt        rebinds both listeners after an app update
├── WatchNotificationSync.kt        "/notification" sender
├── MessagesWatchSync.kt            "/messages" sender (message apps, counts)
├── WearActionRelayService.kt       receives /notifdetail/* from the watch
├── settings/                       MirrorMode, AppMirrorPrefs, ServicePrefs, LatestModePrefs,
│                                   WidgetActionsPrefs, SettingsBackup, AppSelectionActivity/Adapter,
│                                   MessageAppsPrefs, MessageAppsActivity
├── sport/                          SofascoreNotificationListenerService, SofascoreNotificationParser,
│                                   SofascorePrefs, SofascoreApiOverridePrefs, ApiOverrideCache,
│                                   ApiOverrideFollowService, SportsDbApi, LiveTennisApi, TennisApiKeyPrefs,
│                                   WatchSync ("/match" sender + bitmapToAsset), Models, adapters
└── widget/                         NowBarWidgetProvider (4x1 + all shared rendering/refresh),
                                    NowBarWidgetProviderCompact, NowBarWidgetProviderTriple,
                                    WidgetAllNotificationsStore, SofascoreWidgetStore, WidgetImageFiles,
                                    SofascoreMatchPresentation (phone copy of wear score/period formatting),
                                    WidgetPeekPrefs, WidgetViewModePrefs

wear/src/main/kotlin/com/yann/nowbarmirror/wear/
├── PhoneDataListenerService.kt     /match + /notification + /messages receiver
├── PhoneDataLayer.kt               shared re-read/decode/refresh + FreshStore + ComposedImageCache
├── ScoreComplicationService.kt     "Score en direct"
├── NotificationComplicationService.kt  "Notification"
├── NotificationDetailActivity.kt   detail screen
├── MessagesComplicationService.kt  "Messages"
├── MessagesActivity.kt             messages list screen (app rows + messages)
├── MessageInfo.kt                  MessageInfo/MessageApp/MessageList + MessagesStore + MessagesDataCodec
├── DetailViews.kt                  shared Galaxy Watch-style views of both screens
├── PhoneRelay.kt                   watch → phone messages
├── MatchDataCodec.kt / NotificationDataCodec.kt
├── MatchScore.kt (+ MatchScoreStore) / NotificationInfo.kt (+ NotificationInfoStore)
├── MatchClock.kt                   per-sport status labels
└── ComplicationImageComposer.kt    SMALL_IMAGE bitmaps

tools/compile-check/                sandbox type-check (check.sh, genR.py, stubs/) — not part of any build
```

## Build

GitHub Actions `.github/workflows/build.yml` ("Build debug APK", on push to `main` or manual): JDK 17, Gradle 8.13, AGP 8.13, Kotlin 2.2.20, compileSdk/targetSdk 36 (min 26 phone, 30 watch). Signs both APKs with the `DEBUG_KEYSTORE_B64` secret (generated once by the "Generate debug keystore" workflow).

1. Download artifacts `NowBarMirror-debug-apk` (phone) and, if the watch changed, `NowBarMirror-wear-debug-apk`.
2. Phone: install; Accueil → notification access, app notifications, full-screen-intent access (14+), pick apps; Sport → its own notification access.
3. Place widgets (picker or LockStar). Watch: install via GeminiMan WearOS Manager, assign "Score en direct" (LONG_TEXT / round SMALL_IMAGE), "Notification" and/or "Messages" (round SMALL_IMAGE).
