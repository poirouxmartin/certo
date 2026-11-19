# English UI by default (2026-11-19)

Decision (Martin, 2026-11-19, card 500): the Certo UI is English by default;
French is a translation picked from the device locale. Branch
`dev/poiroux/english-ui-1911`, from `master` (1.6.5). Not pushed, not released.

## Where strings live now

| Surface | Resource | Selection |
|---|---|---|
| Native (widget, lock-screen notification, radar frame tags, updater and notification errors, widget picker text) | `app-android/android/app/src/main/res/values/strings.xml` (English), `values-fr/strings.xml` (French, original wording) | Android resource resolution from the device locale |
| Screens (WebView UI: home, settings, radar, sources, mini page) | `src/sure_weather/web/i18n.js`, `I18N.en` and `I18N.fr` | `navigator.language` (follows the device locale in the WebView); the in-app FR/EN button still overrides it |

## Changes

- `WeatherIcons` returns a `Condition` (emoji + `R.string` id) instead of a
  French label. The class still has no Android API, so the JVM tests still run.
- `WeatherWidget`: condition label, "Rain 14:35" / "Rain ~15:00" / "Dry 12h",
  hour labels (`15:00` in English, `15h` in French), the radar frame tag
  (`radar` / `forecast`) and the date (device locale, it was forced to French)
  all come from resources.
- `LockScreenWeather`: the notification channel name and description come from
  resources. The body reuses the widget state, so it follows the same strings.
- `AppUpdaterPlugin`, `LockScreenPlugin`: the error messages shown in the UI
  come from resources.
- Web: the 10 inline `LANG === "en" ? … : …` ternaries became i18n keys
  (`locale`, `sure_badge`, `day_unit`, `model_tag`); the hard-coded French
  favorites tooltip and empty state, the rain bar tooltip and the sources badge
  go through `tr()`. `tr()` now falls back to English, not French.
- `index.html`, `mini.html`, `manifest.webmanifest`: the default text is English
  (it shows before `i18n.js` runs), `lang="en"`. `mini.html` now loads
  `i18n.js` and `mini.js` uses it (it was French only).
- `local-provider.js`, `local-learn.js`: internal error messages are in English;
  the UI maps them to translated text through `errorText()` (the deep analysis
  status now does too, it showed the raw message).
- `sw.js`: the push fallback body is English (the server always sends one).
- French code comments in the touched files are now in English.
- `.gitignore`: `res/values-fr` is tracked.

## Guard against new hard-coded strings

`tests/test_i18n.py` (pytest, part of the normal suite):

- `values/` and `values-fr/` have the same translatable keys and placeholders;
  `values/` holds no accented text.
- No Java string literal (outside comments) contains accented letters, and no
  literal is passed straight to `call.reject`, `setTextViewText`,
  `setContentTitle/Text` or `drawText`.
- Layout `android:text` / `contentDescription` use `@string/`.
- `I18N.en` and `I18N.fr` have the same keys.
- No accented literal and no inline language ternary in the web scripts.
- `index.html`, `mini.html` and the manifest carry no French.

The checks are heuristics: an English-only literal that skips `tr()` is not
caught by the accent rule (the audit for this pass found and fixed those by
hand).

## Verification

- `pytest`: 112 passed (7 new in `test_i18n.py`).
- JVM unit tests (`gradlew testDebugUnitTest`, JDK 17 from Android Studio's
  JBR): 14 passed, 0 failed.
- Debug APK: `app-android/dist/certo-v1.6.5.apk` (versionName unchanged).
- Emulator (AVD LowEnd, API 37), device locale switched with
  `cmd locale set-device-locale`. Screenshots in
  `C:\tmp\certo-english-ui-1911\`:
  - en-US: `en-01-launch`, `en-02-home`, `en-03-settings`, `en-04-widget`,
    `en-05-notification`, `en-06-radar`, `en-07-confidence-sources`.
  - fr-FR: `fr-02-home`, `fr-03-settings`, `fr-04-widget`,
    `fr-05-notification`, `fr-06-radar`.
  - Seen: English screens, settings sheet, radar card, widget ("Overcast",
    "Rain ~15:00", hours 12:00…17:00, "radar" tag) and notification; the same
    in French ("Couvert", "Pluie ~15h", 12h…17h, "prévision" tag).

## Known limits and follow-ups

- The widget and notification keep the language of their last refresh: after a
  locale change they switch at the next refresh (15 min, or when the app goes
  to the background).
- The in-app FR/EN button only changes the WebView; native surfaces follow the
  device locale.
- Branched from `master`: `dev/poiroux/ux-1811` (1.6.6, icon-first pass) is not
  merged yet and touches `app.js`, `index.html` and `i18n.js`; expect conflicts
  when both land. Its new strings must then pass `test_i18n.py`.
- Seen on the English confidence card: two rows read "Wind" (likely speed and
  direction sharing a label). Not caused or fixed by this pass; to confirm.
