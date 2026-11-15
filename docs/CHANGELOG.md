# Changelog

## 1.6.4 (2026-11-15)

### App

- The current weather sits in the notification bar by default: temperature next to the clock, conditions and the next hours in the shade and on the lock screen, updated with the widget. The app turns it on once at the first forecast (Android asks for the notification permission there).
- New settings sheet (⚙️ in the top bar) with a switch for that notification; it replaces the 🔒 button. When Android blocks the notifications, the sheet says so and opens Android's settings.

### Widget

- Rain and wind are no longer cut at the bottom with a larger font: the ⟳ button moved onto the map corner (the header line is shorter), the hour lines lost their extra font padding, the rain line disappears when no hour has rain, and the rain chance drops the 💧 so it stays on one line. Text sizes are unchanged.

## 1.6.3 (2026-11-14)

### App

- The update banner compares the repository version with the version Android reports for the installed app, no longer with a constant in the page: v1.6.1 shipped with that constant still at 1.6.0, so the banner kept offering an update the installer refused ("not newer than the installed one").
- If the installer still answers "not newer", the banner hides instead of showing the error.
- The footer shows the installed version as reported by Android.
- First launch after an update clears the WebView cache, so no page file of the previous version can outlive it (saved places and forecasts are kept).
- A test now fails when `VERSION`, the Android versionName and the page's version constant disagree.

## 1.6.2 (2026-11-14)

### App

- First launch asks for your location or a city instead of silently showing Paris, and says when geolocation is refused.
- The trust pill reads "confidence NN%" and opens a sheet explaining calibration, the rain chance and "uncal.".
- Network errors are translated and mention the cached forecast left on screen; a forecast over 1 h old shows its age.
- Place suggestions while typing, with a typo-tolerant fallback (Photon); favorite names are prefilled with the city.
- Geocoding follows the UI language; unknown browser languages fall back to English.
- Fixes: the in-app updater no longer offers the version already installed; readable active range button in dark mode.

### Widget and notification

- Radar favours the forecast (3 radar frames, 2 h of forecast, smoother morph); larger texts and map filling the widget height.
- The lock-screen notification shows the temperature as its status-bar icon.

### Security

- Admin token compared in constant time; floors raised on vulnerable transitive Python dependencies.
- Releases now ship a single certo-vX.Y.Z.apk.

## 1.6.1 (2026-10-27)

### Lock screen

- Optional weather on the lock screen (🔒 button in the app): a silent, ongoing, public notification with temperature, condition and the next 5 hours, refreshed with the widget every 15 min. Swiping it away turns it off.
- Limitation: phones have no third-party lock-screen widgets (removed in Android 5; the Android 15+ return is limited to the tablet hub, and OEM lock screens only list their own), so a notification is the one surface every phone shows before unlocking.

### Widget

- Radar fix: frames piled up in the flipper on each refresh, so the animation and its time label could freeze on old frames.
- Less clutter: pressure removed, 6 hours shown, rain chance hidden under 20 %.
- Rain icon refined from mm/h, probability and cloud cover: no rain icon for traces under 0.2 mm/h, light-rain icon under 1 mm/h.

## 1.6.0 (2026-10-20)

### Widget

- Refreshes on its own every 15 min through a persisted JobScheduler periodic job, re-armed after reboot. It also refreshes when the app is left and reopened.
- Renders instantly from a cached state: never blank, and shows the data time.
- Text appears in about 1 s and the map within about 2-3 s (measured on emulator: stage 1 0.6-1.1 s, stage 2 1.4-2.6 s).
- Radar animation: 1 h of observed radar every 10 min, plus 2 h of 15-min forecast from Open-Meteo `minutely_15`. Frames cross-fade, with a big time label, a "radar" / "prévision" tag, a progress bar with a "now" tick, and an amber frame border for forecast frames.
- Larger map (120 dp).
- Frames are passed to the launcher as content URIs (read grant to the home app only) instead of bitmaps, which were approaching the 1 MB binder limit.
- Limitation: since Android 8, apps cannot listen to screen unlock in the background. The 15-min job and app open/close are the refresh triggers.

### Stability

- Radar crash fix: when Android killed the WebView renderer under memory pressure, Chromium killed the whole app ("Render process kill ... wasn't handled by all associated webviews, killing application"). The app now rebuilds its UI, at most 2 times per minute.
- Radar zoom is capped at 12 while radar overlays are shown. Z7 radar tiles were being scaled up to z18, which produced a huge GPU texture.

### In-app radar

- 13 observed frames (2 h) plus up to 8 forecast frames (15 min, 2 h), computed from Open-Meteo on a 7x7 grid (zoom >= 8).
- All frames are preloaded and cross-faded. The player waits for tiles instead of flashing blank frames.
- Time, observed/forecast and relative-time overlay. Timeline with a "now" marker and an amber future part. Intensity legend.
- Opens on "now" at zoom 10, auto-plays when visible, and pauses when hidden.
- RainViewer's free API no longer provides nowcast frames.

### UI

- Denser layout: 2-row header, compact hero, single-line range and metric selectors, merged sun and UV card, compact tables with opaque sticky headers, 2-line hints.
- Dark mode contrast fixed: all text is at least 4.5:1, and map controls are themed.
- Light mode no longer looks muddy at night.
- Chart labels no longer overlap.
- Metric chips are translated.

### Build

- `build-apk.ps1` prefers Android Studio's JBR (JDK 17).
- `build-apk.ps1` forces IPv4 and TLS 1.2 for Gradle (fixes dl.google.com handshake failures).
