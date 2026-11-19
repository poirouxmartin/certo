"""UI language checks: English by default, French as a translation.

Martin's rule (2026-11-19): every user-visible string lives in a resource
(Android values/ + values-fr/, web i18n.js I18N.en + I18N.fr), never inline.
These checks are heuristics on purpose: an accented Latin letter in a code
string literal is almost always French UI text that skipped the resources.
"""

import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "src" / "sure_weather" / "web"
ANDROID = ROOT / "app-android" / "android" / "app" / "src" / "main"
JAVA = ANDROID / "java"
RES = ANDROID / "res"

ACCENTED = re.compile(r"[À-ÖØ-öø-ÿŒœ]")
PLACEHOLDER = re.compile(r"%\d+\$[sd]")


def string_literals(src: str, quotes: str):
    """Yield (line, text) for every string literal outside comments.

    A small scanner instead of a regex so that "//" inside a URL string or a
    quote inside a comment does not derail it.
    """
    i, line, n = 0, 1, len(src)
    while i < n:
        c = src[i]
        if c == "\n":
            line += 1
            i += 1
        elif src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            line += src.count("\n", i, j)
            i = j
        elif c in quotes:
            j, start = i + 1, line
            while j < n and src[j] != c:
                if src[j] == "\\":
                    j += 1
                elif src[j] == "\n":
                    line += 1
                j += 1
            yield start, src[i + 1 : j]
            i = j + 1
        else:
            i += 1


def android_strings(path: Path) -> dict[str, str]:
    out = {}
    for el in ET.parse(path).getroot().iter("string"):
        if el.get("translatable") != "false":
            out[el.get("name")] = el.text or ""
    return out


def test_android_values_fr_matches_values():
    en = android_strings(RES / "values" / "strings.xml")
    fr = android_strings(RES / "values-fr" / "strings.xml")
    assert en.keys() == fr.keys()
    for key in en:
        assert sorted(PLACEHOLDER.findall(en[key])) == sorted(PLACEHOLDER.findall(fr[key])), key


def test_android_default_strings_are_english():
    en = android_strings(RES / "values" / "strings.xml")
    bad = {k: v for k, v in en.items() if ACCENTED.search(v)}
    assert not bad, f"values/strings.xml must be English: {bad}"


def test_no_hard_coded_text_in_java():
    bad = []
    ui_call = re.compile(r'(?:call\.reject|setTextViewText|setContentTitle|setContentText|drawText)'
                         r'\(\s*"[^"]*[A-Za-z]{3}')
    for path in JAVA.rglob("*.java"):
        src = path.read_text(encoding="utf-8")
        bad += [f"{path.name}:{ln}: {t}" for ln, t in string_literals(src, '"') if ACCENTED.search(t)]
        bad += [f"{path.name}: {m.group(0)}" for m in ui_call.finditer(src)]
    assert not bad, "user-visible text must come from R.string:\n" + "\n".join(bad)


def test_no_hard_coded_text_in_layouts():
    attr = re.compile(r'android:(?:text|contentDescription)="([^"@]*[A-Za-z][^"]*)"')
    bad = [f"{p.name}: {m.group(1)}" for p in (RES / "layout").glob("*.xml")
           for m in attr.finditer(p.read_text(encoding="utf-8"))]
    assert not bad, "layout text must use @string/ (tools:text for previews): " + ", ".join(bad)


def i18n_keys(lang: str) -> set[str]:
    src = (WEB / "i18n.js").read_text(encoding="utf-8")
    start = src.index(f"\n  {lang}: {{")
    end = src.index("\n  },", start)
    # Several short keys can share a line: 'gt_temp: "Temp", gt_rain: "Rain",'.
    return set(re.findall(r'(?:^    |, )(\w+): "', src[start:end], re.M))


def test_web_i18n_en_and_fr_have_the_same_keys():
    en, fr = i18n_keys("en"), i18n_keys("fr")
    assert en - fr == set(), f"missing in fr: {sorted(en - fr)}"
    assert fr - en == set(), f"missing in en: {sorted(fr - en)}"


def test_no_hard_coded_french_in_web_code():
    bad = []
    ternary = re.compile(r'LANG\s*===?\s*"(?:en|fr)"\s*\?\s*["`]')
    for name in ("app.js", "local-provider.js", "local-learn.js", "mini.js", "sw.js", "theme.js"):
        src = (WEB / name).read_text(encoding="utf-8")
        bad += [f"{name}:{ln}: {t[:60]}" for ln, t in string_literals(src, "\"'`") if ACCENTED.search(t)]
        bad += [f"{name}: inline language ternary {m.group(0)}" for m in ternary.finditer(src)]
    assert not bad, "web UI text must go through tr() and i18n.js:\n" + "\n".join(bad)


def test_web_html_defaults_are_english():
    # HTML text is what shows before i18n.js runs (and to crawlers): it must
    # be the English default, French only comes from I18N.fr.
    bad = []
    for name in ("index.html", "mini.html", "manifest.webmanifest"):
        for n, line in enumerate((WEB / name).read_text(encoding="utf-8").splitlines(), 1):
            if ACCENTED.search(line):
                bad.append(f"{name}:{n}: {line.strip()[:80]}")
    assert not bad, "\n".join(bad)
