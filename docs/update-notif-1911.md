# Update notification (2026-11-19)

Branch `dev/poiroux/update-notif-1911`, from `dev/poiroux/english-ui-1911` (816963a).
Code commit: `8ed479c` (feat(android): notify when an app update is available).

## What was added

- `UpdateCheckJob` (JobService): persisted periodic job, every 6 h (1 h flex),
  network required. Reads the same source as the in-app banner, the raw
  `VERSION` file of `poirouxmartin/certo` on master, and posts
  "Update available: vX.Y.Z" when it is newer than the installed versionName.
  Scheduled (idempotently) on every app start from `MainActivity`.
- `UpdateCheck`: pure logic (version compare, VERSION parsing, notify-once,
  stale check), unit-tested in `UpdateCheckTest` (7 tests).
- Notify once: the last notified version is stored in the `certo_update`
  SharedPreferences. A version is notified only if newer than both the
  installed one and the last notified one, and it is recorded only once the
  notification was actually posted.
- Channel `app_updates` ("App updates" / "Mises à jour de l'appli"), separate
  from the weather channel so either can be muted alone.
- Tap: opens `MainActivity` (action `com.sureweather.app.OPEN_UPDATE`); the
  launch check shows the existing update banner. If the app was already open,
  the WebView reloads to run that check again. Opening the app once the update
  is installed clears a leftover notification.
- POST_NOTIFICATIONS: already declared and asked once at first launch (lock-screen
  weather, `LockScreenPlugin`). The job never prompts; without the permission
  it stays silent and retries at the next period.
- Strings in `values/` (EN) and `values-fr/` (FR); `.gitignore` whitelists the
  two new classes.

Why JobScheduler and not WorkManager: same pattern as `WidgetRefreshJob`, no new
dependency in the APK.

## How to test manually

1. Install the debug APK, open the app once (schedules the job), allow notifications.
2. Make the installed version older than `VERSION` on master (e.g. build with
   versionName `1.6.4`), or wait for the next release.
3. Force the job: `adb shell cmd jobscheduler run -f com.sureweather.app 4731`.
4. Expect the "Update available: v1.6.5" notification; tap it: the app opens
   with the update banner.
5. Run the job again: no second notification for the same version.
6. Reset: `adb shell pm clear com.sureweather.app`.

## Verification

- JVM unit tests (`gradlew testDebugUnitTest`): 21 passed, 0 failed (7 new).
- `pytest tests/test_i18n.py`: 7 passed (EN/FR string files in sync).
- `gradlew assembleDebug`: BUILD SUCCESSFUL.
- Not run on an emulator or phone in this session.
