"""v1.6.1 shipped APP_VERSION "1.6.0", so the in-app banner offered an update the installer refused as NOT_NEWER.
The APK now reads its version natively, but APP_VERSION still drives the browser/PWA footer and banner.
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VERSION_FILE = ROOT / "VERSION"
GRADLE = ROOT / "app-android" / "android" / "app" / "build.gradle"
APP_JS = ROOT / "src" / "sure_weather" / "web" / "app.js"


def _read_versions():
    version = VERSION_FILE.read_text(encoding="utf-8").strip()
    gradle = GRADLE.read_text(encoding="utf-8")
    js = APP_JS.read_text(encoding="utf-8")
    m_name = re.search(r'versionName\s+"([^"]+)"', gradle)
    m_js = re.search(r'const APP_VERSION = "([^"]+)";', js)
    assert m_name, f"versionName not found in {GRADLE}"
    assert m_js, f"const APP_VERSION not found in {APP_JS}"
    return version, m_name.group(1), m_js.group(1)


def test_versions_agree():
    version, gradle_name, js_version = _read_versions()
    assert version == gradle_name == js_version, (
        f"version mismatch: VERSION file={version!r}, "
        f"build.gradle versionName={gradle_name!r}, "
        f"app.js APP_VERSION={js_version!r}"
    )


def test_version_code_is_positive_int():
    gradle = GRADLE.read_text(encoding="utf-8")
    m = re.search(r"versionCode\s+(\d+)", gradle)
    assert m, f"versionCode not found in {GRADLE}"
    code = int(m.group(1))
    assert code > 0, f"versionCode must be > 0, got {code} in {GRADLE}"
