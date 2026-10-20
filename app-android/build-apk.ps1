# Build the debug APK of the Capacitor shell from src/sure_weather/web.
# Usage (from anywhere): powershell -ExecutionPolicy Bypass -File app-android\build-apk.ps1
# Output: app-android\dist\certo-v<versionName>.apk
# Native tools (npx, gradlew) are checked via $LASTEXITCODE: PS 5.1 turns their
# stderr into terminating errors under 'Stop' when redirected.
$ErrorActionPreference = 'Continue'

$appDir  = $PSScriptRoot
$repo    = Split-Path -Parent $appDir
$webSrc  = Join-Path $repo 'src\sure_weather\web'
$www     = Join-Path $appDir 'www'
$android = Join-Path $appDir 'android'
$dist    = Join-Path $appDir 'dist'
$log     = Join-Path $dist 'build-apk.log'
New-Item -ItemType Directory -Force -Path $dist | Out-Null

# Gradle needs the SDK location; default to the standard Android Studio path
# so no machine-specific local.properties has to be written.
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
# AGP 8.2 needs JDK 17, and the system JDK here is 11; the Adoptium 17 build
# also failed the TLS handshake with dl.google.com (aapt2 download, 20 Oct
# 2026). Android Studio's bundled JBR works: prefer it when present.
$jbr = 'C:\Program Files\Android\Android Studio\jbr'
if (Test-Path (Join-Path $jbr 'bin\java.exe')) { $env:JAVA_HOME = $jbr }
# The JBR still dropped dl.google.com handshakes on this network (AAR
# downloads, same day) while curl worked; IPv4 + TLS 1.2 fixed it.
if (-not $env:GRADLE_OPTS) {
    $env:GRADLE_OPTS = '-Djava.net.preferIPv4Stack=true -Dhttps.protocols=TLSv1.2 -Djdk.tls.client.protocols=TLSv1.2'
}

if (-not (Test-Path (Join-Path $appDir 'node_modules\@capacitor\cli'))) {
    throw 'node_modules missing: run "npm install" in app-android first'
}

# 1. Stage www. Layout mirrors the released APK (assets/public):
#    the pages and the SW/manifest sit at the root, everything under /static/
#    (the URLs referenced by index.html, sw.js and manifest.webmanifest).
$rootFiles = @('index.html', 'mini.html', 'manifest.webmanifest', 'sw.js', 'widget-template.json')
if (Test-Path $www) { Remove-Item $www -Recurse -Force }
New-Item -ItemType Directory -Path (Join-Path $www 'static') | Out-Null
foreach ($f in Get-ChildItem -Path $webSrc -File) {
    if ($rootFiles -contains $f.Name) {
        Copy-Item $f.FullName (Join-Path $www $f.Name)
    } else {
        Copy-Item $f.FullName (Join-Path $www "static\$($f.Name)")
    }
}
# Subfolders too (vendor/leaflet): without them the in-app map stayed an
# empty grey box, leaflet.js answered 404 in v1.4.0 to v1.5.1.
foreach ($d in Get-ChildItem -Path $webSrc -Directory) {
    if ($d.Name -eq '__pycache__') { continue }
    Copy-Item $d.FullName (Join-Path $www "static\$($d.Name)") -Recurse
}
Write-Host "www staged from $webSrc"

# 2. Sync web assets and plugins into the native project.
Push-Location $appDir
try {
    & npx cap sync android *> $log
    if ($LASTEXITCODE -ne 0) { throw "cap sync failed, see $log" }

    # 3. Gradle debug build (output redirected to the log, checked via exit code).
    Push-Location $android
    try {
        & .\gradlew.bat assembleDebug --console=plain *>> $log
        if ($LASTEXITCODE -ne 0) { throw "gradle assembleDebug failed, see $log" }
    } finally {
        Pop-Location
    }
} finally {
    Pop-Location
}

# 4. Copy the APK to dist/ with the version from build.gradle.
$gradle = Get-Content (Join-Path $android 'app\build.gradle') -Raw
if ($gradle -notmatch 'versionName\s+"([^"]+)"') { throw 'versionName not found in app/build.gradle' }
$version = $Matches[1]

$apkSrc = Join-Path $android 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $apkSrc)) { throw "APK not found: $apkSrc" }
$apkDst = Join-Path $dist "certo-v$version.apk"
Copy-Item $apkSrc $apkDst -Force
$size = (Get-Item $apkDst).Length
Write-Host "APK: $apkDst ($size bytes)"
