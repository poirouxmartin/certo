# Startup perf

Measures cold start of `com.sureweather.app` on an emulator (adb + WebView CDP).

```
node measure-startup.mjs --runs 5 --label baseline [--serial emulator-5554] [--out C:/tmp/sw-perf]
```

Env: `ADB` overrides the adb path (default `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`).
Output: `${out}/${label}.json` (all runs) and a summary table on stdout.
`tFirstForecast` = ms from `am start` to `#now-temp` first showing a digit.
`totalTimeNative` = `TotalTime` reported by `am start -W`.
Failed runs are kept with `error` and excluded from medians.
